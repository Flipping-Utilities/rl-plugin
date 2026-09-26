package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.db.MigrationService;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
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

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Run production migration and live DAO writes through the exact browser bridge protocol. */
public class BrowserSqliteStorageTest {
    private static final Instant TIME = Instant.parse("2026-01-02T03:04:05Z");
    private static final long LARGE = 9_007_199_254_740_993L;
    private static final Gson JSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (instant, type, context) -> new JsonPrimitive(instant.toEpochMilli()))
        .create();
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void productionMigrationMatchesNativeModelsAndBatchesAcrossTheBridge() throws Exception {
        File browserFile = temporary.newFile("browser.db");
        NativeBridge bridge = new NativeBridge(browserFile);
        BrowserSqliteStorage browser = new BrowserSqliteStorage(browserFile, bridge);
        SqliteStorage nativeStorage = new SqliteStorage(temporary.newFile("native.db"));
        try {
            Map<String, AccountData> accounts = fixtures();
            TradePersister persister = new TradePersister(new Gson());
            assertEquals(2, new MigrationService(browser, persister).migrate(accounts));
            assertEquals(2, new MigrationService(nativeStorage, persister).migrate(accounts));
            assertEquals("true", browser.getSetting("migration_completed"));
            assertEquals(Arrays.asList(500, 1), bridge.tradeBatches);
            BrowserSqliteImporter.validateSchema(browser.getConnection());
            assertEquals(nativeStorage.listAccounts(), browser.listAccounts());
            for (String account : browser.listAccounts()) {
                assertModelsEqual(nativeStorage.loadAccount(account), browser.loadAccount(account));
            }
            AccountData alice = browser.loadAccount("Alice");
            assertEquals(LARGE, alice.getAccumulatedSessionTimeMillis());
            assertEquals(LARGE, alice.getRecipeFlipGroups().get(0).getRecipeFlips().get(0).getCoinCost());
            assertEquals(1, alice.getLastOffers().size());
            assertTrue(alice.getTrades().stream().anyMatch(item -> item.getItemId() == 11802 && item.isFavorite()));
            assertTrue(alice.getTrades().stream().anyMatch(item -> item.getItemId() == 11804 && !item.getValidFlippingPanelItem()));
            assertEquals("Completed migration is idempotent", 0, new MigrationService(browser, persister).migrate(accounts));
            assertModelsEqual(nativeStorage.loadAccount("Alice"), browser.loadAccount("Alice"));
        } finally { browser.close(); nativeStorage.close(); }
    }

    @Test public void migrationFailureRollsBackTheAccountAndCanRetryWithoutDuplicates() throws Exception {
        File file = temporary.newFile("retry.db");
        NativeBridge bridge = new NativeBridge(file);
        BrowserSqliteStorage storage = new BrowserSqliteStorage(file, bridge);
        try {
            Map<String, AccountData> accounts = Collections.singletonMap("Alice", fixtures().get("Alice"));
            MigrationService migration = new MigrationService(storage, new TradePersister(new Gson()));
            bridge.failSqlContaining = "INSERT OR REPLACE INTO item_favorites";
            assertEquals(0, migration.migrate(accounts));
            assertNull(storage.getSetting("migration_completed"));
            assertNull(storage.getSetting("migrated_Alice"));
            assertTrue(storage.listAccounts().isEmpty());
            assertTrue(storage.getConnection().getAutoCommit());
            bridge.failSqlContaining = null;
            assertEquals(1, migration.migrate(accounts));
            assertEquals("true", storage.getSetting("migration_completed"));
            assertEquals(502, storage.loadAccount("Alice").getTrades().get(0).getHistory().getCompressedOfferEvents().size());
            BrowserSqliteImporter.validateSchema(storage.getConnection());
        } finally { storage.close(); }
    }

    @Test public void liveOfferTransactionsRollBackAndDeletionPathsUseTheSameWritableDatabase() throws Exception {
        File file = temporary.newFile("live.db");
        NativeBridge bridge = new NativeBridge(file);
        BrowserSqliteStorage storage = new BrowserSqliteStorage(file, bridge);
        try {
            storage.initializeSchema();
            OfferEvent original = offer("original", 0, GrandExchangeOfferState.BOUGHT);
            storage.recordTrade("Alice", original);
            OfferEvent partial = offer("partial", 1, GrandExchangeOfferState.BUYING);
            bridge.failSqlContaining = "INSERT OR REPLACE INTO active_slots";
            try {
                storage.recordOfferUpdate("Alice", partial, Collections.singletonList("original"));
                fail("A failed slot write must fail the transaction");
            } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("slot")); }
            assertEquals("original", storage.loadAccount("Alice").getTrades().get(0).getHistory().getCompressedOfferEvents().get(0).getUuid());
            assertTrue(storage.loadAccount("Alice").getLastOffers().isEmpty());
            bridge.failSqlContaining = null;
            storage.recordOfferUpdate("Alice", partial, Collections.singletonList("original"));
            assertEquals("partial", storage.loadAccount("Alice").getLastOffers().get(0).getUuid());
            storage.archiveOfferAndClearSlot("Alice", 0, partial);
            assertTrue(storage.loadAccount("Alice").getLastOffers().isEmpty());
            storage.upsertFavorite("Alice", 4151, true, "favorite");
            storage.upsertItemVisibility("Alice", 4151, false);
            RecipeFlip flip = recipe(partial);
            storage.insertRecipeFlip("Alice", "live-recipe", flip);
            storage.insertRecipeFlip("Alice", "live-recipe", flip);
            assertEquals("Ignored duplicate insert must not reuse a stale generated key", 1,
                storage.loadAccount("Alice").getRecipeFlipGroups().get(0).getRecipeFlips().size());
            storage.deleteTradesByUuid("Alice", Collections.singletonList("partial"));
            assertTrue(storage.loadAccount("Alice").getRecipeFlipGroups().isEmpty());
            assertTrue(storage.loadAccount("Alice").getTrades().get(0).getHistory().getCompressedOfferEvents().isEmpty());
            storage.deleteAccountData("Alice");
            assertTrue(storage.listAccounts().isEmpty());
            BrowserSqliteImporter.validateSchema(storage.getConnection());
        } finally { storage.close(); }
    }

    @Test public void jdbcTransactionsRemainManualAfterCommitAndRollbackAndCommitWhenEnabled() throws Exception {
        File file = temporary.newFile("transactions.db");
        BrowserSqliteStorage storage = new BrowserSqliteStorage(file, new NativeBridge(file));
        try {
            storage.initializeSchema();
            Connection connection = storage.getConnection();
            connection.setAutoCommit(false);
            storage.upsertAccount("Committed", null);
            connection.commit();
            assertFalse(connection.getAutoCommit());
            storage.upsertAccount("Rolled back", null);
            connection.rollback();
            assertFalse(connection.getAutoCommit());
            storage.invalidateAccountCache();
            assertEquals(Collections.singletonList("Committed"), storage.listAccounts());
            connection.setAutoCommit(false);
            storage.upsertAccount("Commit on enable", null);
            connection.setAutoCommit(true);
            assertEquals(Arrays.asList("Commit on enable", "Committed"), storage.listAccounts());
            try { connection.rollback(); fail("Rollback in auto-commit mode must be rejected"); }
            catch (SQLException expected) { assertTrue(expected.getMessage().contains("auto-commit")); }
            connection.setAutoCommit(false);
            storage.upsertAccount("Rolled back on close", null);
        } finally { storage.close(); }
        SqliteStorage reopened = new SqliteStorage(file);
        try { assertEquals(Arrays.asList("Commit on enable", "Committed"), reopened.listAccounts()); }
        finally { reopened.close(); }
    }

    @Test public void parameterBindingBatchSnapshotsAndGeneratedKeysAreIndependent() throws Exception {
        File file = temporary.newFile("parameters.db");
        BrowserSqliteStorage storage = new BrowserSqliteStorage(file, new NativeBridge(file));
        try {
            storage.initializeSchema();
            Connection connection = storage.getConnection();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO settings(key,value) VALUES (?,?)")) {
                statement.setString(1, "one"); statement.setString(2, "first"); statement.addBatch();
                statement.setString(1, "two"); statement.setNull(2, Types.VARCHAR); statement.addBatch();
                assertArrayEquals(new int[]{1, 1}, statement.executeBatch());
                assertArrayEquals(new int[0], statement.executeBatch());
                statement.clearParameters();
                try { statement.executeUpdate(); fail("Cleared bindings must not reuse earlier values"); }
                catch (SQLException expected) { assertTrue(expected.getMessage().contains("Unbound")); }
            }
            assertEquals("first", storage.getSetting("one"));
            assertNull(storage.getSetting("two"));
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounts(display_name, accumulated_time) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "Exact"); statement.setLong(2, LARGE);
                assertEquals(1, statement.executeUpdate());
                try (ResultSet keys = statement.getGeneratedKeys()) { assertTrue(keys.next()); assertTrue(keys.getLong(1) > 0); }
            }
            assertEquals(LARGE, storage.loadAccount("Exact").getAccumulatedSessionTimeMillis());
        } finally { storage.close(); }
    }

    private static Map<String, AccountData> fixtures() {
        AccountData alice = new AccountData();
        alice.setSessionStartTime(TIME);
        alice.setAccumulatedSessionTimeMillis(LARGE);
        FlippingItem whip = new FlippingItem(4151, "Abyssal whip", 70, "Alice");
        for (int index = 0; index < 501; index++) whip.getHistory().getCompressedOfferEvents()
            .add(offer("trade-" + index, index, index % 2 == 0 ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD));
        OfferEvent active = offer("active", 502, GrandExchangeOfferState.BUYING);
        alice.getLastOffers().put(0, active);
        whip.getHistory().getCompressedOfferEvents().add(active);
        whip.setFavorite(true); whip.setFavoriteCode("whip");
        whip.getHistory().setNextGeLimitRefresh(TIME.plusSeconds(14400));
        whip.getHistory().setItemsBoughtThisLimitWindow(23);
        whip.getHistory().setItemsBoughtThroughCompleteOffers(20);
        alice.getTrades().add(whip);
        FlippingItem favorite = new FlippingItem(11802, "Favorite only", 8, "Alice");
        favorite.setFavorite(true); favorite.setFavoriteCode("fav-only"); alice.getTrades().add(favorite);
        FlippingItem hidden = new FlippingItem(11804, "Hidden only", 8, "Alice");
        hidden.setValidFlippingPanelItem(false); alice.getTrades().add(hidden);
        RecipeFlipGroup group = new RecipeFlipGroup("test-recipe");
        group.getRecipeFlips().add(recipe(whip.getHistory().getCompressedOfferEvents().get(0)));
        alice.getRecipeFlipGroups().add(group);
        AccountData bob = new AccountData(); bob.setSessionStartTime(TIME);
        Map<String, AccountData> accounts = new LinkedHashMap<>();
        accounts.put("Alice", alice); accounts.put("Bob", bob);
        return accounts;
    }

    private static RecipeFlip recipe(OfferEvent source) {
        Map<String, PartialOffer> components = new LinkedHashMap<>();
        components.put(source.getUuid(), new PartialOffer(source, 1));
        components.put("missing-reference", new PartialOffer("missing-reference", 1));
        return new RecipeFlip(TIME.plusSeconds(600), Collections.emptyMap(),
            Collections.singletonMap(source.getItemId(), components), LARGE);
    }

    private static OfferEvent offer(String uuid, int seconds, GrandExchangeOfferState state) {
        boolean buy = state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.BUYING;
        int quantity = state == GrandExchangeOfferState.BUYING ? 3 : 10;
        return new OfferEvent(uuid, buy, 4151, quantity, 100, TIME.plusSeconds(seconds),
            0, state, seconds, 5, 10, null, false, "Alice", "Abyssal whip", 100, quantity * 100);
    }

    private static void assertModelsEqual(AccountData expected, AccountData actual) {
        expected.setLastModifiedAt(TIME); actual.setLastModifiedAt(TIME);
        assertEquals(JSON.toJsonTree(expected), JSON.toJsonTree(actual));
    }

    /** Native SQLite at the bridge boundary is independent of the JDBC facade being tested. */
    private static final class NativeBridge implements BrowserSqliteStorage.Bridge {
        private final Connection nativeConnection;
        private final List<Integer> tradeBatches = new ArrayList<>();
        private String failSqlContaining;

        NativeBridge(File file) throws SQLException {
            nativeConnection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement statement = nativeConnection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        }

        @Override public String execute(String operation, String sql, String parametersJson) {
            JsonObject result = new JsonObject();
            try {
                if (failSqlContaining != null && sql.contains(failSqlContaining)) throw new SQLException("Injected write failure");
                if (sql.startsWith("VACUUM INTO")) throw new SQLException("Browser backups do not share Java filesystem paths");
                if (operation.equals("control")) {
                    if (sql.equals("CLOSE")) nativeConnection.close();
                    else try (Statement statement = nativeConnection.createStatement()) { statement.execute(sql); }
                } else {
                    JsonArray parameters = new JsonParser().parse(parametersJson).getAsJsonArray();
                    try (PreparedStatement statement = nativeConnection.prepareStatement(sql)) {
                        if (operation.equals("batch")) {
                            if (sql.startsWith("INSERT OR IGNORE INTO trades")) tradeBatches.add(parameters.size());
                            JsonArray counts = new JsonArray();
                            for (JsonElement row : parameters) { bind(statement, row.getAsJsonArray()); counts.add(statement.executeUpdate()); }
                            result.add("counts", counts);
                        } else {
                            bind(statement, parameters);
                            if (operation.equals("query")) {
                                JsonArray columns = new JsonArray(), rows = new JsonArray();
                                if (statement.execute()) try (ResultSet cursor = statement.getResultSet()) {
                                    ResultSetMetaData metadata = cursor.getMetaData();
                                    for (int index = 1; index <= metadata.getColumnCount(); index++) columns.add(metadata.getColumnLabel(index));
                                    while (cursor.next()) {
                                        JsonArray row = new JsonArray();
                                        for (int index = 1; index <= metadata.getColumnCount(); index++) {
                                            Object value = cursor.getObject(index);
                                            row.add(value == null ? null : new JsonPrimitive(value.toString()));
                                        }
                                        rows.add(row);
                                    }
                                }
                                result.add("columns", columns); result.add("rows", rows);
                            } else if (operation.equals("update")) {
                                result.addProperty("changes", statement.executeUpdate());
                                try (Statement keys = nativeConnection.createStatement(); ResultSet row = keys.executeQuery("SELECT last_insert_rowid()")) {
                                    row.next(); result.addProperty("lastInsertId", row.getString(1));
                                }
                            } else throw new SQLException("Unexpected bridge operation: " + operation);
                        }
                    }
                }
            } catch (SQLException failure) { result.addProperty("error", failure.getMessage()); }
            return JSON.toJson(result);
        }

        private static void bind(PreparedStatement statement, JsonArray parameters) throws SQLException {
            statement.clearParameters();
            for (int index = 0; index < parameters.size(); index++) {
                JsonElement value = parameters.get(index);
                if (value.isJsonNull()) statement.setNull(index + 1, Types.VARCHAR);
                else statement.setString(index + 1, value.getAsString());
            }
        }
    }
}
