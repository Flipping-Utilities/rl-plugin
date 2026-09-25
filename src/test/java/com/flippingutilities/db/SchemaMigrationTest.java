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
        assertEquals("The released backend starts with one initial schema", 1, version);
    }

    @Test
    public void testFreshSchemaUsesIndexedRecipeComponentLookups() throws Exception {
        storage.initializeSchema();
        assertRecipeComponentLookupsAreIndexed();
    }

    private void assertRecipeComponentLookupsAreIndexed() throws Exception {
        for (String direction : new String[]{"inputs", "outputs"}) {
            String table = "recipe_flip_" + direction;
            String query = "SELECT item_id, offer_uuid, amount_consumed, offer_json FROM " + table + " WHERE recipe_flip_id = ?";
            boolean indexed = false;
            StringBuilder plan = new StringBuilder();
            try (PreparedStatement statement = storage.getConnection().prepareStatement("EXPLAIN QUERY PLAN " + query)) {
                statement.setLong(1, 1L);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        String detail = results.getString("detail");
                        plan.append(detail).append('\n');
                        if (detail.startsWith("SEARCH " + table + " ") && detail.contains("recipe_flip_id=?")) {
                            indexed = true;
                        }
                    }
                }
            }
            assertTrue("Loading each recipe must search its " + direction + " by flip ID:\n" + plan, indexed);
        }
    }

    @Test
    public void testGeLimitCountersArePresentInInitialSchema() throws Exception {
        storage.initializeSchema();
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
        storage.recordTrade("Acct", complete("Acct", 4151, "saved-offer", 1700000000000L, 1, 100, true));
        storage.close();
        storage.initializeSchema();
        assertEquals(1, getUserVersion());
        assertEquals("saved-offer", storage.loadAccount("Acct").getTrades().get(0)
            .getHistory().getCompressedOfferEvents().get(0).getUuid());
    }

    @Test
    public void testOfferMetadataIsRequired() throws Exception {
        storage.initializeSchema();
        storage.upsertAccount("Acct", null);
        try (Statement statement = storage.getConnection().createStatement()) {
            assertInsertRejected(statement, "INSERT INTO trades (account_id, item_id, timestamp, qty, price, is_buy) " +
                "SELECT id, 4151, 1700000000000, 1, 100, 1 FROM accounts WHERE display_name = 'Acct'");
            assertInsertRejected(statement, "INSERT INTO active_slots (account_id, slot_index) " +
                "SELECT id, 0 FROM accounts WHERE display_name = 'Acct'");
        }
    }

    @Test
    public void testForeignKeysAreEnforced() throws Exception {
        storage.initializeSchema();
        try (Statement statement = storage.getConnection().createStatement()) {
            assertInsertRejected(statement,
                "INSERT INTO item_visibility (account_id, item_id, is_visible) VALUES (999, 4151, 1)");
            try (ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(violations.next());
            }
        }
    }

    private void assertInsertRejected(Statement statement, String sql) throws SQLException {
        try {
            statement.executeUpdate(sql);
            fail("Invalid row should have been rejected");
        } catch (SQLException expected) {
            assertEquals("SQLite constraint violation", 19, expected.getErrorCode());
        }
    }

    @Test
    public void testTradesUuidUniqueConstraint() throws Exception {
        storage.initializeSchema();
        // Re-recording the same offer must preserve a single row.
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
