package com.messaging.ingestion;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false)
    private String direction;

    @Column(nullable = false)
    private String author;

    private String body;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "provider_timestamp", nullable = false)
    private Instant providerTimestamp;

    @Column(nullable = false)
    private String status = "received";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Message() {}

    public Message(UUID tenantId, UUID conversationId, String direction, String author,
                   String body, String providerMessageId, Instant providerTimestamp) {
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.direction = direction;
        this.author = author;
        this.body = body;
        this.providerMessageId = providerMessageId;
        this.providerTimestamp = providerTimestamp;
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }

    public UUID getConversationId() { return conversationId; }

    public String getDirection() { return direction; }

    public String getAuthor() { return author; }

    public String getBody() { return body; }

    public String getProviderMessageId() { return providerMessageId; }

    public Instant getProviderTimestamp() { return providerTimestamp; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
}
