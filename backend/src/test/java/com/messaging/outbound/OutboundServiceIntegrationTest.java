package com.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.web.server.ResponseStatusException;

import com.messaging.channel.Channel;
import com.messaging.channel.ChannelRepository;
import com.messaging.channel.UnipileClient;
import com.messaging.ingestion.Contact;
import com.messaging.ingestion.ContactRepository;
import com.messaging.ingestion.Conversation;
import com.messaging.ingestion.ConversationRepository;
import com.messaging.ingestion.Message;
import com.messaging.ingestion.MessageRepository;
import com.messaging.tenant.Tenant;
import com.messaging.tenant.TenantContext;
import com.messaging.tenant.TenantRepository;

/**
 * Integration tests for OutboundService through the real Spring context
 * and Testcontainers Postgres. Proves the critical invariants the SQL-only
 * tests cannot: service-level idempotency, failure persistence, and
 * that the Unipile mock is called exactly once on double-submit.
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboundServiceIntegrationTest {

    @Autowired private OutboundService outboundService;
    @Autowired private OutboundMessageRepository outboundRepo;
    @Autowired private ConversationRepository conversationRepo;
    @Autowired private ContactRepository contactRepo;
    @Autowired private ChannelRepository channelRepo;
    @Autowired private MessageRepository messageRepo;
    @Autowired private TenantRepository tenantRepo;

    @MockitoBean private UnipileClient unipileClient;

    private UUID tenantId;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        // Clean in FK order
        outboundRepo.deleteAll();
        messageRepo.deleteAll();
        conversationRepo.deleteAll();
        contactRepo.deleteAll();

        Tenant tenant = tenantRepo.save(new Tenant("outbound-test"));
        tenantId = tenant.getId();

        TenantContext.set(tenantId);
        try {
            Channel channel = new Channel(tenantId, "whatsapp");
            channel.setExternalAccountId("test-acc");
            channel.setStatus("connected");
            channelRepo.save(channel);

            Contact contact = new Contact(tenantId, "whatsapp", "+15551234567");
            contact.setDisplayName("Test Customer");
            contactRepo.save(contact);

            Conversation conversation = new Conversation(tenantId, contact.getId(), channel.getId());
            conversationRepo.save(conversation);
            conversationId = conversation.getId();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Double-submit with the same idempotency key -> exactly ONE Unipile send,
     * ONE outbound_messages row, ONE thread message. The second call returns
     * the same message without calling Unipile again.
     */
    @Test
    void doubleSubmit_sameKey_oneUnipileSend() {
        when(unipileClient.sendMessage(any(), any(), any())).thenReturn("prov-msg-1");

        String key = "double-click-" + UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            Message first = outboundService.sendReply(tenantId, conversationId, "Hello!", key);
            Message second = outboundService.sendReply(tenantId, conversationId, "Hello!", key);

            // Same message returned
            assertThat(second.getId()).isEqualTo(first.getId());

            // Unipile called exactly once
            verify(unipileClient, times(1)).sendMessage(any(), any(), any());

            // One outbound_messages row
            assertThat(outboundRepo.findByIdempotencyKey(key)).isPresent();
            assertThat(outboundRepo.count()).isEqualTo(1);

            // One thread message
            List<Message> thread = messageRepo.findByConversationIdOrderByProviderTimestamp(conversationId);
            assertThat(thread).hasSize(1);
            assertThat(thread.get(0).getDirection()).isEqualTo("outbound");
            assertThat(thread.get(0).getAuthor()).isEqualTo("human");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Concurrent double-submit: two threads race with the same idempotency key.
     * INSERT ON CONFLICT DO NOTHING ensures exactly one wins the insert.
     * The loser gets a 409 CONFLICT (send in progress). No duplicate sends.
     */
    @Test
    void concurrentSubmit_sameKey_exactlyOneSend() throws Exception {
        when(unipileClient.sendMessage(any(), any(), any())).thenReturn("prov-concurrent");

        String key = "race-" + UUID.randomUUID();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CopyOnWriteArrayList<Message> results = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Exception> errors = new CopyOnWriteArrayList<>();

        Future<?> f1 = executor.submit(() -> {
            try {
                startGate.await();
                TenantContext.set(tenantId);
                results.add(outboundService.sendReply(tenantId, conversationId, "Race!", key));
            } catch (Exception e) {
                errors.add(e);
            } finally {
                TenantContext.clear();
            }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                startGate.await();
                TenantContext.set(tenantId);
                results.add(outboundService.sendReply(tenantId, conversationId, "Race!", key));
            } catch (Exception e) {
                errors.add(e);
            } finally {
                TenantContext.clear();
            }
        });

        startGate.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one succeeds, the other gets 409 CONFLICT
        assertThat(results).hasSize(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) errors.get(0)).getStatusCode().value()).isEqualTo(409);

        // Exactly one Unipile send
        verify(unipileClient, times(1)).sendMessage(any(), any(), any());

        // One outbound row, one thread message
        TenantContext.set(tenantId);
        try {
            assertThat(outboundRepo.count()).isEqualTo(1);
            assertThat(messageRepo.findByConversationIdOrderByProviderTimestamp(conversationId)).hasSize(1);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Unipile failure: outbound_messages row persists with status='failed' + error.
     * No phantom thread message. Caller gets 502.
     */
    @Test
    void sendFailure_persistsFailedRecord_noThreadMessage() {
        when(unipileClient.sendMessage(any(), any(), any()))
                .thenThrow(new RuntimeException("Unipile timeout"));

        String key = "fail-" + UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() ->
                    outboundService.sendReply(tenantId, conversationId, "Hello!", key))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(502));

            // Failed outbound record persisted (not rolled back)
            OutboundMessage failed = outboundRepo.findByIdempotencyKey(key).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(OutboundMessage.STATUS_FAILED);
            assertThat(failed.getError()).contains("Unipile timeout");

            // No thread message created
            List<Message> thread = messageRepo.findByConversationIdOrderByProviderTimestamp(conversationId);
            assertThat(thread).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Retry after failure: same idempotency key, Unipile now succeeds.
     * The failed record is deleted and replaced with a successful one.
     * Exactly one Unipile send on the retry (the original failure doesn't count).
     */
    @Test
    void retryAfterFailure_succeedsWithSameKey() {
        when(unipileClient.sendMessage(any(), any(), any()))
                .thenThrow(new RuntimeException("Unipile timeout"))  // first call fails
                .thenReturn("prov-retry-1");                          // second call succeeds

        String key = "retry-" + UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            // First attempt fails
            assertThatThrownBy(() ->
                    outboundService.sendReply(tenantId, conversationId, "Hello!", key))
                    .isInstanceOf(ResponseStatusException.class);

            // Retry with same key succeeds
            Message msg = outboundService.sendReply(tenantId, conversationId, "Hello!", key);
            assertThat(msg.getDirection()).isEqualTo("outbound");
            assertThat(msg.getAuthor()).isEqualTo("human");

            // Outbound record is now 'sent' (the failed one was replaced)
            OutboundMessage outbound = outboundRepo.findByIdempotencyKey(key).orElseThrow();
            assertThat(outbound.getStatus()).isEqualTo(OutboundMessage.STATUS_SENT);
            assertThat(outbound.getProviderMessageId()).isEqualTo("prov-retry-1");

            // Exactly one thread message
            List<Message> thread = messageRepo.findByConversationIdOrderByProviderTimestamp(conversationId);
            assertThat(thread).hasSize(1);
        } finally {
            TenantContext.clear();
        }
    }
}
