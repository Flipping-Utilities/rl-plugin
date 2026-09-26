package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.google.gson.Gson;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;
import static com.flippingutilities.db.StorageTestOffers.complete;

/**
 * Verifies the initial schema's version, lookup indexes, and data integrity constraints.
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
            for (String lookupColumn : new String[]{"recipe_flip_id", "offer_uuid"}) {
                String query = "SELECT item_id, offer_uuid, amount_consumed, offer_json FROM " + table +
                    " WHERE " + lookupColumn + " = ?";
                boolean indexed = false;
                StringBuilder plan = new StringBuilder();
                try (PreparedStatement statement = storage.getConnection().prepareStatement("EXPLAIN QUERY PLAN " + query)) {
                    statement.setString(1, "1");
                    try (ResultSet results = statement.executeQuery()) {
                        while (results.next()) {
                            String detail = results.getString("detail");
                            plan.append(detail).append('\n');
                            if (detail.startsWith("SEARCH " + table + " ") && detail.contains(lookupColumn + "=?")) {
                                indexed = true;
                            }
                        }
                    }
                }
                assertTrue("Recipe " + direction + " lookup by " + lookupColumn + " must use an index:\n" + plan, indexed);
            }
        }
    }

    @Test
    public void testGeLimitCountersArePresentInInitialSchema() throws Exception {
        storage.initializeSchema();
        storage.upsertAccount("GeAcct", null);
        storage.upsertGeLimitState("GeAcct", 4151, Instant.ofEpochMilli(1789500000000L), 110, 100);

        Map<String, Object> state = storage.loadAllGeLimitStates("GeAcct").get(4151);
        assertNotNull("GE limit state should be restored", state);
        assertEquals("items_bought must round-trip", 110, state.get("itemsBought"));
        assertEquals("items_bought_complete must round-trip",
            100, state.get("itemsBoughtThroughCompleteOffers"));
    }

    @Test
    public void migrationAndLiveWritesShareOneGeLimitStatePerAccountItem() throws Exception {
        storage.initializeSchema();
        Instant refresh = Instant.parse("2026-09-25T15:00:00Z");
        storage.upsertGeLimitState("GeAcct", 4151, refresh.minusSeconds(60), 10, 8);
        storage.upsertGeLimitState("OtherAcct", 4151, refresh, 70, 60);

        AccountData snapshot = new AccountData();
        FlippingItem item = new FlippingItem(4151, "Whip", 70, "GeAcct");
        item.getHistory().setNextGeLimitRefresh(refresh);
        item.getHistory().setItemsBoughtThisLimitWindow(40);
        item.getHistory().setItemsBoughtThroughCompleteOffers(35);
        snapshot.getTrades().add(item);
        assertEquals(1, new MigrationService(storage, new TradePersister(new Gson()))
            .migrate(Collections.singletonMap("GeAcct", snapshot)));
        assertEquals(40, storage.loadAllGeLimitStates("GeAcct").get(4151).get("itemsBought"));

        storage.upsertGeLimitState("GeAcct", 4151, refresh.plusSeconds(60), 45, 35);
        storage.close();
        Map<String, Object> restored = storage.loadAllGeLimitStates("GeAcct").get(4151);
        assertEquals(45, restored.get("itemsBought"));
        assertEquals(35, restored.get("itemsBoughtThroughCompleteOffers"));
        assertEquals(refresh.plusSeconds(60), restored.get("nextRefresh"));
        assertEquals(70, storage.loadAllGeLimitStates("OtherAcct").get(4151).get("itemsBought"));
        try (Statement statement = storage.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM ge_limit_state WHERE item_id = 4151")) {
            assertTrue(rows.next());
            assertEquals("Each account must have exactly one row for this item", 2, rows.getInt(1));
        }
        try (Statement statement = storage.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN SELECT * FROM ge_limit_state " +
                 "WHERE account_id = 1 AND item_id = 4151")) {
            assertTrue(rows.next());
            assertTrue("Account/item lookups must use the unique index",
                rows.getString("detail").contains("USING INDEX"));
        }
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
            for (String direction : new String[]{"inputs", "outputs"}) {
                assertInsertRejected(statement, "INSERT INTO recipe_flip_" + direction + " (item_id, amount_consumed) " +
                    "VALUES (4151, 1)");
            }
        }
    }

    @Test
    public void testForeignKeysAreEnforced() throws Exception {
        storage.initializeSchema();
        try (Statement statement = storage.getConnection().createStatement()) {
            assertInsertRejected(statement,
                "INSERT INTO item_visibility (account_id, item_id, is_visible) VALUES (999, 4151, 1)");
            assertInsertRejected(statement,
                "INSERT INTO recipe_flips (account_id, timestamp, natural_key) VALUES (999, 1700000000000, 'orphan')");
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
    public void testActiveSlotsAreUniquePerAccountAndSlot() throws Exception {
        storage.initializeSchema();
        storage.upsertAccount("Acct", null);
        storage.upsertAccount("OtherAcct", null);
        try (Statement statement = storage.getConnection().createStatement()) {
            assertEquals(2, statement.executeUpdate("INSERT INTO active_slots (account_id, slot_index, offer_json) " +
                "SELECT id, 0, '{}' FROM accounts"));
            assertInsertRejected(statement, "INSERT INTO active_slots (account_id, slot_index, offer_json) " +
                "SELECT id, 0, '{}' FROM accounts WHERE display_name = 'Acct'");
        }
    }

    @Test
    public void testRecipeNaturalKeyIsRequiredAndUnique() throws Exception {
        storage.initializeSchema();
        Connection conn = storage.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO accounts (display_name) VALUES ('NKTest')");
            stmt.execute("INSERT INTO recipe_flips (account_id, timestamp, recipe_key, coin_cost, natural_key) " +
                "VALUES (1, 1700000000000, 'recipe', 100, 'nk-1')");
            assertInsertRejected(stmt, "INSERT INTO recipe_flips (account_id, timestamp, recipe_key, coin_cost, natural_key) " +
                "VALUES (1, 1700000000001, 'recipe', 200, 'nk-1')");
            assertInsertRejected(stmt, "INSERT INTO recipe_flips (account_id, timestamp, recipe_key, coin_cost) " +
                "VALUES (1, 1700000000001, 'recipe', 200)");
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
