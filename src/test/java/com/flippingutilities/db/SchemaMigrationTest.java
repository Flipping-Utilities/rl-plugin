package com.flippingutilities.db;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;

import static org.junit.Assert.*;

/**
 * Verifies that the schema initializes at the expected version and that key constraints
 * (UNIQUE on trades.uuid, nullable consumed_trade.event_id) are present.
 */
public class SchemaMigrationTest {

    private Path tempDir;
    private File dbFile;
    private SqliteStorage storage;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("schema_test_");
        dbFile = new File(tempDir.toFile(), "schema.db");
        storage = new SqliteStorage(dbFile);
    }

    @After
    public void tearDown() {
        if (storage != null) storage.close();
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    @Test
    public void testFreshSchemaAtCurrentVersion() throws Exception {
        storage.initializeSchema();
        int version = getUserVersion();
        assertEquals("Fresh DB should be at SCHEMA_VERSION",
            SqliteSchema.SCHEMA_VERSION, version);
    }

    /**
     * v5 -> v6: ge_limit_state gains items_bought_complete (the base the window is
     * recalculated from when the next partial buy arrives, so GE limit counts survive
     * restarts). Simulates a v5 DB by dropping the column and resetting the version, then
     * verifies the upgrade re-adds it and that both counters round-trip.
     */
    @Test
    public void testV5ToV6AddsItemsBoughtComplete() throws Exception {
        storage.initializeSchema();
        Connection conn = storage.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE ge_limit_state DROP COLUMN items_bought_complete;");
            stmt.execute("PRAGMA user_version = 5;");
        }

        storage.close();
        storage = new SqliteStorage(dbFile);
        storage.initializeSchema();
        assertEquals("Upgraded DB should be at SCHEMA_VERSION", SqliteSchema.SCHEMA_VERSION, getUserVersion());

        storage.upsertAccount("GeAcct", null);
        storage.upsertGeLimitState("GeAcct", 4151, java.time.Instant.ofEpochMilli(1789500000000L), 110, 100);

        java.util.Map<String, Object> state = storage.loadGeLimitState("GeAcct", 4151);
        assertNotNull("GE limit state should be restored", state);
        assertEquals("items_bought must round-trip", 110, state.get("itemsBought"));
        assertEquals("items_bought_complete must round-trip",
            100, state.get("itemsBoughtThroughCompleteOffers"));

        // The account-level load must restore BOTH counters onto the FlippingItem's history.
        java.util.Map<Integer, java.util.Map<String, Object>> all = storage.loadAllGeLimitStates("GeAcct");
        assertEquals(100, all.get(4151).get("itemsBoughtThroughCompleteOffers"));
    }

    @Test
    public void testReinitializeIsNoOp() throws Exception {
        storage.initializeSchema();
        int v1 = getUserVersion();
        storage.initializeSchema();
        int v2 = getUserVersion();
        assertEquals("Re-running initializeSchema should not change version", v1, v2);
    }

    @Test
    public void testTradesUuidUniqueConstraint() throws Exception {
        storage.initializeSchema();
        // The v4 schema adds UNIQUE(account_id, uuid). Verify by attempting a duplicate insert.
        storage.upsertAccount("Acct", null);
        int id1 = storage.insertTrade("Acct", 4151, "dup-uuid", 1700000000000L, 1, 100, true);
        assertTrue("First insert should succeed", id1 > 0);
        // Second insert with same uuid should be ignored by INSERT OR IGNORE (returns -1).
        int id2 = storage.insertTrade("Acct", 4151, "dup-uuid", 1700000000000L, 1, 100, true);
        assertEquals("Duplicate uuid insert should be ignored", -1, id2);
    }

    @Test
    public void testConsumedTradeEventIdNullable() throws Exception {
        storage.initializeSchema();
        storage.upsertAccount("Acct", null);
        int tradeId = storage.insertTrade("Acct", 4151, "void-uuid", 1700000000000L, 1, 100, false);
        assertTrue("Trade insert should succeed", tradeId > 0);
        // Voiding a trade (eventId = null) should not throw now that event_id is nullable.
        storage.consumeTrade(tradeId, 1, null);
    }

    @Test
    public void testEventsNaturalKeyUnique() throws Exception {
        storage.initializeSchema();
        // Verify the partial unique index exists by checking that two events with the same
        // natural_key cannot both be inserted.
        Connection conn = storage.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO accounts (display_name) VALUES ('NKTest')");
            stmt.execute("INSERT INTO events (account_id, timestamp, type, cost, profit, natural_key) " +
                "VALUES (1, 1700000000000, 'flip', 100, 50, 'nk-1')");
            // Second insert with same natural_key should fail (caught by partial unique index).
            try {
                stmt.execute("INSERT INTO events (account_id, timestamp, type, cost, profit, natural_key) " +
                    "VALUES (1, 1700000000001, 'flip', 200, 75, 'nk-1')");
                fail("Duplicate natural_key insert should have been rejected");
            } catch (Exception expected) {
                // Good: the unique index rejected the duplicate
            }
        }
    }

    private int getUserVersion() throws Exception {
        Connection conn = storage.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
