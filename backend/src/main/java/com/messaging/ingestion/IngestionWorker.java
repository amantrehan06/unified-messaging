package com.messaging.ingestion;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messaging.channel.ChannelService;
import com.messaging.tenant.TenantContext;

import jakarta.persistence.EntityManager;

/**
 * Worker: claim -> advisory lock -> resolve contact/convo -> store message -> mark done.
 *
 * Transaction boundaries are explicit via TransactionTemplate (not @Transactional)
 * so they are immune to Spring proxy self-invocation gotchas and clearly visible.
 *
 * Two-phase design:
 *  Phase 1 (claimTx, REQUIRES_NEW): conditional-UPDATE claim commits immediately.
 *          The claim survives JVM crashes so the reaper can find 'processing' rows.
 *  Phase 2 (processTx): advisory lock + resolve + store + mark processed.
 *          Rolls back cleanly on failure - no half-stored data.
 *          The advisory lock is acquired INSIDE this transaction so it holds for the
 *          entire unit and auto-releases on commit/rollback.
 *
 * Defense-in-depth: message insert uses ON CONFLICT DO NOTHING against the unique
 * constraint on (tenant_id, provider_message_id), so redelivery/crash-retry can
 * never create a duplicate message, independent of the claim.
 */
@Service
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final InboundEventRepository eventRepository;
    private final ContactRepository contactRepository;
    private final ConversationRepository conversationRepository;
    private final ChannelService channelService;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final TransactionTemplate claimTx;
    private final TransactionTemplate processTx;

    public IngestionWorker(InboundEventRepository eventRepository,
                           ContactRepository contactRepository,
                           ConversationRepository conversationRepository,
                           MessageRepository messageRepository,
                           ChannelService channelService,
                           EntityManager entityManager,
                           PlatformTransactionManager txManager) {
        this.eventRepository = eventRepository;
        this.contactRepository = contactRepository;
        this.conversationRepository = conversationRepository;
        this.channelService = channelService;
        this.entityManager = entityManager;

        this.claimTx = new TransactionTemplate(txManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.processTx = new TransactionTemplate(txManager);
    }

    @EventListener
    public void onEvent(InProcessEventPublisher.InboundEventReceived event) {
        processEvent(event.eventId());
    }

    /**
     * Main entry point - can be called in-process or via HTTP (future Pub/Sub push).
     */
    public void processEvent(UUID eventId) {
        // Phase 1: claim (separate committed transaction)
        Boolean claimed = claimTx.execute(status ->
                eventRepository.claimEvent(eventId) > 0
        );
        if (!Boolean.TRUE.equals(claimed)) {
            log.debug("Event already claimed or processed: eventId={}", eventId);
            return;
        }

        // Phase 2: process (advisory lock + store + mark - atomic unit)
        try {
            processTx.execute(status -> {
                doProcess(eventId);
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to process eventId={}", eventId, e);
            // Phase 3: mark failed (separate committed transaction)
            try {
                claimTx.execute(status -> {
                    eventRepository.findById(eventId).ifPresent(event -> {
                        event.setProcessingStatus("failed");
                        event.setRetryCount(event.getRetryCount() + 1);
                        event.setLastError(e.getMessage());
                        eventRepository.save(event);
                    });
                    return null;
                });
            } catch (Exception failEx) {
                log.error("Failed to mark event as failed: eventId={}", eventId, failEx);
                // Event stays in 'processing' - reaper will reset it
            }
        }
    }

    /**
     * The processing unit. Called inside processTx so all of this is in one transaction.
     * pg_advisory_xact_lock is acquired here and holds until the transaction commits.
     */
    private void doProcess(UUID eventId) {
        InboundEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Event not found after claim: " + eventId));

        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.getRawPayload());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse event payload: " + eventId, e);
        }

        UUID tenantId = resolveTenant(payload);
        if (tenantId == null) {
            throw new IllegalStateException("Could not resolve tenant for eventId=" + eventId);
        }

        event.setTenantId(tenantId);
        eventRepository.save(event);

        TenantContext.set(tenantId);
        try {
            entityManager.createNativeQuery("SET LOCAL app.current_tenant = '" + tenantId + "'")
                    .executeUpdate();

            // Advisory lock on conversation key (serialize per-conversation, parallel across)
            String senderIdentity = extractSenderIdentity(payload);
            String channelType = extractChannelType(payload);
            String conversationKey = tenantId + ":" + channelType + ":" + senderIdentity;
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))")
                    .setParameter("key", conversationKey)
                    .getSingleResult();

            // Resolve/create contact
            Contact contact = contactRepository
                    .findByTenantIdAndChannelTypeAndExternalIdentity(tenantId, channelType, senderIdentity)
                    .orElseGet(() -> {
                        Contact c = new Contact(tenantId, channelType, senderIdentity);
                        c.setDisplayName(extractDisplayName(payload));
                        return contactRepository.save(c);
                    });

            // Resolve/create/reopen conversation
            UUID channelId = resolveChannelId(payload);
            Conversation conversation = conversationRepository
                    .findByContactIdAndChannelId(contact.getId(), channelId)
                    .map(convo -> {
                        if (Conversation.STATE_CLOSED.equals(convo.getState())) {
                            convo.setState(Conversation.STATE_AI_HANDLING);
                            convo.setStateReason("reopened by inbound message");
                            return conversationRepository.save(convo);
                        }
                        return convo;
                    })
                    .orElseGet(() -> {
                        Conversation c = new Conversation(tenantId, contact.getId(), channelId);
                        return conversationRepository.save(c);
                    });

            // Store message - ON CONFLICT DO NOTHING for idempotency
            Instant providerTimestamp = extractProviderTimestamp(payload);
            String messageBody = extractMessageBody(payload);
            String providerMessageId = extractProviderMessageId(payload);

            entityManager.createNativeQuery("""
                INSERT INTO messages (tenant_id, conversation_id, direction, author, body,
                                      provider_message_id, provider_timestamp)
                VALUES (:tenantId, :convoId, 'inbound', 'contact', :body,
                        :providerMsgId, :providerTs)
                ON CONFLICT (tenant_id, provider_message_id) DO NOTHING
                """)
                    .setParameter("tenantId", tenantId)
                    .setParameter("convoId", conversation.getId())
                    .setParameter("body", messageBody)
                    .setParameter("providerMsgId", providerMessageId)
                    .setParameter("providerTs", providerTimestamp)
                    .executeUpdate();

            // Update conversation.last_message_at
            conversation.setLastMessageAt(providerTimestamp);
            conversationRepository.save(conversation);

            // Mark event processed
            event.setProcessingStatus("processed");
            event.setProcessedAt(Instant.now());
            eventRepository.save(event);

            log.info("Processed eventId={}, conversationId={}", eventId, conversation.getId());
        } finally {
            TenantContext.clear();
        }
    }

    private UUID resolveTenant(JsonNode payload) {
        String accountId = extractAccountId(payload);
        if (accountId == null) return null;
        return channelService.lookupTenantByAccountId(accountId);
    }

    private UUID resolveChannelId(JsonNode payload) {
        String accountId = extractAccountId(payload);
        if (accountId == null) return null;
        var channel = entityManager
                .createNativeQuery("SELECT id FROM channels WHERE external_account_id = :accountId", UUID.class)
                .setParameter("accountId", accountId)
                .getResultStream()
                .findFirst()
                .orElse(null);
        return (UUID) channel;
    }

    private String extractAccountId(JsonNode payload) {
        JsonNode node = payload.get("account_id");
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private String extractSenderIdentity(JsonNode payload) {
        JsonNode from = payload.get("sender_id");
        if (from != null && !from.isNull()) return from.asText();
        from = payload.get("from");
        if (from != null && !from.isNull()) return from.asText();
        return "unknown";
    }

    private String extractChannelType(JsonNode payload) {
        JsonNode node = payload.get("channel_type");
        if (node != null && !node.isNull()) return node.asText();
        node = payload.get("provider_name");
        if (node != null && !node.isNull()) return node.asText();
        return "unknown";
    }

    private String extractDisplayName(JsonNode payload) {
        JsonNode node = payload.get("sender_name");
        return node != null && !node.isNull() ? node.asText() : null;
    }

    private Instant extractProviderTimestamp(JsonNode payload) {
        JsonNode node = payload.get("timestamp");
        if (node != null && !node.isNull()) {
            try {
                return Instant.parse(node.asText());
            } catch (Exception e) {
                try {
                    return Instant.ofEpochMilli(node.asLong());
                } catch (Exception e2) {
                    // fall through
                }
            }
        }
        return Instant.now();
    }

    private String extractMessageBody(JsonNode payload) {
        JsonNode node = payload.get("text");
        if (node != null && !node.isNull()) return node.asText();
        node = payload.get("body");
        if (node != null && !node.isNull()) return node.asText();
        return null;
    }

    private String extractProviderMessageId(JsonNode payload) {
        JsonNode node = payload.get("id");
        return node != null && !node.isNull() ? node.asText() : null;
    }
}
