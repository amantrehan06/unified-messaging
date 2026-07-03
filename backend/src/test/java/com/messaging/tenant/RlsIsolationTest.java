package com.messaging.tenant;

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
 * Proves RLS isolation at the database level using raw JDBC.
 * No Spring context - this tests Postgres behavior directly.
 */
@Testcontainers
class RlsIsolationTest {

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
            // Run Flyway migrations as the owner
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .load()
                    .migrate();

            // Create the restricted app_user role
            try (Statement stmt = owner.createStatement()) {
                stmt.execute("CREATE ROLE " + APP_ROLE + " WITH LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
                stmt.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
                stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_ROLE);
            }

            // Seed two tenants
            tenantA = UUID.randomUUID();
            tenantB = UUID.randomUUID();
            try (Statement stmt = owner.createStatement()) {
                stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + tenantA + "', 'Tenant A')");
                stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + tenantB + "', 'Tenant B')");
                // Insert notes for both tenants
                stmt.execute("INSERT INTO tenant_notes (tenant_id, body) VALUES ('" + tenantA + "', 'Note A1')");
                stmt.execute("INSERT INTO tenant_notes (tenant_id, body) VALUES ('" + tenantA + "', 'Note A2')");
                stmt.execute("INSERT INTO tenant_notes (tenant_id, body) VALUES ('" + tenantB + "', 'Note B1')");
            }
        }
    }

    @Test
    void positive_seeOwnRows() throws Exception {
        try (Connection conn = appUserConnection()) {
            setTenant(conn, tenantA);
            List<String> bodies = queryNotes(conn);
            assertThat(bodies).containsExactlyInAnyOrder("Note A1", "Note A2");
        }
    }

    @Test
    void crossTenant_cannotReadOtherTenantRows() throws Exception {
        try (Connection conn = appUserConnection()) {
            setTenant(conn, tenantA);
            List<String> bodies = queryNotes(conn);
            assertThat(bodies).doesNotContain("Note B1");
        }
    }

    @Test
    void adversarial_noWhereClauseStillFiltered() throws Exception {
        // Query has no WHERE tenant_id - RLS must still filter
        try (Connection conn = appUserConnection()) {
            setTenant(conn, tenantB);
            List<String> bodies = queryNotes(conn);
            assertThat(bodies).containsExactly("Note B1");
            assertThat(bodies).doesNotContain("Note A1", "Note A2");
        }
    }

    @Test
    void noContext_noRowsLeak() throws Exception {
        // app.current_tenant is not set - should see zero rows (fail closed)
        try (Connection conn = appUserConnection()) {
            List<String> bodies = queryNotes(conn);
            assertThat(bodies).isEmpty();
        }
    }

    @Test
    void roleDoesNotBypassRls() throws Exception {
        // Verify app_user is not a superuser and does not own the table
        try (Connection conn = appUserConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = '" + APP_ROLE + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("rolsuper")).isFalse();
                assertThat(rs.getBoolean("rolbypassrls")).isFalse();
            }
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
            stmt.execute("SET app.current_tenant = '" + tenantId + "'");
        }
    }

    private static List<String> queryNotes(Connection conn) throws Exception {
        List<String> result = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT body FROM tenant_notes")) {
            while (rs.next()) {
                result.add(rs.getString("body"));
            }
        }
        return result;
    }
}
