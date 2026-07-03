package com.messaging.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "auth_provider", nullable = false)
    private String authProvider;

    @Column(name = "external_auth_id", nullable = false)
    private String externalAuthId;

    @Column(nullable = false)
    private String role = "owner";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {}

    public AppUser(Tenant tenant, String email, String authProvider, String externalAuthId) {
        this.tenant = tenant;
        this.email = email;
        this.authProvider = authProvider;
        this.externalAuthId = externalAuthId;
    }

    public UUID getId() { return id; }

    public Tenant getTenant() { return tenant; }

    public String getEmail() { return email; }

    public String getAuthProvider() { return authProvider; }

    public String getExternalAuthId() { return externalAuthId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }
}
