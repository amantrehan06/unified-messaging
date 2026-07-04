package com.messaging.outbound;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for outbound message sending invariants:
 * - Idempotency (double-submit produces one outbound row)
 * - Failure path (status=failed, error stored)
 * - Outbound message appears in thread
 * - RLS isolation on outbound_messages
 */
@Testcontainers
class OutboundPipelineTest {

    private static final String APP_ROLE = "app_user";
    private static final String APP_ROLE_PASSWORD = "test_password";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    private static UUID tenantA;
    private static UUID tenantB;
    private static UUID channelA;
    private static UUID contactA;
    private static UUID conversationA;

    @BeforeAll
    static void setupSchema() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            // Create app_user role if it doesn't exist yet
            ResultSet rs = stmt.executeQuery(
                    "SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "'");
            if (!rs.next()) {
                stmt.execute("CREATE ROLE " + APP_ROLE + " WITH LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
                stmt.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
                stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_ROLE);
            }

            tenantA = UUID.randomUUID();
            tenantB = UUID.randomUUID();
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + tenantA + "', 'Tenant A')");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + tenantB + "', 'Tenant B')");

            channelA = UUID.randomUUID();
            stmt.execute("INSERT INTO channels (id, tenant_id, type, status, external_account_id) VALUES " +
                    "('" + channelA + "', '" + tenantA + "', 'whatsapp', 'connected', 'acc-a')");

            contactA = UUID.randomUUID();
            stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity, display_name) VALUES " +
                    "('" + contactA + "', '" + tenantA + "', 'whatsapp', '+1234567890', 'Alice')");

            conversationA = UUID.randomUUID();
            stmt.execute("INSERT INTO conversations (id, tenant_id, contact_id, channel_id, state, last_message_at) VALUES " +
                    "('" + conversationA + "', '" + tenantA + "', '" + contactA + "', '" + channelA + "', 'AI_HANDLING', now())");
        }
    }

    @BeforeEach
    void cleanOutbound() throws Exception {
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("DELETE FROM outbound_messages");
            stmt.execute("DELETE FROM messages");
        }
    }

    @Test
    void idempotency_sameKeyInsertedTwice_oneRow() throws Exception {
        String idempotencyKey = "idem-" + UUID.randomUUID();

        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO outbound_messages (tenant_id, conversation_id, body, idempotency_key, status) " +
                    "VALUES ('" + tenantA + "', '" + conversationA + "', 'Hello!', '" + idempotencyKey + "', 'queued')");

            // Double-submit with same key - ON CONFLICT on unique constraint
            stmt.execute("INSERT INTO outbound_messages (tenant_id, conversation_id, body, idempotency_key, status) " +
                    "VALUES ('" + tenantA + "', '" + conversationA + "', 'Hello!', '" + idempotencyKey + "', 'queued') " +
                    "ON CONFLICT (idempotency_key) DO NOTHING");

            ResultSet rs = stmt.executeQuery(
                    "SELECT count(*) FROM outbound_messages WHERE idempotency_key = '" + idempotencyKey + "'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void failedStatus_errorStored() throws Exception {
        String idempotencyKey = "fail-" + UUID.randomUUID();

        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO outbound_messages (tenant_id, conversation_id, body, idempotency_key, status, error) " +
                    "VALUES ('" + tenantA + "', '" + conversationA + "', 'Hello!', '" + idempotencyKey + "', " +
                    "'failed', 'Unipile timeout')");

            ResultSet rs = stmt.executeQuery(
                    "SELECT status, error FROM outbound_messages WHERE idempotency_key = '" + idempotencyKey + "'");
            rs.next();
            assertThat(rs.getString("status")).isEqualTo("failed");
            assertThat(rs.getString("error")).isEqualTo("Unipile timeout");
        }
    }

    @Test
    void outboundMessageAppearsInThread() throws Exception {
        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            // Simulate: insert inbound message, then outbound reply
            stmt.execute("INSERT INTO messages (tenant_id, conversation_id, direction, author, body, provider_timestamp) " +
                    "VALUES ('" + tenantA + "', '" + conversationA + "', 'inbound', 'contact', 'Hi there', now() - interval '1 minute')");
            stmt.execute("INSERT INTO messages (tenant_id, conversation_id, direction, author, body, provider_message_id, provider_timestamp, status) " +
                    "VALUES ('" + tenantA + "', '" + conversationA + "', 'outbound', 'human', 'Hello back!', 'prov-123', now(), 'sent')");

            // Query thread ordered by provider_timestamp
            List<String> directions = new ArrayList<>();
            List<String> bodies = new ArrayList<>();
            ResultSet rs = stmt.executeQuery(
                    "SELECT direction, body FROM messages WHERE conversation_id = '" + conversationA + "' " +
                    "ORDER BY provider_timestamp");
            while (rs.next()) {
                directions.add(rs.getString("direction"));
                bodies.add(rs.getString("body"));
            }

            assertThat(directions).containsExactly("inbound", "outbound");
            assertThat(bodies).containsExactly("Hi there", "Hello back!");
        }
    }

    @Test
    void rls_outboundMessagesIsolatedCrossTenant() throws Exception {
        UUID contactB = UUID.randomUUID();
        UUID conversationB = UUID.randomUUID();
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity) " +
                    "VALUES ('" + contactB + "', '" + tenantB + "', 'whatsapp', 'rls-outbound-b')");
            stmt.execute("INSERT INTO conversations (id, tenant_id, contact_id, state) " +
                    "VALUES ('" + conversationB + "', '" + tenantB + "', '" + contactB + "', 'AI_HANDLING')");

            // Insert outbound for both tenants
            stmt.execute("INSERT INTO outbound_messages (tenant_id, conversation_id, body, idempotency_key, status) " +
                    "VALUES ('" + tenantA + "', '" + conversationA + "', 'From A', 'rls-a-" + UUID.randomUUID() + "', 'sent')");
            stmt.execute("INSERT INTO outbound_messages (tenant_id, conversation_id, body, idempotency_key, status) " +
                    "VALUES ('" + tenantB + "', '" + conversationB + "', 'From B', 'rls-b-" + UUID.randomUUID() + "', 'sent')");
        }

        // Tenant A should only see their outbound
        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT body FROM outbound_messages")) {
                List<String> bodies = new ArrayList<>();
                while (rs.next()) bodies.add(rs.getString("body"));
                assertThat(bodies).containsExactly("From A");
            }
            conn.rollback();
        }

        // Tenant B should only see their outbound
        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantB);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT body FROM outbound_messages")) {
                List<String> bodies = new ArrayList<>();
                while (rs.next()) bodies.add(rs.getString("body"));
                assertThat(bodies).containsExactly("From B");
            }
            conn.rollback();
        }
    }

    // ---- Helpers ----

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
}
