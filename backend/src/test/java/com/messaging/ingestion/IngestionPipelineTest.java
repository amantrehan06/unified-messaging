package com.messaging.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrency test suite for the ingestion pipeline.
 * Raw JDBC against Testcontainers Postgres - no Spring context.
 * Tests the critical invariants: dedupe, claim race, advisory lock serialization,
 * reaper behavior, ledger-first, ordering, reopen, and RLS.
 */
@Testcontainers
class IngestionPipelineTest {

    private static final String APP_ROLE = "app_user";
    private static final String APP_ROLE_PASSWORD = "test_password";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17");

    private static UUID tenantA;
    private static UUID tenantB;
    private static UUID channelA;

    @BeforeAll
    static void setupSchema() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("CREATE ROLE " + APP_ROLE + " WITH LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
            stmt.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + APP_ROLE);
            stmt.execute("GRANT EXECUTE ON FUNCTION channel_tenant_by_account(text) TO " + APP_ROLE);

            tenantA = UUID.randomUUID();
            tenantB = UUID.randomUUID();
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + tenantA + "', 'Tenant A')");
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + tenantB + "', 'Tenant B')");

            channelA = UUID.randomUUID();
            stmt.execute("INSERT INTO channels (id, tenant_id, type, status, external_account_id) VALUES " +
                    "('" + channelA + "', '" + tenantA + "', 'whatsapp', 'connected', 'acc-a')");
        }
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("DELETE FROM messages");
            stmt.execute("DELETE FROM conversations");
            stmt.execute("DELETE FROM contacts");
            stmt.execute("DELETE FROM inbound_events");
        }
    }

    // ---- Test 1: Dedupe ----

    @Test
    void dedupe_sameDedupKeyInsertedTwice_oneRow() throws Exception {
        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO inbound_events (raw_payload, dedupe_key) VALUES ('{\"id\":\"msg1\"}'::jsonb, 'msg1')");

            // Second insert with same dedupe_key - ON CONFLICT DO NOTHING
            stmt.execute("INSERT INTO inbound_events (raw_payload, dedupe_key) " +
                    "VALUES ('{\"id\":\"msg1\"}'::jsonb, 'msg1') ON CONFLICT (dedupe_key) DO NOTHING");

            ResultSet rs = stmt.executeQuery("SELECT count(*) FROM inbound_events WHERE dedupe_key = 'msg1'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    // ---- Test 2: Claim race ----

    @Test
    void claimRace_concurrentClaims_exactlyOneWins() throws Exception {
        UUID eventId;
        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO inbound_events (raw_payload, dedupe_key) VALUES ('{}'::jsonb, 'race-test')");
            ResultSet rs = stmt.executeQuery("SELECT id FROM inbound_events WHERE dedupe_key = 'race-test'");
            rs.next();
            eventId = (UUID) rs.getObject("id");
        }

        int threadCount = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        CopyOnWriteArrayList<Boolean> results = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                    try (Connection conn = ownerConnection()) {
                        conn.setAutoCommit(false);
                        try (Statement stmt = conn.createStatement()) {
                            int updated = stmt.executeUpdate(
                                    "UPDATE inbound_events SET processing_status = 'processing', claimed_at = now() " +
                                    "WHERE id = '" + eventId + "' AND processing_status = 'received'");
                            results.add(updated > 0);
                        }
                        conn.commit();
                    }
                } catch (Exception e) {
                    results.add(false);
                }
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        long winners = results.stream().filter(b -> b).count();
        assertThat(winners).isEqualTo(1);
    }

    // ---- Test 3: Advisory lock serialization ----

    @Test
    void advisoryLock_sameConversation_serialized() throws Exception {
        // Two threads acquire advisory lock on the same key - they must serialize
        String conversationKey = tenantA + ":whatsapp:sender1";
        CopyOnWriteArrayList<String> executionOrder = new CopyOnWriteArrayList<>();
        CountDownLatch thread1InLock = new CountDownLatch(1);
        CountDownLatch thread1Done = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            try (Connection conn = ownerConnection()) {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT pg_advisory_xact_lock(hashtext('" + conversationKey + "'))");
                    executionOrder.add("t1-acquired");
                    thread1InLock.countDown();
                    // Hold the lock for a bit
                    Thread.sleep(200);
                    executionOrder.add("t1-releasing");
                }
                conn.commit();
                thread1Done.countDown();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                // Wait for t1 to acquire the lock
                thread1InLock.await();
                try (Connection conn = ownerConnection()) {
                    conn.setAutoCommit(false);
                    try (Statement stmt = conn.createStatement()) {
                        // This should block until t1 releases
                        stmt.execute("SELECT pg_advisory_xact_lock(hashtext('" + conversationKey + "'))");
                        executionOrder.add("t2-acquired");
                    }
                    conn.commit();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);

        // t2 must acquire after t1 releases
        assertThat(executionOrder).containsExactly("t1-acquired", "t1-releasing", "t2-acquired");
    }

    @Test
    void advisoryLock_differentConversations_parallel() throws Exception {
        String key1 = tenantA + ":whatsapp:sender1";
        String key2 = tenantA + ":whatsapp:sender2";
        CountDownLatch bothAcquired = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            try (Connection conn = ownerConnection()) {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT pg_advisory_xact_lock(hashtext('" + key1 + "'))");
                    bothAcquired.countDown();
                    proceed.await(); // Hold lock until test verifies both acquired
                }
                conn.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread t2 = new Thread(() -> {
            try (Connection conn = ownerConnection()) {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT pg_advisory_xact_lock(hashtext('" + key2 + "'))");
                    bothAcquired.countDown();
                    proceed.await();
                }
                conn.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();

        // Both should acquire within a reasonable time (they're on different keys)
        boolean bothGotLocks = bothAcquired.await(5, TimeUnit.SECONDS);
        proceed.countDown();
        t1.join(5000);
        t2.join(5000);

        assertThat(bothGotLocks).isTrue();
    }

    // ---- Test 4: Reaper - reset ----

    @Test
    void reaper_stuckProcessing_resetsToReceived() throws Exception {
        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            // Insert a processing event with claimed_at far in the past
            stmt.execute("INSERT INTO inbound_events (raw_payload, dedupe_key, processing_status, claimed_at) " +
                    "VALUES ('{}'::jsonb, 'reaper-reset', 'processing', now() - interval '10 minutes')");

            // Run reaper query
            int reaped = stmt.executeUpdate(
                    "UPDATE inbound_events " +
                    "SET processing_status = CASE WHEN retry_count >= 3 THEN 'dead' ELSE 'received' END, " +
                    "    retry_count = retry_count + 1 " +
                    "WHERE processing_status = 'processing' AND claimed_at < now() - interval '5 minutes'");

            assertThat(reaped).isEqualTo(1);

            ResultSet rs = stmt.executeQuery(
                    "SELECT processing_status, retry_count FROM inbound_events WHERE dedupe_key = 'reaper-reset'");
            rs.next();
            assertThat(rs.getString("processing_status")).isEqualTo("received");
            assertThat(rs.getInt("retry_count")).isEqualTo(1);
        }
    }

    // ---- Test 5: Reaper - no steal ----

    @Test
    void reaper_recentProcessing_notTouched() throws Exception {
        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            // Insert a processing event with recent claimed_at
            stmt.execute("INSERT INTO inbound_events (raw_payload, dedupe_key, processing_status, claimed_at) " +
                    "VALUES ('{}'::jsonb, 'reaper-nosteal', 'processing', now())");

            // Run reaper query - timeout is 5 minutes, claimed_at is now()
            int reaped = stmt.executeUpdate(
                    "UPDATE inbound_events " +
                    "SET processing_status = CASE WHEN retry_count >= 3 THEN 'dead' ELSE 'received' END, " +
                    "    retry_count = retry_count + 1 " +
                    "WHERE processing_status = 'processing' AND claimed_at < now() - interval '5 minutes'");

            assertThat(reaped).isEqualTo(0);

            ResultSet rs = stmt.executeQuery(
                    "SELECT processing_status FROM inbound_events WHERE dedupe_key = 'reaper-nosteal'");
            rs.next();
            assertThat(rs.getString("processing_status")).isEqualTo("processing");
        }
    }

    // ---- Test 6: Reaper - dead ----

    @Test
    void reaper_maxRetriesExceeded_markedDead() throws Exception {
        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            // Insert a processing event with retry_count already at max
            stmt.execute("INSERT INTO inbound_events (raw_payload, dedupe_key, processing_status, claimed_at, retry_count) " +
                    "VALUES ('{}'::jsonb, 'reaper-dead', 'processing', now() - interval '10 minutes', 3)");

            int reaped = stmt.executeUpdate(
                    "UPDATE inbound_events " +
                    "SET processing_status = CASE WHEN retry_count >= 3 THEN 'dead' ELSE 'received' END, " +
                    "    retry_count = retry_count + 1 " +
                    "WHERE processing_status = 'processing' AND claimed_at < now() - interval '5 minutes'");

            assertThat(reaped).isEqualTo(1);

            ResultSet rs = stmt.executeQuery(
                    "SELECT processing_status, retry_count FROM inbound_events WHERE dedupe_key = 'reaper-dead'");
            rs.next();
            assertThat(rs.getString("processing_status")).isEqualTo("dead");
            assertThat(rs.getInt("retry_count")).isEqualTo(4);
        }
    }

    // ---- Test 7: Ledger-first ----

    @Test
    void ledgerFirst_eventExistsBeforeConversation() throws Exception {
        // Insert event row (ledger write happens before any processing)
        UUID eventId;
        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO inbound_events (raw_payload, dedupe_key) VALUES ('{}'::jsonb, 'ledger-first')");
            ResultSet rs = stmt.executeQuery("SELECT id FROM inbound_events WHERE dedupe_key = 'ledger-first'");
            rs.next();
            eventId = (UUID) rs.getObject("id");
        }

        // Verify event exists with status 'received', and no conversations/messages yet
        try (Connection conn = ownerConnection(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT processing_status FROM inbound_events WHERE id = '" + eventId + "'");
            rs.next();
            assertThat(rs.getString("processing_status")).isEqualTo("received");

            rs = stmt.executeQuery("SELECT count(*) FROM conversations");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);

            rs = stmt.executeQuery("SELECT count(*) FROM messages");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }
    }

    // ---- Test 8: Ordering ----

    @Test
    void ordering_messagesByProviderTimestamp() throws Exception {
        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);

            // Create contact and conversation
            UUID contactId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity) " +
                        "VALUES ('" + contactId + "', '" + tenantA + "', 'whatsapp', 'order-test-sender')");
                stmt.execute("INSERT INTO conversations (id, tenant_id, contact_id, channel_id) " +
                        "VALUES ('" + conversationId + "', '" + tenantA + "', '" + contactId + "', '" + channelA + "')");
            }

            // Insert messages out of created_at order but with specific provider_timestamps
            Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
            Instant t2 = Instant.parse("2026-01-01T10:00:01Z");
            Instant t3 = Instant.parse("2026-01-01T10:00:02Z");

            try (Statement stmt = conn.createStatement()) {
                // Insert in reverse order
                stmt.execute("INSERT INTO messages (tenant_id, conversation_id, direction, author, body, provider_timestamp) " +
                        "VALUES ('" + tenantA + "', '" + conversationId + "', 'inbound', 'contact', 'third', '" + Timestamp.from(t3) + "')");
                stmt.execute("INSERT INTO messages (tenant_id, conversation_id, direction, author, body, provider_timestamp) " +
                        "VALUES ('" + tenantA + "', '" + conversationId + "', 'inbound', 'contact', 'first', '" + Timestamp.from(t1) + "')");
                stmt.execute("INSERT INTO messages (tenant_id, conversation_id, direction, author, body, provider_timestamp) " +
                        "VALUES ('" + tenantA + "', '" + conversationId + "', 'inbound', 'contact', 'second', '" + Timestamp.from(t2) + "')");
            }

            // Query ordered by provider_timestamp
            List<String> bodies = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT body FROM messages WHERE conversation_id = '" + conversationId + "' " +
                         "ORDER BY provider_timestamp")) {
                while (rs.next()) {
                    bodies.add(rs.getString("body"));
                }
            }

            assertThat(bodies).containsExactly("first", "second", "third");
            conn.rollback();
        }
    }

    // ---- Test 9: Reopen ----

    @Test
    void reopen_closedConversation_reopensToAiHandling() throws Exception {
        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);

            UUID contactId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity) " +
                        "VALUES ('" + contactId + "', '" + tenantA + "', 'whatsapp', 'reopen-sender')");
                stmt.execute("INSERT INTO conversations (id, tenant_id, contact_id, channel_id, state) " +
                        "VALUES ('" + conversationId + "', '" + tenantA + "', '" + contactId + "', '" + channelA + "', 'CLOSED')");
            }

            // Simulate reopen: inbound on CLOSED -> AI_HANDLING
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(
                        "UPDATE conversations SET state = 'AI_HANDLING', state_reason = 'reopened by inbound message' " +
                        "WHERE id = '" + conversationId + "' AND state = 'CLOSED'");
            }

            // Verify state
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT state, state_reason FROM conversations WHERE id = '" + conversationId + "'")) {
                rs.next();
                assertThat(rs.getString("state")).isEqualTo("AI_HANDLING");
                assertThat(rs.getString("state_reason")).isEqualTo("reopened by inbound message");
            }

            conn.rollback();
        }
    }

    // ---- Test 10: RLS isolation ----

    @Test
    void rls_contactsIsolatedCrossTenant() throws Exception {
        // Insert contacts for both tenants as owner
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO contacts (tenant_id, channel_type, external_identity) " +
                    "VALUES ('" + tenantA + "', 'whatsapp', 'rls-contact-a')");
            stmt.execute("INSERT INTO contacts (tenant_id, channel_type, external_identity) " +
                    "VALUES ('" + tenantB + "', 'whatsapp', 'rls-contact-b')");
        }

        // Tenant A should only see their contact
        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT external_identity FROM contacts")) {
                List<String> identities = new ArrayList<>();
                while (rs.next()) identities.add(rs.getString("external_identity"));
                assertThat(identities).containsExactly("rls-contact-a");
            }
            conn.rollback();
        }

        // Tenant B should only see their contact
        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantB);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT external_identity FROM contacts")) {
                List<String> identities = new ArrayList<>();
                while (rs.next()) identities.add(rs.getString("external_identity"));
                assertThat(identities).containsExactly("rls-contact-b");
            }
            conn.rollback();
        }
    }

    @Test
    void rls_conversationsIsolatedCrossTenant() throws Exception {
        UUID contactA = UUID.randomUUID();
        UUID contactB = UUID.randomUUID();
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity) " +
                    "VALUES ('" + contactA + "', '" + tenantA + "', 'whatsapp', 'rls-convo-a')");
            stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity) " +
                    "VALUES ('" + contactB + "', '" + tenantB + "', 'whatsapp', 'rls-convo-b')");
            stmt.execute("INSERT INTO conversations (tenant_id, contact_id, state) " +
                    "VALUES ('" + tenantA + "', '" + contactA + "', 'AI_HANDLING')");
            stmt.execute("INSERT INTO conversations (tenant_id, contact_id, state) " +
                    "VALUES ('" + tenantB + "', '" + contactB + "', 'AI_HANDLING')");
        }

        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM conversations")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            conn.rollback();
        }
    }

    @Test
    void rls_messagesIsolatedCrossTenant() throws Exception {
        UUID contactA = UUID.randomUUID();
        UUID contactB = UUID.randomUUID();
        UUID convoA = UUID.randomUUID();
        UUID convoB = UUID.randomUUID();
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity) " +
                    "VALUES ('" + contactA + "', '" + tenantA + "', 'whatsapp', 'rls-msg-a')");
            stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity) " +
                    "VALUES ('" + contactB + "', '" + tenantB + "', 'whatsapp', 'rls-msg-b')");
            stmt.execute("INSERT INTO conversations (id, tenant_id, contact_id) " +
                    "VALUES ('" + convoA + "', '" + tenantA + "', '" + contactA + "')");
            stmt.execute("INSERT INTO conversations (id, tenant_id, contact_id) " +
                    "VALUES ('" + convoB + "', '" + tenantB + "', '" + contactB + "')");
            stmt.execute("INSERT INTO messages (tenant_id, conversation_id, direction, author, body, provider_timestamp) " +
                    "VALUES ('" + tenantA + "', '" + convoA + "', 'inbound', 'contact', 'msg-a', now())");
            stmt.execute("INSERT INTO messages (tenant_id, conversation_id, direction, author, body, provider_timestamp) " +
                    "VALUES ('" + tenantB + "', '" + convoB + "', 'inbound', 'contact', 'msg-b', now())");
        }

        try (Connection conn = appUserConnection()) {
            conn.setAutoCommit(false);
            setTenant(conn, tenantA);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT body FROM messages")) {
                List<String> bodies = new ArrayList<>();
                while (rs.next()) bodies.add(rs.getString("body"));
                assertThat(bodies).containsExactly("msg-a");
            }
            conn.rollback();
        }
    }

    @Test
    void rls_noContext_seesNothing() throws Exception {
        // Seed some data as owner
        UUID contactId = UUID.randomUUID();
        UUID convoId = UUID.randomUUID();
        try (Connection owner = ownerConnection(); Statement stmt = owner.createStatement()) {
            stmt.execute("INSERT INTO contacts (id, tenant_id, channel_type, external_identity) " +
                    "VALUES ('" + contactId + "', '" + tenantA + "', 'whatsapp', 'rls-nocontext')");
            stmt.execute("INSERT INTO conversations (id, tenant_id, contact_id) " +
                    "VALUES ('" + convoId + "', '" + tenantA + "', '" + contactId + "')");
            stmt.execute("INSERT INTO messages (tenant_id, conversation_id, direction, author, body, provider_timestamp) " +
                    "VALUES ('" + tenantA + "', '" + convoId + "', 'inbound', 'contact', 'hidden', now())");
        }

        // No SET LOCAL - fail-closed
        try (Connection conn = appUserConnection(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT count(*) FROM contacts");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);

            rs = stmt.executeQuery("SELECT count(*) FROM conversations");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);

            rs = stmt.executeQuery("SELECT count(*) FROM messages");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(0);
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
