package com.messaging.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound message webhook from Unipile.
 * Thin: verify -> ledger insert (dedupe) -> 200 -> publish(eventId).
 * No processing inline.
 */
@RestController
public class MessageWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MessageWebhookController.class);

    private final String webhookSecret;
    private final InboundEventService inboundEventService;
    private final EventPublisher eventPublisher;

    public MessageWebhookController(@Value("${unipile.webhook-secret}") String webhookSecret,
                                     InboundEventService inboundEventService,
                                     EventPublisher eventPublisher) {
        this.webhookSecret = webhookSecret;
        this.inboundEventService = inboundEventService;
        this.eventPublisher = eventPublisher;
    }

    @PostMapping("/api/unipile/message")
    public ResponseEntity<Void> inboundMessage(@RequestParam("secret") String secret,
                                                @RequestBody String rawPayload) {
        if (!constantTimeEquals(webhookSecret, secret)) {
            log.warn("Rejected message webhook: invalid secret");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String dedupeKey = extractDedupeKey(rawPayload);
        if (dedupeKey == null) {
            log.warn("Rejected message webhook: no dedupe key in payload");
            return ResponseEntity.badRequest().build();
        }

        UUID eventId = inboundEventService.recordEvent(rawPayload, dedupeKey, "message");

        if (eventId != null) {
            eventPublisher.publish(eventId);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Extract a dedupe key from the Unipile webhook payload.
     * Uses the message ID from the payload as the dedupe key.
     */
    private String extractDedupeKey(String rawPayload) {
        // Unipile message webhooks include an "id" field at the top level.
        // Use a lightweight parse - find "id" value without full Jackson deserialization
        // to keep the webhook as thin as possible.
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var tree = mapper.readTree(rawPayload);
            var idNode = tree.get("id");
            if (idNode != null && !idNode.isNull()) {
                return idNode.asText();
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse webhook payload for dedupe key", e);
            return null;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
