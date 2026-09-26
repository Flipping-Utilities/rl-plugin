package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BrowserSqliteImporterTest {
    private static final Instant TIME = Instant.parse("2025-09-10T12:34:56Z");
    private static final long LARGE_INTEGER = 9_007_199_254_740_993L;
    private static final Gson MODEL_JSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) -> new JsonPrimitive(value.toEpochMilli()))
        .create();

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void browserQueryBridgeReconstructsTheSameCompleteModelsAsNativeSqlite() throws Exception {
        SqliteStorage storage = new SqliteStorage(temporary.newFile("parity.db"));
        try {
            storage.initializeSchema();
            seed(storage);
            Connection connection = storage.getConnection();
            long changesBefore = totalChanges(connection);
            List<String> queries = new ArrayList<>();
            Map<String, AccountData> imported = BrowserSqliteImporter.load((sql, parameters) -> {
                queries.add(sql);
                return execute(connection, sql, parameters);
            });
            assertEquals(Arrays.asList("Alice", "Bob"), new ArrayList<>(imported.keySet()));
            for (Map.Entry<String, AccountData> entry : imported.entrySet()) {
                AccountData expected = storage.loadAccount(entry.getKey());
                // This field is construction time, not persisted SQLite state.
                expected.setLastModifiedAt(TIME);
                entry.getValue().setLastModifiedAt(TIME);
                assertEquals("Full persisted model for " + entry.getKey(),
                    MODEL_JSON.toJsonTree(expected), MODEL_JSON.toJsonTree(entry.getValue()));
            }
            assertEquals("Import must not issue writes", changesBefore, totalChanges(connection));
            assertTrue(queries.contains("PRAGMA foreign_key_check"));
            assertTrue(queries.stream().anyMatch(sql -> sql.contains("recipe_flip_inputs")));

            AccountData alice = imported.get("Alice");
            assertEquals(LARGE_INTEGER, alice.getAccumulatedSessionTimeMillis());
            assertEquals(LARGE_INTEGER, alice.getRecipeFlipGroups().get(0).getRecipeFlips().get(0).getCoinCost());
            assertEquals(3, alice.getLastOffers().size());
            List<OfferEvent> history = item(alice, 4151).getHistory().getCompressedOfferEvents();
            assertEquals(1, history.stream().filter(offer -> "same-uuid".equals(offer.getUuid())).count());
            assertTrue(history.stream().anyMatch(offer -> "partial".equals(offer.getUuid())));
            assertFalse(history.stream().anyMatch(offer -> "deleted-partial".equals(offer.getUuid())));
            assertTrue(item(alice, 4587).isFavorite());
            assertEquals("fav-only", item(alice, 4587).getFavoriteCode());
            assertEquals(Boolean.FALSE, item(alice, 11802).getValidFlippingPanelItem());
            assertNull(item(alice, 13190).getHistory().getNextGeLimitRefresh());
            assertEquals(5, item(alice, 13190).getHistory().getItemsBoughtThroughCompleteOffers());
            assertNull(alice.getRecipeFlipGroups().get(0).getRecipeFlips().get(0)
                .getInputs().get(1515).get("missing-offer").getOffer());
        } finally {
            storage.close();
        }
    }

    @Test public void unsupportedSchemaDoesNotInitializeOrMigrateTheDatabase() throws Exception {
        SqliteStorage storage = new SqliteStorage(temporary.newFile("uninitialized.db"));
        try {
            Connection connection = storage.getConnection();
            assertImportFails(connection, "requires version 1");
            try (Statement statement = connection.createStatement();
                 ResultSet tables = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'")) {
                assertTrue(tables.next());
                assertEquals(0, tables.getInt(1));
            }
            storage.initializeSchema();
            try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA user_version=2"); }
            assertImportFails(connection, "requires version 1");
        } finally {
            storage.close();
        }
    }

    @Test public void schemaVersionAloneDoesNotHideMissingColumnsOrBrokenReferences() throws Exception {
        SqliteStorage storage = new SqliteStorage(temporary.newFile("invalid-schema.db"));
        try {
            storage.initializeSchema();
            Connection connection = storage.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE active_slots RENAME COLUMN history_visible TO old_history_visible");
            }
            assertImportFails(connection, "history_visible");
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE active_slots RENAME COLUMN old_history_visible TO history_visible");
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.execute("INSERT INTO item_visibility(account_id,item_id,is_visible) VALUES(999,4151,1)");
            }
            assertImportFails(connection, "foreign-key");
        } finally {
            storage.close();
        }
    }

    @Test public void malformedResponsesAndQueryFailuresCannotBecomeEmptyImports() throws Exception {
        try {
            BrowserSqliteImporter.load((sql, parameters) -> "{\"columns\":[\"user_version\"],\"rows\":[[\"1\",\"extra\"]]}");
            fail("Malformed response must fail");
        } catch (SQLException expected) {
            assertTrue(expected.getMessage().contains("Malformed"));
        }
        try {
            BrowserSqliteImporter.load((sql, parameters) -> { throw new SQLException("bridge unavailable"); });
            fail("Query failure must propagate");
        } catch (SQLException expected) {
            assertEquals("bridge unavailable", expected.getMessage());
        }
    }

    @Test public void accountNamesMustRemainRepresentableByTheJsonBackend() throws Exception {
        SqliteStorage storage = new SqliteStorage(temporary.newFile("reserved-account.db"));
        try {
            storage.initializeSchema();
            storage.upsertAccount("accountwide", null);
            assertImportFails(storage.getConnection(), "Account name");
        } finally {
            storage.close();
        }
    }

    private static void seed(SqliteStorage storage) throws SQLException {
        storage.upsertAccount("Alice", "player-id");
        storage.upsertAccount("Bob", null);
        storage.updateAccountSessionTime("Alice", LARGE_INTEGER);
        try (PreparedStatement statement = storage.getConnection().prepareStatement("UPDATE accounts SET session_start=?")) {
            statement.setLong(1, TIME.toEpochMilli());
            statement.executeUpdate();
        }
        storage.recordTrade("Alice", offer("buy", GrandExchangeOfferState.BOUGHT, 4151, 10, 10, 100, 0));
        storage.recordTrade("Alice", offer("sell", GrandExchangeOfferState.SOLD, 4151, 10, 10, 130, 20));
        storage.recordTrade("Alice", offer("archived-partial", GrandExchangeOfferState.BUYING, 4151, 2, 8, 99, 25));
        storage.recordTrade("Alice", offer("same-uuid", GrandExchangeOfferState.BOUGHT, 4151, 4, 4, 100, 30));
        OfferEvent partial = offer("partial", GrandExchangeOfferState.BUYING, 4151, 3, 7, 105, 40);
        partial.setSlot(1);
        storage.upsertSlot("Alice", 1, partial, true);
        OfferEvent stale = offer("same-uuid", GrandExchangeOfferState.BUYING, 4151, 2, 4, 100, 29);
        stale.setSlot(2);
        storage.upsertSlot("Alice", 2, stale, true);
        OfferEvent deleted = offer("deleted-partial", GrandExchangeOfferState.SELLING, 4151, 2, 9, 120, 50);
        deleted.setSlot(3);
        storage.upsertSlot("Alice", 3, deleted, false);
        storage.upsertFavorite("Alice", 4151, true, "active");
        storage.upsertFavorite("Alice", 4587, true, "fav-only");
        storage.upsertItemVisibility("Alice", 4151, false);
        storage.upsertItemVisibility("Alice", 11802, false);
        storage.upsertGeLimitState("Alice", 4151, TIME.plusSeconds(14400), 17, 14);
        storage.upsertGeLimitState("Alice", 13190, null, 7, 5);

        Map<String, PartialOffer> inputs = new LinkedHashMap<>();
        inputs.put("consumed", new PartialOffer(offer("consumed", GrandExchangeOfferState.BOUGHT, 1515, 12, 12, 20, 60), 12));
        inputs.put("missing-offer", new PartialOffer("missing-offer", 2));
        Map<Integer, Map<String, PartialOffer>> outputs = Collections.singletonMap(1215,
            Collections.singletonMap("recipe-sale", new PartialOffer(offer("recipe-sale", GrandExchangeOfferState.SOLD, 1215, 3, 3, 500, 80), 3)));
        storage.insertRecipeFlip("Alice", "recipe-a", new RecipeFlip(TIME.plusSeconds(90), outputs,
            Collections.singletonMap(1515, inputs), LARGE_INTEGER));
        storage.recordTrade("Bob", offer("other-account", GrandExchangeOfferState.CANCELLED_BUY, 4151, 1, 5, 88, 100));
        storage.insertRecipeFlip("Bob", "recipe-b", new RecipeFlip(TIME.plusSeconds(110), Collections.emptyMap(), Collections.emptyMap(), 0));
    }

    /** Mimics sql.js wire values: all integers arrive as exact decimal strings. */
    private static String execute(Connection connection, String sql, String parametersJson) throws SQLException {
        JsonArray parameters = new JsonParser().parse(parametersJson).getAsJsonArray();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                JsonElement value = parameters.get(i);
                statement.setString(i + 1, value.isJsonNull() ? null : value.getAsString());
            }
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData metadata = result.getMetaData();
                JsonArray columns = new JsonArray();
                JsonArray rows = new JsonArray();
                for (int column = 1; column <= metadata.getColumnCount(); column++) columns.add(metadata.getColumnLabel(column));
                while (result.next()) {
                    JsonArray row = new JsonArray();
                    for (int column = 1; column <= metadata.getColumnCount(); column++) row.add(result.getString(column));
                    rows.add(row);
                }
                JsonObject response = new JsonObject();
                response.add("columns", columns);
                response.add("rows", rows);
                return response.toString();
            }
        }
    }

    private static OfferEvent offer(String uuid, GrandExchangeOfferState state, int itemId, int quantity, int total, int price, int seconds) {
        return new OfferEvent(uuid, OfferEvent.isBuy(state), itemId, quantity, price, TIME.plusSeconds(seconds),
            0, state, 123, 8, total, TIME, false, null, null, price, quantity * price);
    }

    private static FlippingItem item(AccountData account, int id) {
        return account.getTrades().stream().filter(item -> item.getItemId() == id).findFirst().orElseThrow(AssertionError::new);
    }

    private static long totalChanges(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT total_changes()")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void assertImportFails(Connection connection, String message) throws SQLException {
        try {
            BrowserSqliteImporter.load((sql, parameters) -> execute(connection, sql, parameters));
            fail("Expected import failure: " + message);
        } catch (SQLException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}
