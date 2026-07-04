package com.messaging.outbound;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbound_messages")
public class OutboundMessage {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private String body;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(nullable = false)
    private String status = STATUS_QUEUED;

    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboundMessage() {}

    public OutboundMessage(UUID tenantId, UUID conversationId, String body, String idempotencyKey) {
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.body = body;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }

    public UUID getConversationId() { return conversationId; }

    public String getBody() { return body; }

    public String getIdempotencyKey() { return idempotencyKey; }

    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
