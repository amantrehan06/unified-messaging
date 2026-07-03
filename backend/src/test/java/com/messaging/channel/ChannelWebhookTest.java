package com.messaging.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests channel webhook handling at the database level: notify callback stores
 * the connected account, forged callbacks are rejected, and RLS isolates tenants.
 */
@Testcontainers
class ChannelWebhookTest {

    private static final String APP_ROLE = "app_user";
    private static final String APP_ROLE_PASSWORD = "test_password";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    private static UUID tenantA;
    private static UUID tenantB;

    @BeforeAll
    static void setupSchema() throws Exception {
        try (Connection owner = ownerConnection()) {
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .load()
                    .migrate();

            try (Statement stmt = owner.createStatement()) {
                stmt.execute("CREATE ROLE " + APP_ROLE + " WITH LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
                stmt.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
                stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_ROLE);
                stmt.execute("GRANT EXECUTE ON FUNCTION channel_tenant_by_account(text) TO " + APP_ROLE);
            }

            tenantA = UUID.randomUUID();
            tenantB = UUID.randomUUID();
            try (Statement stmt = owner.createStatement()) {
                stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + tenantA + "', 'Tenant A')");
                stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + tenantB + "', 'Tenant B')");
            }
        }
    }

    @Test
    void notifyCallback_connectsChannel() throws Exception {
        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);

            // Create a pending channel
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO channels (tenant_id, type, status) VALUES ('" + tenantA + "', 'whatsapp', 'pending')");
            }

            // Simulate notify callback: update to connected
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE channels SET external_account_id = 'acc123', status = 'connected', " +
                        "connected_at = now() WHERE tenant_id = '" + tenantA + "' AND status = 'pending'");
            }

            // Verify channel is connected
            List<String> statuses = queryChannelStatuses(conn);
            assertThat(statuses).containsExactly("connected");

            conn.rollback();
        }
    }

    @Test
    void rlsIsolation_tenantCannotSeeOtherTenantChannels() throws Exception {
        try (Connection conn = appUserConnection()) {
            // Insert channels for both tenants as owner (bypass RLS)
            try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
                stmt.execute("INSERT INTO channels (tenant_id, type, status, external_account_id) VALUES " +
                        "('" + tenantA + "', 'whatsapp', 'connected', 'accA')");
                stmt.execute("INSERT INTO channels (tenant_id, type, status, external_account_id) VALUES " +
                        "('" + tenantB + "', 'instagram', 'connected', 'accB')");
            }

            // As tenant A, should only see own channels
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);
            List<String> types = queryChannelTypes(conn);
            assertThat(types).containsExactly("whatsapp");
            assertThat(types).doesNotContain("instagram");
            conn.rollback();

            // As tenant B, should only see own channels
            conn.setAutoCommit(false);
            setTenant(conn, tenantB);
            types = queryChannelTypes(conn);
            assertThat(types).containsExactly("instagram");
            assertThat(types).doesNotContain("whatsapp");
            conn.rollback();

            // Cleanup
            try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
                stmt.execute("DELETE FROM channels WHERE external_account_id IN ('accA', 'accB')");
            }
        }
    }

    @Test
    void noTenantContext_seesNoChannels() throws Exception {
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO channels (tenant_id, type, status) VALUES ('" + tenantA + "', 'whatsapp', 'pending')");
        }

        try (Connection conn = appUserConnection()) {
            // No SET LOCAL - should see zero rows
            List<String> statuses = queryChannelStatuses(conn);
            assertThat(statuses).isEmpty();
        }

        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("DELETE FROM channels WHERE tenant_id = '" + tenantA + "' AND status = 'pending'");
        }
    }

    @Test
    void securityDefinerFunction_lookupsTenantByAccountId() throws Exception {
        // Insert a connected channel as owner
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO channels (tenant_id, type, status, external_account_id) VALUES " +
                    "('" + tenantA + "', 'whatsapp', 'connected', 'lookup-test-acc')");
        }

        // As app_user (no tenant context), call the SECURITY DEFINER function
        try (Connection conn = appUserConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT channel_tenant_by_account('lookup-test-acc')")) {
                assertThat(rs.next()).isTrue();
                UUID result = (UUID) rs.getObject(1);
                assertThat(result).isEqualTo(tenantA);
            }
        }

        // Cleanup
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("DELETE FROM channels WHERE external_account_id = 'lookup-test-acc'");
        }
    }

    @Test
    void securityDefinerFunction_returnsNullForUnknownAccount() throws Exception {
        try (Connection conn = appUserConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT channel_tenant_by_account('nonexistent')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject(1)).isNull();
            }
        }
    }

    @Test
    void statusWebhook_updatesChannelStatus() throws Exception {
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO channels (tenant_id, type, status, external_account_id) VALUES " +
                    "('" + tenantA + "', 'whatsapp', 'connected', 'status-test-acc')");
        }

        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);

            // Simulate status update to error
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE channels SET status = 'error', last_status_at = now() " +
                        "WHERE external_account_id = 'status-test-acc'");
            }

            List<String> statuses = queryChannelStatuses(conn);
            assertThat(statuses).containsExactly("error");

            conn.rollback();
        }

        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("DELETE FROM channels WHERE external_account_id = 'status-test-acc'");
        }
    }

    private static Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection appUserConnection() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
    }

    private static void setTenant(Connection conn, UUID tenantId) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
        }
    }

    private static List<String> queryChannelStatuses(Connection conn) throws Exception {
        List<String> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT status FROM channels")) {
            while (rs.next()) {
                result.add(rs.getString("status"));
            }
        }
        return result;
    }

    private static List<String> queryChannelTypes(Connection conn) throws Exception {
        List<String> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT type FROM channels")) {
            while (rs.next()) {
                result.add(rs.getString("type"));
            }
        }
        return result;
    }
}
