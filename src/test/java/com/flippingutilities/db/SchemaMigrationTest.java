package com.flippingutilities.db;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;

import static org.junit.Assert.*;
import static com.flippingutilities.db.StorageTestOffers.complete;

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

    @Test
    public void testFreshSchemaUsesIndexedRecipeComponentLookups() throws Exception {
        storage.initializeSchema();
        assertRecipeComponentLookupsAreIndexed();
    }

    @Test
    public void testV6UpgradeIndexesRecipeComponentsWithoutChangingData() throws Exception {
        storage.initializeSchema();
        try (Statement stmt = storage.getConnection().createStatement()) {
            removeV8Columns(stmt);
            stmt.execute("DROP INDEX IF EXISTS idx_recipe_flip_inputs_flip");
            stmt.execute("DROP INDEX IF EXISTS idx_recipe_flip_outputs_flip");
            stmt.execute("PRAGMA user_version = 6");
            stmt.execute("INSERT INTO recipe_flips (id, recipe_key, coin_cost) VALUES (1, '4151:4587', 25)");
            stmt.execute("INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) " +
                "VALUES (1, 4151, 'input-offer', 4)");
            stmt.execute("INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) " +
                "VALUES (1, 4587, 'output-offer', 2)");
        }

        storage.close();
        storage = new SqliteStorage(dbFile);
        storage.initializeSchema();

        assertEquals(SqliteSchema.SCHEMA_VERSION, getUserVersion());
        assertRecipeComponentLookupsAreIndexed();
        try (Statement stmt = storage.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT i.offer_uuid, i.amount_consumed, o.offer_uuid, o.amount_consumed " +
                 "FROM recipe_flip_inputs i JOIN recipe_flip_outputs o USING (recipe_flip_id)")) {
            assertTrue(rs.next());
            assertEquals("input-offer", rs.getString(1));
            assertEquals(4, rs.getInt(2));
            assertEquals("output-offer", rs.getString(3));
            assertEquals(2, rs.getInt(4));
            assertFalse(rs.next());
        }
    }

    private void assertRecipeComponentLookupsAreIndexed() throws Exception {
        for (String direction : new String[]{"inputs", "outputs"}) {
            String alias = direction.equals("inputs") ? "rfi" : "rfo";
            // Match loadRecipeFlipInputs/loadRecipeFlipOutputs, including the trade join.
            String query = "SELECT " + alias + ".item_id, " + alias + ".offer_uuid, " + alias + ".amount_consumed, " +
                "t.price, t.timestamp, t.qty AS trade_qty FROM recipe_flip_" + direction + " " + alias + " " +
                "LEFT JOIN trades t ON t.uuid = " + alias + ".offer_uuid WHERE " + alias + ".recipe_flip_id = ?";
            boolean indexed = false;
            StringBuilder plan = new StringBuilder();
            try (PreparedStatement statement = storage.getConnection().prepareStatement("EXPLAIN QUERY PLAN " + query)) {
                statement.setLong(1, 1L);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        String detail = results.getString("detail");
                        plan.append(detail).append('\n');
                        if (detail.startsWith("SEARCH " + alias + " ") && detail.contains("recipe_flip_id=?")) {
                            indexed = true;
                        }
                    }
                }
            }
            assertTrue("Loading each recipe must search its " + direction + " by flip ID:\n" + plan, indexed);
        }
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
            removeV8Columns(stmt);
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
    public void testV7UpgradeRequiresResyncBeforeUsingIncompleteOfferMetadata() throws Exception {
        storage.initializeSchema();
        storage.upsertAccount("Existing account", null);
        storage.recordTrade("Existing account", complete("Existing account", 4151, "original-offer", 1700000000000L, 1, 100, true));
        storage.setSetting("migration_completed", "true");
        try (Statement statement = storage.getConnection().createStatement()) {
            removeV8Columns(statement);
            statement.execute("PRAGMA user_version = 7");
        }
        storage.close();
        storage = new SqliteStorage(dbFile);

        storage.initializeSchema();

        assertEquals(SqliteSchema.SCHEMA_VERSION, getUserVersion());
        assertEquals("true", storage.getSetting("migration_pending"));
        assertTrue(storage.requiresFullResync());
        // Upgrade preserves old records until the caller has safely loaded the JSON source.
        try (Statement statement = storage.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT uuid FROM trades")) {
            assertTrue(rows.next());
            assertEquals("original-offer", rows.getString(1));
        }
    }

    private void removeV8Columns(Statement statement) throws Exception {
        statement.execute("ALTER TABLE trades DROP COLUMN offer_json");
        statement.execute("ALTER TABLE active_slots DROP COLUMN offer_json");
        statement.execute("ALTER TABLE active_slots DROP COLUMN history_visible");
        statement.execute("DROP TABLE item_visibility");
    }

    @Test
    public void testTradesUuidUniqueConstraint() throws Exception {
        storage.initializeSchema();
        // The v4 schema adds UNIQUE(account_id, uuid). Verify by attempting a duplicate insert.
        storage.upsertAccount("Acct", null);
        storage.recordTrade("Acct", complete("Acct", 4151, "dup-uuid", 1700000000000L, 1, 100, true));
        storage.recordTrade("Acct", complete("Acct", 4151, "dup-uuid", 1700000000000L, 1, 100, true));
        try (Statement statement = storage.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM trades")) {
            assertTrue(rows.next());
            assertEquals("Duplicate UUID must not create another trade", 1, rows.getInt(1));
        }
    }

    @Test
    public void testConsumedTradeEventIdNullable() throws Exception {
        storage.initializeSchema();
        storage.upsertAccount("Acct", null);
        storage.recordTrade("Acct", complete("Acct", 4151, "void-uuid", 1700000000000L, 1, 100, false));
        try (Statement statement = storage.getConnection().createStatement()) {
            assertEquals(1, statement.executeUpdate("INSERT INTO consumed_trade (trade_id, qty, event_id) " +
                "SELECT id, 1, NULL FROM trades WHERE uuid = 'void-uuid'"));
        }
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
