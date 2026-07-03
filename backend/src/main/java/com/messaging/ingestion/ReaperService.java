package com.messaging.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crash recovery: sweeps for events stuck in 'processing' past a timeout.
 * Resets them to 'received' for re-processing, or 'dead' after max retries.
 */
@Service
public class ReaperService {

    private static final Logger log = LoggerFactory.getLogger(ReaperService.class);

    static final int MAX_RETRIES = 3;
    static final int TIMEOUT_SECONDS = 300; // 5 minutes

    private final InboundEventRepository eventRepository;

    public ReaperService(InboundEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Scheduled(fixedDelay = 120_000) // every 2 minutes
    @Transactional
    public void reap() {
        int reaped = eventRepository.reapStuckEvents(MAX_RETRIES, TIMEOUT_SECONDS);
        if (reaped > 0) {
            log.info("Reaper reset {} stuck events", reaped);
        }
    }
}
