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
@Table(name = "contacts")
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "channel_type", nullable = false)
    private String channelType;

    @Column(name = "external_identity", nullable = false)
    private String externalIdentity;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Contact() {}

    public Contact(UUID tenantId, String channelType, String externalIdentity) {
        this.tenantId = tenantId;
        this.channelType = channelType;
        this.externalIdentity = externalIdentity;
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }

    public String getChannelType() { return channelType; }

    public String getExternalIdentity() { return externalIdentity; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Instant getCreatedAt() { return createdAt; }
}
