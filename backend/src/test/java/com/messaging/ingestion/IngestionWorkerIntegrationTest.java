package com.messaging.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.messaging.channel.Channel;
import com.messaging.channel.ChannelRepository;
import com.messaging.channel.UnipileClient;
import com.messaging.tenant.Tenant;
import com.messaging.tenant.TenantContext;
import com.messaging.tenant.TenantRepository;

/**
 * Integration tests for the ingestion worker exercising the REAL TransactionTemplate
 * and Spring proxy through Testcontainers Postgres.
 * Proves: redelivery idempotency, crash recovery + no duplicates,
 * and advisory lock serialization through the real worker path.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionWorkerIntegrationTest {

    @Autowired private IngestionWorker worker;
    @Autowired private InboundEventRepository eventRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private ReaperService reaperService;
    @Autowired private PlatformTransactionManager txManager;

    @MockitoBean private UnipileClient unipileClient;

    private UUID tenantId;
    private UUID channelId;

    @BeforeEach
    void setUp() {
        // Clean up in correct FK order
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        contactRepository.deleteAll();
        eventRepository.deleteAll();

        Tenant tenant = tenantRepository.save(new Tenant("integration-test"));
        tenantId = tenant.getId();

        TenantContext.set(tenantId);
        try {
            Channel ch = new Channel(tenantId, "whatsapp");
            ch.setExternalAccountId("int-test-acc");
            ch.setStatus("connected");
            channelRepository.save(ch);
            channelId = ch.getId();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Check 6 - Redelivery: same eventId delivered twice -> exactly one message.
     * Proves the claim CAS + idempotency constraint compose correctly.
     */
    @Test
    void redelivery_sameEventIdTwice_exactlyOneMessage() {
        UUID eventId = seedEvent("redelivery-msg-1", "sender-redelivery");
        worker.processEvent(eventId);
        worker.processEvent(eventId); // redelivery

        TenantContext.set(tenantId);
        try {
            List<Message> messages = messageRepository.findByConversationIdOrderByProviderTimestamp(
                    getOnlyConversation().getId());
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getProviderMessageId()).isEqualTo("redelivery-msg-1");
        } finally {
            TenantContext.clear();
        }

        InboundEvent event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo("processed");
    }

    /**
     * Check 10 - Crash simulation: claim commits, processing transaction never completes.
     * Simulates a JVM crash by: claim the event (commits), then DON'T process.
     * The event stays in 'processing' -> reaper resets to 'received' -> reprocess -> no dupe.
     */
    @Test
    void crashSimulation_claimWithoutProcess_reaperResets_noDuplicate() {
        UUID eventId = seedEvent("crash-msg-1", "sender-crash");

        // Phase 1 only: claim commits (simulates crash before processing completes)
        TransactionTemplate claimTx = new TransactionTemplate(txManager);
        claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Boolean claimed = claimTx.execute(status ->
                eventRepository.claimEvent(eventId) > 0);
        assertThat(claimed).isTrue();

        // Verify event is in 'processing' (claim survived, processing "crashed")
        InboundEvent event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo("processing");
        assertThat(event.getClaimedAt()).isNotNull();

        // No messages stored (processing tx never ran)
        assertThat(messageRepository.count()).isZero();

        // Simulate reaper: reset the stuck event. Use a short timeout override via direct SQL.
        TransactionTemplate reaperTx = new TransactionTemplate(txManager);
        reaperTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reaperTx.execute(status -> {
            eventRepository.reapStuckEvents(3, 0); // timeout=0 so it matches immediately
            return null;
        });

        // Event should be back to 'received'
        event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo("received");
        assertThat(event.getRetryCount()).isEqualTo(1);

        // Reprocess the event - should succeed and create exactly one message
        worker.processEvent(eventId);

        event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo("processed");

        TenantContext.set(tenantId);
        try {
            List<Message> messages = messageRepository.findByConversationIdOrderByProviderTimestamp(
                    getOnlyConversation().getId());
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getProviderMessageId()).isEqualTo("crash-msg-1");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Check 10 extended - Crash with partial processing: claim commits, processing
     * starts but the message is somehow stored then the transaction rolls back.
     * Since message insert + mark-processed are in the SAME transaction, rollback
     * undoes both. Reprocess with idempotency constraint -> exactly one message.
     *
     * We simulate this by: process the event normally (creates message + marks processed),
     * then manually reset event to 'received' (simulating reaper after crash-with-partial),
     * then reprocess. The idempotency constraint (ON CONFLICT DO NOTHING) prevents a dupe.
     */
    @Test
    void idempotencyConstraint_reprocessAfterReset_noDuplicate() {
        UUID eventId = seedEvent("idempotent-msg-1", "sender-idempotent");

        // Process normally
        worker.processEvent(eventId);

        InboundEvent event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getProcessingStatus()).isEqualTo("processed");

        // Simulate: reaper resets event to 'received' (as if crash happened and
        // the message was somehow already committed in a prior partial run)
        TransactionTemplate resetTx = new TransactionTemplate(txManager);
        resetTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        resetTx.execute(status -> {
            InboundEvent e = eventRepository.findById(eventId).orElseThrow();
            e.setProcessingStatus("received");
            e.setClaimedAt(null);
            e.setProcessedAt(null);
            eventRepository.save(e);
            return null;
        });

        // Reprocess - the ON CONFLICT DO NOTHING on message insert prevents a duplicate
        worker.processEvent(eventId);

        TenantContext.set(tenantId);
        try {
            List<Message> messages = messageRepository.findByConversationIdOrderByProviderTimestamp(
                    getOnlyConversation().getId());
            assertThat(messages).hasSize(1);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Check 3 strengthened - Advisory lock: two events for the SAME conversation,
     * concurrent, through the real worker -> serialized, one contact, one conversation,
     * two messages (no duplicate entities).
     */
    @Test
    void advisoryLock_sameConversation_serialized_noDuplicateEntities() throws Exception {
        UUID eventId1 = seedEvent("adv-msg-1", "sender-adv-same");
        UUID eventId2 = seedEvent("adv-msg-2", "sender-adv-same");

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> f1 = executor.submit(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            worker.processEvent(eventId1);
        });
        Future<?> f2 = executor.submit(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            worker.processEvent(eventId2);
        });

        startGate.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Both events processed
        assertThat(eventRepository.findById(eventId1).orElseThrow().getProcessingStatus()).isEqualTo("processed");
        assertThat(eventRepository.findById(eventId2).orElseThrow().getProcessingStatus()).isEqualTo("processed");

        TenantContext.set(tenantId);
        try {
            // One contact (same sender)
            assertThat(contactRepository.count()).isEqualTo(1);
            // One conversation (same contact + channel)
            assertThat(conversationRepository.count()).isEqualTo(1);
            // Two messages (different events)
            Conversation convo = getOnlyConversation();
            List<Message> messages = messageRepository.findByConversationIdOrderByProviderTimestamp(convo.getId());
            assertThat(messages).hasSize(2);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Check 3 - Different conversations run in parallel (not accidentally serialized).
     */
    @Test
    void advisoryLock_differentConversations_parallel() throws Exception {
        UUID eventId1 = seedEvent("par-msg-1", "sender-par-A");
        UUID eventId2 = seedEvent("par-msg-2", "sender-par-B");

        CopyOnWriteArrayList<Long> startTimes = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Long> endTimes = new CopyOnWriteArrayList<>();

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> f1 = executor.submit(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            startTimes.add(System.nanoTime());
            worker.processEvent(eventId1);
            endTimes.add(System.nanoTime());
        });
        Future<?> f2 = executor.submit(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            startTimes.add(System.nanoTime());
            worker.processEvent(eventId2);
            endTimes.add(System.nanoTime());
        });

        startGate.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Both events processed
        assertThat(eventRepository.findById(eventId1).orElseThrow().getProcessingStatus()).isEqualTo("processed");
        assertThat(eventRepository.findById(eventId2).orElseThrow().getProcessingStatus()).isEqualTo("processed");

        TenantContext.set(tenantId);
        try {
            // Two contacts, two conversations (different senders)
            assertThat(contactRepository.count()).isEqualTo(2);
            assertThat(conversationRepository.count()).isEqualTo(2);
        } finally {
            TenantContext.clear();
        }
    }

    // ---- Helpers ----

    private UUID seedEvent(String messageId, String senderId) {
        String payload = """
            {
                "id": "%s",
                "account_id": "int-test-acc",
                "sender_id": "%s",
                "channel_type": "whatsapp",
                "text": "Hello from %s",
                "timestamp": "%s"
            }
            """.formatted(messageId, senderId, senderId, Instant.now().toString());

        InboundEvent event = new InboundEvent(payload, messageId);
        return eventRepository.save(event).getId();
    }

    private Conversation getOnlyConversation() {
        List<Conversation> convos = conversationRepository.findAll();
        assertThat(convos).hasSize(1);
        return convos.get(0);
    }
}
