package com.messaging.ingestion;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * In-process implementation: publishes a Spring application event
 * after the ledger write commits. The listener triggers the worker.
 */
@Component
public class InProcessEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventPublisher.class);

    private final ApplicationEventPublisher springPublisher;

    public InProcessEventPublisher(ApplicationEventPublisher springPublisher) {
        this.springPublisher = springPublisher;
    }

    @Override
    public void publish(UUID eventId) {
        log.debug("Publishing in-process event for eventId={}", eventId);
        springPublisher.publishEvent(new InboundEventReceived(eventId));
    }

    public record InboundEventReceived(UUID eventId) {}
}
