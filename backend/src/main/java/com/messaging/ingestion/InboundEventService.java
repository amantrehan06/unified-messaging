package com.messaging.ingestion;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/**
 * Writes inbound events to the ledger (dedupe via ON CONFLICT).
 * Returns the event ID if a new row was inserted, empty if duplicate.
 */
@Service
public class InboundEventService {

    private static final Logger log = LoggerFactory.getLogger(InboundEventService.class);

    private final EntityManager entityManager;

    public InboundEventService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Insert an inbound event into the ledger. Idempotent: duplicate dedupe_key is a no-op.
     * Returns the generated event ID, or null if the row already existed.
     */
    @Transactional
    public UUID recordEvent(String rawPayload, String dedupeKey, String eventType) {
        Object result = entityManager.createNativeQuery("""
            INSERT INTO inbound_events (raw_payload, dedupe_key, event_type)
            VALUES (cast(:payload as jsonb), :dedupeKey, :eventType)
            ON CONFLICT (dedupe_key) DO NOTHING
            RETURNING id
            """)
                .setParameter("payload", rawPayload)
                .setParameter("dedupeKey", dedupeKey)
                .setParameter("eventType", eventType)
                .getResultStream()
                .findFirst()
                .orElse(null);

        if (result == null) {
            log.debug("Duplicate event ignored: dedupeKey={}", dedupeKey);
            return null;
        }

        UUID eventId = (UUID) result;
        log.info("Ledger row created: eventId={}, dedupeKey={}", eventId, dedupeKey);
        return eventId;
    }
}
