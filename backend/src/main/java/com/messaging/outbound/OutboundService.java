package com.messaging.outbound;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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

import jakarta.persistence.EntityManager;

/**
 * Outbound reply service with three-phase design:
 *
 * Phase 1 (REQUIRES_NEW): INSERT outbound_messages with ON CONFLICT DO NOTHING.
 *         Committed immediately so the idempotency guard is durable before the
 *         external call. Also handles deduplication at the DB level (no race window).
 *
 * Phase 2: Call Unipile send (external HTTP - outside any DB transaction).
 *
 * Phase 3 (REQUIRES_NEW): On success: mark sent + write thread message.
 *         On failure: mark failed + capture error. Either way, this commits
 *         independently so the failure record is never rolled back.
 */
@Service
public class OutboundService {

    private static final Logger log = LoggerFactory.getLogger(OutboundService.class);

    private final OutboundMessageRepository outboundRepo;
    private final ConversationRepository conversationRepo;
    private final ContactRepository contactRepo;
    private final ChannelRepository channelRepo;
    private final MessageRepository messageRepo;
    private final EntityManager entityManager;
    private final UnipileClient unipileClient;

    private final TransactionTemplate requiresNewTx;

    public OutboundService(OutboundMessageRepository outboundRepo,
                           ConversationRepository conversationRepo,
                           ContactRepository contactRepo,
                           ChannelRepository channelRepo,
                           MessageRepository messageRepo,
                           EntityManager entityManager,
                           Optional<UnipileClient> unipileClient,
                           PlatformTransactionManager txManager) {
        this.outboundRepo = outboundRepo;
        this.conversationRepo = conversationRepo;
        this.contactRepo = contactRepo;
        this.channelRepo = channelRepo;
        this.messageRepo = messageRepo;
        this.entityManager = entityManager;
        this.unipileClient = unipileClient.orElse(null);

        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Send a reply. NOT @Transactional - each phase manages its own transaction
     * so the Unipile HTTP call is never inside a held DB connection and failures
     * are persisted independently.
     */
    public Message sendReply(UUID tenantId, UUID conversationId, String body, String idempotencyKey) {
        // --- Phase 1: idempotent insert (REQUIRES_NEW, committed before external call) ---
        record InsertResult(boolean inserted, UUID outboundId, String accountId, String recipient) {}

        InsertResult phase1 = requiresNewTx.execute(status -> {
            // Idempotency: use native INSERT ON CONFLICT to eliminate the race window.
            int rowsInserted = entityManager.createNativeQuery("""
                INSERT INTO outbound_messages (tenant_id, conversation_id, body, idempotency_key, status)
                VALUES (:tenantId, :conversationId, :body, :idempotencyKey, 'queued')
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                    .setParameter("tenantId", tenantId)
                    .setParameter("conversationId", conversationId)
                    .setParameter("body", body)
                    .setParameter("idempotencyKey", idempotencyKey)
                    .executeUpdate();

            if (rowsInserted == 0) {
                // Key already exists - this is a duplicate or retry
                return new InsertResult(false, null, null, null);
            }

            // Fetch the ID we just inserted (Postgres assigned it)
            @SuppressWarnings("unchecked")
            UUID outboundId = (UUID) entityManager.createNativeQuery(
                    "SELECT id FROM outbound_messages WHERE idempotency_key = :key", UUID.class)
                    .setParameter("key", idempotencyKey)
                    .getSingleResult();

            // Resolve conversation, channel, contact inside this tx
            Conversation conversation = conversationRepo.findById(conversationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
            if (!conversation.getTenantId().equals(tenantId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
            }

            Channel channel = channelRepo.findById(conversation.getChannelId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No channel for conversation"));
            Contact contact = contactRepo.findById(conversation.getContactId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No contact for conversation"));

            return new InsertResult(true, outboundId, channel.getExternalAccountId(), contact.getExternalIdentity());
        });

        if (!phase1.inserted()) {
            // Duplicate key - find the existing outbound record
            OutboundMessage existing = outboundRepo.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate send"));

            if (OutboundMessage.STATUS_FAILED.equals(existing.getStatus())) {
                // Failed send - delete the old record and retry from scratch
                requiresNewTx.execute(status -> {
                    outboundRepo.deleteById(existing.getId());
                    return null;
                });
                return sendReply(tenantId, conversationId, body, idempotencyKey);
            }

            if (OutboundMessage.STATUS_QUEUED.equals(existing.getStatus())) {
                // Another request is in flight (Phase 2/3 not done yet) - safe to reject
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Send already in progress");
            }

            // Already sent - return the thread message
            return messageRepo.findByConversationIdOrderByProviderTimestamp(conversationId)
                    .stream()
                    .filter(m -> "outbound".equals(m.getDirection()) && body.equals(m.getBody()))
                    .reduce((a, b) -> b)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate send"));
        }

        // --- Phase 2: call Unipile (no DB transaction held) ---
        Instant now = Instant.now();
        String providerMessageId;
        try {
            if (unipileClient == null) {
                throw new RuntimeException("Unipile client not configured");
            }
            providerMessageId = unipileClient.sendMessage(phase1.accountId(), phase1.recipient(), body);
        } catch (Exception e) {
            log.error("Failed to send via Unipile: conversationId={}, idempotencyKey={}",
                    conversationId, idempotencyKey, e);

            // --- Phase 3a: record failure (REQUIRES_NEW, survives any outer rollback) ---
            requiresNewTx.execute(status -> {
                OutboundMessage outbound = outboundRepo.findById(phase1.outboundId()).orElseThrow();
                outbound.setStatus(OutboundMessage.STATUS_FAILED);
                outbound.setError(e.getMessage());
                outboundRepo.save(outbound);
                return null;
            });

            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to send message: " + e.getMessage());
        }

        // --- Phase 3b: record success + write thread message (REQUIRES_NEW) ---
        return requiresNewTx.execute(status -> {
            OutboundMessage outbound = outboundRepo.findById(phase1.outboundId()).orElseThrow();
            outbound.setProviderMessageId(providerMessageId);
            outbound.setStatus(OutboundMessage.STATUS_SENT);
            outbound.setSentAt(now);
            outboundRepo.save(outbound);

            Message message = new Message(
                    tenantId, conversationId, "outbound", "human",
                    body, providerMessageId, now
            );
            message.setStatus("sent");
            messageRepo.save(message);

            Conversation conversation = conversationRepo.findById(conversationId).orElseThrow();
            conversation.setLastMessageAt(now);
            conversationRepo.save(conversation);

            return message;
        });
    }
}
