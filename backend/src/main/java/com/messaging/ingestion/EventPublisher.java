package com.messaging.ingestion;

import java.util.UUID;

/**
 * Seam for event transport. MVP: in-process. Later: Pub/Sub push.
 * The event carries only an eventId - the worker re-reads the row from the ledger.
 */
public interface EventPublisher {

    void publish(UUID eventId);
}
