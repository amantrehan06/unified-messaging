package com.messaging.channel;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "channels")
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String provider = "unipile";

    @Column(name = "external_account_id")
    private String externalAccountId;

    @Column(nullable = false)
    private String status = "pending";

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "last_status_at")
    private Instant lastStatusAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Channel() {}

    public Channel(UUID tenantId, String type) {
        this.tenantId = tenantId;
        this.type = type;
    }

    public UUID getId() { return id; }

    public UUID getTenantId() { return tenantId; }

    public String getType() { return type; }

    public String getProvider() { return provider; }

    public String getExternalAccountId() { return externalAccountId; }
    public void setExternalAccountId(String externalAccountId) { this.externalAccountId = externalAccountId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getConnectedAt() { return connectedAt; }
    public void setConnectedAt(Instant connectedAt) { this.connectedAt = connectedAt; }

    public Instant getLastStatusAt() { return lastStatusAt; }
    public void setLastStatusAt(Instant lastStatusAt) { this.lastStatusAt = lastStatusAt; }

    public Instant getCreatedAt() { return createdAt; }
}
