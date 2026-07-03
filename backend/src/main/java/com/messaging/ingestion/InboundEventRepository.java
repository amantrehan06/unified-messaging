package com.messaging.ingestion;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface InboundEventRepository extends JpaRepository<InboundEvent, UUID> {

    Optional<InboundEvent> findByDedupeKey(String dedupeKey);

    @Modifying
    @Query(value = """
        UPDATE inbound_events
        SET processing_status = 'processing', claimed_at = now()
        WHERE id = :id AND processing_status = 'received'
        """, nativeQuery = true)
    int claimEvent(UUID id);

    @Modifying
    @Query(value = """
        UPDATE inbound_events
        SET processing_status = CASE WHEN retry_count >= :maxRetries THEN 'dead' ELSE 'received' END,
            retry_count = retry_count + 1
        WHERE processing_status = 'processing'
          AND claimed_at < now() - cast(:timeoutSeconds || ' seconds' as interval)
        """, nativeQuery = true)
    int reapStuckEvents(int maxRetries, int timeoutSeconds);
}
