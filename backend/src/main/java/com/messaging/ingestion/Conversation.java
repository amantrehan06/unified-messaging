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
@Table(name = "conversations")
public class Conversation {

    public static final String STATE_AI_HANDLING = "AI_HANDLING";
    public static final String STATE_WAITING_HUMAN = "WAITING_HUMAN";
    public static final String STATE_WITH_HUMAN = "WITH_HUMAN";
    public static final String STATE_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "channel_id")
    private UUID channelId;

    @Column(nullable = false)
    private String state = STATE_AI_HANDLING;

    @Column(name = "state_reason")
    private String stateReason;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Conversation() {}

    public Conversation(UUID tenantId, UUID contactId, UUID channelId) {
        this.tenantId = tenantId;
        this.contactId = contactId;
        this.channelId = channelId;
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }

    public UUID getContactId() { return contactId; }

    public UUID getChannelId() { return channelId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }

    public Instant getCreatedAt() { return createdAt; }
}
