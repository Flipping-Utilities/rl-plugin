package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * SQLite storage with connection management and schema initialization.
 */
public class SqliteStorage {
    private static final Logger logger = LoggerFactory.getLogger(SqliteStorage.class);

    static {
        // Register the SQLite driver explicitly. DriverManager's service discovery uses the
        // thread-context classloader, which in a packed RuneLite plugin does not see the
        // plugin jar (where sqlite-jdbc is embedded), so discovery alone throws
        // "No suitable driver". Class.forName runs the driver's static initializer, which
        // registers it with DriverManager regardless of classloader.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.error("SQLite JDBC driver not found on the plugin classpath", e);
        }
    }

    private static final Gson SLOT_GSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
            @Override
            public void write(JsonWriter out, Instant value) throws java.io.IOException {
                if (value == null) {
                    out.nullValue();
                    return;
                }
                out.value(value.toEpochMilli());
            }

            @Override
            public Instant read(JsonReader in) throws java.io.IOException {
                JsonToken token = in.peek();
                if (token == JsonToken.NULL) {
                    in.nextNull();
                    return null;
                }
                if (token == JsonToken.NUMBER) {
                    return Instant.ofEpochMilli(in.nextLong());
                }
                if (token == JsonToken.STRING) {
                    String s = in.nextString();
                    if (s == null || s.trim().isEmpty()) {
                        return null;
                    }
                    return Instant.parse(s);
                }
                in.skipValue();
                return null;
            }
        })
        .create();

    static String serializeOffer(OfferEvent offer) {
        return SLOT_GSON.toJson(offer);
    }

    static String serializeRecipeOffer(PartialOffer component) throws SQLException {
        if (component.getOffer() == null) {
            throw new SQLException("Cannot persist recipe: missing offer " + component.getOfferUuid());
        }
        return serializeOffer(component.getOffer());
    }

    private final File dbFile;
    private Connection connection;
    private boolean schemaInitAttempted;
    // Cache of display_name -> account_id to avoid a SELECT per repository call.
    // Invalidated whenever upsertAccount creates/updates a row.
    private final Map<String, Integer> accountIdCache = new ConcurrentHashMap<>();

    /**
     * Creates a storage bound to the given database file.
     *
     * @param dbFile Database file location
     */
    public SqliteStorage(File dbFile) {
        this.dbFile = dbFile;
    }

    /**
     * Returns the database file this storage is bound to. Used by MigrationService to
     * create pre-migration backups.
     */
    public File getDbFile() {
        return dbFile;
    }

    private Path recoveryMarker() {
        return new File(dbFile.getAbsolutePath() + ".needs-resync").toPath();
    }

    /** Kept outside SQLite so a failed database write cannot leave the database authoritative. */
    public synchronized void markOutOfSync() {
        try {
            Files.write(recoveryMarker(), new byte[0]);
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist SQLite recovery marker", e);
        }
    }

    public synchronized boolean requiresFullResync() {
        return Files.exists(recoveryMarker());
    }

    /** Called only after the full JSON import has committed successfully. */
    public synchronized void markSynchronized() {
        try {
            Files.deleteIfExists(recoveryMarker());
        } catch (IOException e) {
            throw new IllegalStateException("Could not clear SQLite recovery marker", e);
        }
    }

    /**
     * Returns a valid JDBC Connection to the SQLite database.
     * Creates connection if needed, enables WAL mode and sets busy timeout.
     * @return Active database connection
     * @throws SQLException if connection cannot be established
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(2)) {
                    return connection;
                }
                logger.warn("SQLite connection invalid, reconnecting...");
                try { connection.close(); } catch (SQLException e) { logger.debug("Closing stale connection failed", e); }
                connection = null;
            } catch (SQLException e) {
                connection = null;
            }
        }

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                connection = DriverManager.getConnection(url);
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL;");
                    stmt.execute("PRAGMA busy_timeout=5000;");
                    stmt.execute("PRAGMA foreign_keys=ON;");
                }
                return connection;
            } catch (SQLException e) {
                if (attempts >= 3) throw e;
                logger.warn("SQLite connection attempt {} failed, retrying...", attempts, e);
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted during connection retry", ie);
                }
            }
        }
    }

    /**
     * Close the underlying connection if present.
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.warn("Error while closing SQLite connection", e);
            } finally {
                connection = null;
            }
        }
        // Reset init/cache state so a subsequent open on a (possibly replaced) file starts clean.
        schemaInitAttempted = false;
        accountIdCache.clear();
    }

    /**
     * Drop all cached account-id mappings. Must be called after the accounts table is
     * emptied/recreated outside of normal upserts (e.g. the DELETE/REGENERATE maintenance
     * actions), otherwise stale ids cause FK violations on later writes.
     */
    public synchronized void invalidateAccountCache() {
        accountIdCache.clear();
    }

// Simple key-value settings helpers stored in the 'settings' table
    /**
     * Get a setting value from the settings table.
     * @param key Setting key
     * @return Value or null if not found
     */
    public synchronized String getSetting(String key) {
        try {
            Connection conn = getConnection();
            String sql = "SELECT value FROM settings WHERE key = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("value");
                    }
                }
            }
        } catch (SQLException e) {
            // If the schema isn't initialized yet, try to initialize and retry exactly once.
            // schemaInitAttempted breaks the recursion if init itself fails.
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table") && !schemaInitAttempted) {
                try {
                    initializeSchema();
                } catch (RuntimeException initFailure) {
                    throw new IllegalStateException("Could not initialize settings", initFailure);
                }
                return getSetting(key);
            }
            throw new IllegalStateException("Could not read setting " + key, e);
        }
        return null;
    }

    /**
     * Set a setting value in the settings table.
     * @param key Setting key
     * @param value Setting value
     */
    public synchronized void setSetting(String key, String value) {
        try {
            Connection conn = getConnection();
            String sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table") && !schemaInitAttempted) {
                try {
                    initializeSchema();
                } catch (RuntimeException initFailure) {
                    throw new IllegalStateException("Could not initialize settings", initFailure);
                }
                setSetting(key, value);
            } else {
                throw new IllegalStateException("Could not persist setting " + key, e);
            }
        }
    }

    /**
     * Clear a setting from the settings table.
     * @param key Setting key to delete
     */
    public synchronized void clearSetting(String key) {
        try {
            Connection conn = getConnection();
            String sql = "DELETE FROM settings WHERE key = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table") && !schemaInitAttempted) {
                try {
                    initializeSchema();
                } catch (RuntimeException initFailure) {
                    throw new IllegalStateException("Could not initialize settings", initFailure);
                }
                clearSetting(key);
            } else {
                throw new IllegalStateException("Could not clear setting " + key, e);
            }
        }
    }

    /**
     * Initialize database schema using SqliteSchema DDL.
     * Creates all tables, indexes, and sets user_version.
     */
    public synchronized void initializeSchema() {
        try {
            Connection conn = getConnection();

            // Check current schema version
            int currentVersion = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(SqliteSchema.PRAGMA_GET_USER_VERSION)) {
                if (rs.next()) {
                    currentVersion = rs.getInt(1);
                }
            }

            if (currentVersion == SqliteSchema.SCHEMA_VERSION) {
                logger.debug("Schema already at version {}, nothing to do", currentVersion);
                return;
            }

            if (currentVersion != 0) {
                throw new IllegalStateException("Unsupported SQLite schema version: " + currentVersion);
            }

            // Create the complete initial schema and its version stamp atomically. There
            // are no historical database versions to upgrade before the first release.
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : SqliteSchema.getCreateStatementsInOrder()) {
                        stmt.execute(sql);
                    }
                    for (String sql : SqliteSchema.getIndexStatements()) {
                        stmt.execute(sql);
                    }
                    stmt.execute(SqliteSchema.getMigrationStatement());
                    try (ResultSet violations = stmt.executeQuery("PRAGMA foreign_key_check")) {
                        if (violations.next()) {
                            throw new SQLException("Foreign key violation while initializing SQLite schema");
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            // Rethrow instead of swallowing: callers (notably FlippingPlugin.rebuildSqliteFromJson)
            // catch this and fall back to the JSON backend. Swallowing here left a corrupt/locked
            // DB in SQLite mode with every subsequent operation failing silently.
            logger.error("Error initializing SQLite schema", e);
            throw new IllegalStateException("Failed to initialize SQLite schema", e);
        } finally {
            // Guard against infinite recursion in the setting helpers: once we've attempted init
            // (whether it succeeded or failed), don't try again on the next "no such table" error.
            schemaInitAttempted = true;
        }
    }

    /**
     * Upsert an account record. Creates new or updates existing.
     * @param displayName Account display name
     * @param playerId Player ID from RuneLite API
     */
    public synchronized void upsertAccount(String displayName, String playerId) {
        // player_id is preserved on update (COALESCE) so a re-upsert with a null playerId
        // (the common case, since OfferEvents don't carry it) doesn't wipe a known value.
        final String sql = "INSERT OR REPLACE INTO accounts (id, display_name, player_id, session_start, accumulated_time) " +
                "VALUES (" +
                "  (SELECT id FROM accounts WHERE display_name = ?)," +
                "  ?," +
                "  COALESCE((SELECT player_id FROM accounts WHERE display_name = ?), ?)," +
                "  COALESCE((SELECT session_start FROM accounts WHERE display_name = ?), ?)," +
                "  COALESCE((SELECT accumulated_time FROM accounts WHERE display_name = ?), ?)" +
                ")";

        long nowMillis = Instant.now().toEpochMilli();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, displayName);
                ps.setString(2, displayName);
                ps.setString(3, displayName);
                if (playerId == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, playerId);
                }
                ps.setString(5, displayName);
                ps.setLong(6, nowMillis);
                ps.setString(7, displayName);
                ps.setLong(8, 0L);
                ps.executeUpdate();
            }
            // Invalidate cache; next getAccountId() will repopulate with the upserted row.
            accountIdCache.remove(displayName);
        } catch (SQLException e) {
            throw new IllegalStateException("Error upserting account", e);
        }
    }
    /**
     * Load account data from SQLite, fully reconstructing trades and flips.
     * @param displayName Account display name
     * @return AccountData object with all trades loaded, or null if the account is unknown
     *         (callers fall back to JSON in that case)
     */
    public synchronized AccountData loadAccount(String displayName) {
        AccountData data = new AccountData();
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return null;
        }

        // Load session info
        final String sessionSql = "SELECT session_start, accumulated_time FROM accounts WHERE id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sessionSql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long sessionStartMillis = rs.getLong("session_start");
                        if (!rs.wasNull() && sessionStartMillis > 0) {
                            data.setSessionStartTime(Instant.ofEpochMilli(sessionStartMillis));
                        }
                        long accumulatedTimeMillis = rs.getLong("accumulated_time");
                        if (!rs.wasNull()) {
                            data.setAccumulatedSessionTimeMillis(accumulatedTimeMillis);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading session info", e);
        }

        // Load trades and convert to FlippingItem objects
        Map<Integer, OfferEvent> partialHistory = new HashMap<>();
        data.getLastOffers().putAll(loadAllSlots(displayName, partialHistory));
        List<FlippingItem> tradeItems = loadTradeItems(accountId, displayName, partialHistory);
        data.getTrades().addAll(tradeItems);

        // Load recipe flip groups
        List<RecipeFlipGroup> recipeFlipGroups = loadRecipeFlipGroups(accountId, displayName);
        data.getRecipeFlipGroups().addAll(recipeFlipGroups);

        // Recreate favorite-only items (favorited from search without ever trading them).
        // The JSON backend round-trips these through the trades list; without this they would
        // silently disappear (along with their favorite) on every SQLite reload.
        restoreFavoriteOnlyItems(displayName, data);
        restoreItemVisibility(accountId, displayName, data);

        return data;
    }

    /**
     * Adds a trade-less FlippingItem for every favorited item id that has no trade rows, so
     * favorited-but-never-traded items survive reloads in SQLite mode.
     */
    private void restoreFavoriteOnlyItems(String displayName, AccountData data) {
        Map<Integer, Map<String, Object>> favorites = loadAllFavorites(displayName);
        for (Map.Entry<Integer, Map<String, Object>> entry : favorites.entrySet()) {
            int itemId = entry.getKey();
            boolean isFavorite = entry.getValue().get("isFavorite") instanceof Boolean && (Boolean) entry.getValue().get("isFavorite");
            if (!isFavorite) {
                continue;
            }
            boolean hasTrades = data.getTrades().stream().anyMatch(item -> item.getItemId() == itemId);
            if (hasTrades) {
                continue;
            }
            FlippingItem item = new FlippingItem(itemId, "Item " + itemId, 70, displayName);
            item.setFavorite(true);
            Object favoriteCode = entry.getValue().get("favoriteCode");
            if (favoriteCode instanceof String) {
                item.setFavoriteCode((String) favoriteCode);
            }
            data.getTrades().add(item);
        }
    }

    /**
     * Load recipe flip groups from SQLite for an account.
     */
    private List<RecipeFlipGroup> loadRecipeFlipGroups(int accountId, String displayName) {
        List<RecipeFlipGroup> groups = new ArrayList<>();

        // Get all recipe flip events for this account, grouped by recipe_key
        String groupSql = "SELECT rf.recipe_key, rf.id as recipe_flip_id, rf.coin_cost, " +
            "e.id as event_id, e.timestamp, e.profit, e.cost " +
            "FROM recipe_flips rf " +
            "JOIN events e ON rf.event_id = e.id " +
            "WHERE e.account_id = ? " +
            "ORDER BY rf.recipe_key, e.timestamp";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(groupSql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, RecipeFlipGroup> groupMap = new HashMap<>();

                    while (rs.next()) {
                        String recipeKey = rs.getString("recipe_key");
                        long eventTimestamp = rs.getLong("timestamp");
                        long coinCost = rs.getLong("coin_cost");
                        long recipeFlipId = rs.getLong("recipe_flip_id");

                        // Get or create the group
                        RecipeFlipGroup group = groupMap.computeIfAbsent(recipeKey, RecipeFlipGroup::new);

                        // Load inputs and outputs for this recipe flip
                        Map<Integer, Map<String, PartialOffer>> inputs = loadRecipeFlipComponents(recipeFlipId, displayName, true);
                        Map<Integer, Map<String, PartialOffer>> outputs = loadRecipeFlipComponents(recipeFlipId, displayName, false);

                        // Create RecipeFlip
                        RecipeFlip flip = new RecipeFlip(
                            Instant.ofEpochMilli(eventTimestamp),
                            outputs,
                            inputs,
                            coinCost
                        );

                        group.getRecipeFlips().add(flip);
                    }

                    groups.addAll(groupMap.values());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading recipe flip groups", e);
        }

        return groups;
    }

    /** Recipe snapshots remain valid even after their source trade leaves item history. */
    private Map<Integer, Map<String, PartialOffer>> loadRecipeFlipComponents(long recipeFlipId,
                                                                           String displayName, boolean inputs) {
        Map<Integer, Map<String, PartialOffer>> components = new HashMap<>();
        String table = inputs ? "recipe_flip_inputs" : "recipe_flip_outputs";
        String sql = "SELECT item_id, offer_uuid, amount_consumed, offer_json FROM " + table + " WHERE recipe_flip_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setLong(1, recipeFlipId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    String uuid = rs.getString("offer_uuid");
                    OfferEvent offer = SLOT_GSON.fromJson(rs.getString("offer_json"), OfferEvent.class);
                    if (offer == null) {
                        throw new IllegalStateException("Missing recipe offer snapshot for " + uuid);
                    }
                    offer.setMadeBy(displayName);
                    offer.setItemName("Item " + itemId);
                    PartialOffer component = new PartialOffer(offer, rs.getInt("amount_consumed"));
                    component.setOfferUuid(uuid);
                    components.computeIfAbsent(itemId, ignored -> new HashMap<>()).put(uuid, component);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading recipe components", e);
        }
        return components;
    }

    /**
     * Load trade items from SQLite, reconstructing FlippingItem objects with history.
     */
    private List<FlippingItem> loadTradeItems(int accountId, String displayName, Map<Integer, OfferEvent> slots) {
        Map<Integer, List<OfferEvent>> offersByItem = new HashMap<>();

        // Load all trades for this account, grouped by item. NOTE: qty-0 rows (trades fully
        // consumed by recipe flips) are included on purpose — they are legitimate history and
        // recipe PartialOffers hydrate from them. Excluding them here made reloadFromSqlite
        // shrink the in-memory history, and the next storeData() then overwrote the JSON with
        // the degraded state, permanently destroying those offers in both backends.
        String sql = "SELECT item_id, offer_json FROM trades WHERE account_id = ? ORDER BY item_id, timestamp, id";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int itemId = rs.getInt("item_id");
                        OfferEvent offer = SLOT_GSON.fromJson(rs.getString("offer_json"), OfferEvent.class);
                        offer.setMadeBy(displayName);
                        offersByItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(offer);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading trades", e);
        }

        // Active fills belong to history as well as the slot map. Preserve their slot and
        // incomplete state so HistoryManager replaces them when the trade next updates.
        for (OfferEvent partial : slots.values()) {
            if (partial.getCurrentQuantityInTrade() <= 0) {
                continue;
            }
            List<OfferEvent> offers = offersByItem.computeIfAbsent(partial.getItemId(), ignored -> new ArrayList<>());
            if (partial.getUuid() == null || offers.stream().noneMatch(offer -> partial.getUuid().equals(offer.getUuid()))) {
                partial.setMadeBy(displayName);
                offers.add(partial);
            }
        }

        // Convert to FlippingItem objects, restoring GE limit state where present
        Map<Integer, Map<String, Object>> geLimitStates = loadAllGeLimitStates(displayName);
        Map<Integer, Map<String, Object>> favorites = loadAllFavorites(displayName);
        List<FlippingItem> items = new ArrayList<>();
        for (Map.Entry<Integer, List<OfferEvent>> entry : offersByItem.entrySet()) {
            int itemId = entry.getKey();
            List<OfferEvent> offers = entry.getValue();
            offers.sort(Comparator.comparing(OfferEvent::getTime, Comparator.nullsFirst(Comparator.naturalOrder())));

            FlippingItem item = new FlippingItem(itemId, "Item " + itemId, 70, displayName);
            Map<String, Object> geState = geLimitStates.get(itemId);
            if (geState != null) {
                item.getHistory().setNextGeLimitRefresh((Instant) geState.get("nextRefresh"));
                item.getHistory().setItemsBoughtThisLimitWindow(geState.get("itemsBought") instanceof Integer ? (Integer) geState.get("itemsBought") : 0);
                // Restore the complete-offer base too: it is what the window is recalculated
                // from when the next partial buy arrives (buy 100, restart, buy 10 -> 110,
                // not 10).
                Object throughComplete = geState.get("itemsBoughtThroughCompleteOffers");
                item.getHistory().setItemsBoughtThroughCompleteOffers(throughComplete instanceof Integer ? (Integer) throughComplete : 0);
            }
            // Restore persisted favorite state (M4: favorites round-trip across reloads)
            Map<String, Object> fav = favorites.get(itemId);
            if (fav != null) {
                Object isFavorite = fav.get("isFavorite");
                if (isFavorite instanceof Boolean && (Boolean) isFavorite) {
                    item.setFavorite(true);
                }
                Object favoriteCode = fav.get("favoriteCode");
                if (favoriteCode instanceof String) {
                    item.setFavoriteCode((String) favoriteCode);
                }
            }
            item.getHistory().getCompressedOfferEvents().addAll(offers);
            items.add(item);
        }

        return items;
    }

    public synchronized void upsertItemVisibility(String displayName, int itemId, boolean visible) {
        int accountId = getOrCreateAccountId(displayName);
        String sql = "INSERT OR REPLACE INTO item_visibility (account_id, item_id, is_visible) VALUES (?, ?, ?)";
        try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, itemId);
            statement.setBoolean(3, visible);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not persist item visibility for " + displayName, e);
        }
    }

    private void restoreItemVisibility(int accountId, String displayName, AccountData data) {
        Map<Integer, FlippingItem> items = new HashMap<>();
        for (FlippingItem item : data.getTrades()) {
            items.put(item.getItemId(), item);
        }
        String sql = "SELECT item_id, is_visible FROM item_visibility WHERE account_id = ?";
        try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
            statement.setInt(1, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int itemId = rows.getInt("item_id");
                    FlippingItem item = items.get(itemId);
                    if (item == null) {
                        item = new FlippingItem(itemId, "Item " + itemId, 70, displayName);
                        data.getTrades().add(item);
                        items.put(itemId, item);
                    }
                    item.setValidFlippingPanelItem(rows.getBoolean("is_visible"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load item visibility for " + displayName, e);
        }
    }

    /**
     * Load GE limit state for every item of an account in one query.
     * @return Map of itemId -> {nextRefresh (Instant, nullable), itemsBought (int)}
     */
    public synchronized Map<Integer, Map<String, Object>> loadAllGeLimitStates(String displayName) {
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return result;
        }

        final String sql = "SELECT item_id, next_refresh, items_bought, items_bought_complete FROM ge_limit_state WHERE account_id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> state = new HashMap<>(4);
                        long nextRefreshMillis = rs.getLong("next_refresh");
                        state.put("nextRefresh", rs.wasNull() ? null : Instant.ofEpochMilli(nextRefreshMillis));
                        state.put("itemsBought", rs.getInt("items_bought"));
                        state.put("itemsBoughtThroughCompleteOffers", rs.getInt("items_bought_complete"));
                        result.put(rs.getInt("item_id"), state);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading GE limit states", e);
        }
        return result;
    }

    /**
     * List all account display names.
     * @return List of display names sorted alphabetically
     */
    public synchronized List<String> listAccounts() {
        final String sql = "SELECT display_name FROM accounts ORDER BY display_name";
        List<String> names = new ArrayList<>();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error listing accounts", e);
        }

        return names;
    }
    /** Records the original offer, preserving classification and continuity across reloads. */
    public synchronized void recordTrade(String displayName, OfferEvent offer) {
        int accountId = getOrCreateAccountId(displayName);
        long tax = offer.isBuy() || offer.getTime() == null ? 0L
            : (long) offer.getTaxPaidPerItem() * offer.getCurrentQuantityInTrade();
        String sql = "INSERT OR IGNORE INTO trades " +
            "(account_id, item_id, uuid, timestamp, qty, price, is_buy, tax, offer_json) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = getConnection().prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, offer.getItemId());
            statement.setString(3, offer.getUuid());
            statement.setLong(4, offer.getTime() == null ? 0L : offer.getTime().toEpochMilli());
            statement.setInt(5, offer.getCurrentQuantityInTrade());
            statement.setInt(6, offer.getPreTaxPrice());
            statement.setInt(7, offer.isBuy() ? 1 : 0);
            statement.setLong(8, tax);
            statement.setString(9, serializeOffer(offer));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record trade for " + displayName, e);
        }
    }

    /**
     * Upsert an active slot with an offer event.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     * @param offer The offer event to store (cleared if null/complete)
     */
    public synchronized void upsertSlot(String displayName, int slotIndex, OfferEvent offer, boolean historyVisible) {
        if (offer == null || offer.isComplete() || offer.isCausedByEmptySlot()) {
            clearSlot(displayName, slotIndex);
            return;
        }
        int accountId = getOrCreateAccountId(displayName);

        final String sql = "INSERT OR REPLACE INTO active_slots (" +
            "id, account_id, slot_index, offer_uuid, item_id, is_buy, price, qty, total_qty, state, time, trade_started_at, offer_json, history_visible" +
            ") VALUES ((SELECT id FROM active_slots WHERE account_id = ? AND slot_index = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setInt(2, slotIndex);
            ps.setInt(3, accountId);
            ps.setInt(4, slotIndex);
            ps.setString(5, offer.getUuid());
            ps.setInt(6, offer.getItemId());
            ps.setBoolean(7, offer.isBuy());
            ps.setInt(8, offer.getPreTaxPrice());
            ps.setInt(9, offer.getCurrentQuantityInTrade());
            ps.setInt(10, offer.getTotalQuantityInTrade());
            ps.setString(11, offer.getState().name());
            ps.setObject(12, offer.getTime() == null ? null : offer.getTime().toEpochMilli());
            ps.setObject(13, offer.getTradeStartedAt() == null ? null : offer.getTradeStartedAt().toEpochMilli());
            ps.setString(14, serializeOffer(offer));
            ps.setBoolean(15, historyVisible);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not persist active slot for " + displayName, e);
        }
    }

    /**
     * Load all active slots for an account.
     * @param displayName Account display name
     * @return Map of slot index to OfferEvent (empty slots excluded)
     */
    public synchronized Map<Integer, OfferEvent> loadAllSlots(String displayName) {
        return loadAllSlots(displayName, null);
    }

    private Map<Integer, OfferEvent> loadAllSlots(String displayName, Map<Integer, OfferEvent> partialHistory) {
        Integer accountId = getAccountId(displayName);
        Map<Integer, OfferEvent> slots = new HashMap<>();
        if (accountId == null) {
            return slots;
        }

        final String sql = "SELECT slot_index, offer_json, history_visible " +
            "FROM active_slots WHERE account_id = ?";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int idx = rs.getInt("slot_index");
                        if (rs.wasNull()) {
                            continue;
                        }

                        OfferEvent offer = SLOT_GSON.fromJson(rs.getString("offer_json"), OfferEvent.class);
                        offer.setMadeBy(displayName);

                        if (!offer.isComplete() && !offer.isCausedByEmptySlot()) {
                            slots.put(idx, offer);
                            if (partialHistory != null && rs.getBoolean("history_visible")) {
                                partialHistory.put(idx, offer);
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading all active slots", e);
        }

        return slots;
    }

    /**
     * Clear (delete) a slot's active offer.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     */
    public synchronized void clearSlot(String displayName, int slotIndex) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return;
        }

        final String sql = "DELETE FROM active_slots WHERE account_id = ? AND slot_index = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, slotIndex);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error clearing active slot", e);
        }
    }

    /**
     * Upsert GE limit state for an item.
     * @param displayName Account display name
     * @param itemId Item ID
     * @param nextRefresh Time when GE limit resets
     * @param itemsBought Number of items bought this limit window
     */
    public synchronized void upsertGeLimitState(String displayName, int itemId, Instant nextRefresh, int itemsBought, int itemsBoughtThroughCompleteOffers) {
        int accountId = getOrCreateAccountId(displayName);

        final String sql = "INSERT OR REPLACE INTO ge_limit_state (" +
                "id, account_id, item_id, next_refresh, items_bought, items_bought_complete" +
                ") VALUES (" +
                "(SELECT id FROM ge_limit_state WHERE account_id = ? AND item_id = ?)," +
                " ?, ?, ?, ?, ?" +
                ")";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // Bind: 2 for subquery, then id, account_id, item_id, next_refresh, items_bought, items_bought_complete
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                ps.setInt(3, accountId);
                ps.setInt(4, itemId);
                if (nextRefresh != null) {
                    ps.setLong(5, nextRefresh.toEpochMilli());
                } else {
                    ps.setNull(5, Types.INTEGER);
                }
                ps.setInt(6, itemsBought);
                ps.setInt(7, itemsBoughtThroughCompleteOffers);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error upserting ge_limit_state", e);
        }
    }

/**
     * Load GE limit state for an item.
     * @param displayName Account display name
     * @param itemId Item ID
     * @return Map with nextRefresh and itemsBought, or null if not found
     */
    public synchronized Map<String, Object> loadGeLimitState(String displayName, int itemId) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return null;
        }

        final String sql = "SELECT next_refresh, items_bought, items_bought_complete FROM ge_limit_state WHERE account_id = ? AND item_id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    long nextRefreshMillis = rs.getLong("next_refresh");
                    int itemsBought = rs.getInt("items_bought");
                    Map<String, Object> result = new HashMap<>();
                    result.put("nextRefresh", Instant.ofEpochMilli(nextRefreshMillis));
                    result.put("itemsBought", itemsBought);
                    result.put("itemsBoughtThroughCompleteOffers", rs.getInt("items_bought_complete"));
                    return result;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading ge_limit_state", e);
        }
    }

    private int getOrCreateAccountId(String displayName) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            upsertAccount(displayName, null);
            accountId = getAccountId(displayName);
        }
        if (accountId == null) {
            throw new IllegalStateException("Could not create SQLite account " + displayName);
        }
        return accountId;
    }

    synchronized Integer getAccountId(String displayName) {
        Integer cached = accountIdCache.get(displayName);
        if (cached != null) {
            return cached;
        }
        final String sql = "SELECT id FROM accounts WHERE display_name = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, displayName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Integer id = rs.getInt("id");
                        accountIdCache.put(displayName, id);
                        return id;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error fetching account id", e);
        }
        return null;
    }

    // --- Session time persistence ---

    /**
     * Update accumulated session time for an account.
     * @param displayName Account display name
     * @param accumulatedTimeMillis Total accumulated session time in milliseconds
     */
    public synchronized void updateAccountSessionTime(String displayName, long accumulatedTimeMillis) {
        int accountId = getOrCreateAccountId(displayName);

        String sql = "UPDATE accounts SET accumulated_time = ? WHERE id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, accumulatedTimeMillis);
                ps.setInt(2, accountId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error updating session time", e);
        }
    }

    // --- Favorites CRUD ---

    /**
     * Upsert a favorite status for an item under an account.
     * @param displayName Account display name
     * @param itemId Item ID
     * @param isFavorite Whether the item is favorited
     * @param favoriteCode Quick search code
     */
    public synchronized void upsertFavorite(String displayName, int itemId, boolean isFavorite, String favoriteCode) {
        int accountId = getOrCreateAccountId(displayName);

        String sql = "INSERT OR REPLACE INTO item_favorites (account_id, item_id, is_favorite, favorite_code) VALUES (?, ?, ?, ?)";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                ps.setInt(3, isFavorite ? 1 : 0);
                ps.setString(4, favoriteCode != null ? favoriteCode : "1");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error upserting favorite", e);
        }
    }

    /**
     * Load favorite status for a specific item.
     * @return Map with "isFavorite" (boolean) and "favoriteCode" (String), or null
     */
    public synchronized Map<String, Object> loadFavorite(String displayName, int itemId) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) return null;

        String sql = "SELECT is_favorite, favorite_code FROM item_favorites WHERE account_id = ? AND item_id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> result = new HashMap<>(2);
                        result.put("isFavorite", rs.getInt("is_favorite") == 1);
                        result.put("favoriteCode", rs.getString("favorite_code"));
                        return result;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading favorite", e);
        }
        return null;
    }

    /**
     * Load all favorites for an account.
     * @return Map of itemId -> Map with "isFavorite" and "favoriteCode"
     */
    public synchronized Map<Integer, Map<String, Object>> loadAllFavorites(String displayName) {
        Integer accountId = getAccountId(displayName);
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        if (accountId == null) return result;

        String sql = "SELECT item_id, is_favorite, favorite_code FROM item_favorites WHERE account_id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int itemId = rs.getInt("item_id");
                        Map<String, Object> fav = new HashMap<>(2);
                        fav.put("isFavorite", rs.getInt("is_favorite") == 1);
                        fav.put("favoriteCode", rs.getString("favorite_code"));
                        result.put(itemId, fav);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading all favorites", e);
        }
        return result;
    }

    // --- Deletion paths (SQLite mode must support the same deletions as the JSON mode) ---

    /**
     * Delete ALL data for an account (trades, events, consumption, recipe flips, slots,
     * favorites, GE limit state, session time, and the migrated_ flag). Mirrors
     * TradePersister.deleteFile for the JSON backend.
     */
    public synchronized void deleteAccountData(String displayName) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        try {
            Connection conn = getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                execDelete(conn, "DELETE FROM recipe_flip_inputs WHERE recipe_flip_id IN " +
                    "(SELECT rf.id FROM recipe_flips rf JOIN events e ON rf.event_id = e.id WHERE e.account_id = ?)", accountId);
                execDelete(conn, "DELETE FROM recipe_flip_outputs WHERE recipe_flip_id IN " +
                    "(SELECT rf.id FROM recipe_flips rf JOIN events e ON rf.event_id = e.id WHERE e.account_id = ?)", accountId);
                execDelete(conn, "DELETE FROM recipe_flips WHERE event_id IN (SELECT id FROM events WHERE account_id = ?)", accountId);
                execDelete(conn, "DELETE FROM consumed_trade WHERE event_id IN (SELECT id FROM events WHERE account_id = ?)", accountId);
                execDelete(conn, "DELETE FROM consumed_trade WHERE trade_id IN (SELECT id FROM trades WHERE account_id = ?)", accountId);
                execDelete(conn, "DELETE FROM events WHERE account_id = ?", accountId);
                execDelete(conn, "DELETE FROM trades WHERE account_id = ?", accountId);
                execDelete(conn, "DELETE FROM ge_limit_state WHERE account_id = ?", accountId);
                execDelete(conn, "DELETE FROM active_slots WHERE account_id = ?", accountId);
                execDelete(conn, "DELETE FROM item_favorites WHERE account_id = ?", accountId);
                execDelete(conn, "DELETE FROM item_visibility WHERE account_id = ?", accountId);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM settings WHERE key = ?")) {
                    ps.setString(1, "migrated_" + displayName);
                    ps.executeUpdate();
                }
                execDelete(conn, "DELETE FROM accounts WHERE id = ?", accountId);
                conn.commit();
                accountIdCache.remove(displayName);
                logger.info("Deleted all SQLite data for account {}", displayName);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error deleting account data", e);
        }
    }

    /**
     * Delete specific trades by their offer uuid (the per-item offer-deletion flow), together
     * with the events they back and their consumption rows.
     */
    public synchronized void deleteTradesByUuid(String displayName, List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return;
        }
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        try {
            Connection conn = getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                List<Long> tradeIds = new ArrayList<>();
                for (int i = 0; i < uuids.size(); i += 500) {
                    List<String> chunk = uuids.subList(i, Math.min(i + 500, uuids.size()));
                    String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
                    // A deleted partial fill still belongs to the live slot for offer
                    // continuity, but must not reappear in history after restarting.
                    try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE active_slots SET history_visible = 0 WHERE account_id = ? AND offer_uuid IN (" + placeholders + ")")) {
                        ps.setInt(1, accountId);
                        int idx = 2;
                        for (String uuid : chunk) {
                            ps.setString(idx++, uuid);
                        }
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM trades WHERE account_id = ? AND uuid IN (" + placeholders + ")")) {
                        ps.setInt(1, accountId);
                        int idx = 2;
                        for (String uuid : chunk) {
                            ps.setString(idx++, uuid);
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                tradeIds.add(rs.getLong(1));
                            }
                        }
                    }
                }
                if (tradeIds.isEmpty()) {
                    conn.commit();
                    return;
                }

                List<Long> eventIds = new ArrayList<>();
                for (int i = 0; i < tradeIds.size(); i += 500) {
                    List<Long> chunk = tradeIds.subList(i, Math.min(i + 500, tradeIds.size()));
                    String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
                    try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT DISTINCT event_id FROM consumed_trade WHERE event_id IS NOT NULL AND trade_id IN (" + placeholders + ")")) {
                        int idx = 1;
                        for (Long tradeId : chunk) {
                            ps.setLong(idx++, tradeId);
                        }
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                eventIds.add(rs.getLong(1));
                            }
                        }
                    }
                }
                if (!eventIds.isEmpty()) {
                    deleteEventsById(conn, eventIds);
                }

                for (int i = 0; i < tradeIds.size(); i += 500) {
                    List<Long> chunk = tradeIds.subList(i, Math.min(i + 500, tradeIds.size()));
                    String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
                    execDeleteByLongs(conn, "DELETE FROM consumed_trade WHERE trade_id IN (" + placeholders + ")", chunk);
                    execDeleteByLongs(conn, "DELETE FROM trades WHERE id IN (" + placeholders + ")", chunk);
                }
                conn.commit();
                logger.info("Deleted {} SQLite trades by uuid for {}", tradeIds.size(), displayName);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error deleting trades by uuid", e);
        }
    }

    /**
     * Delete a single recipe flip (the per-flip delete button in the recipe panel) together
     * with its components and consumption rows. Identified by the same natural key that both
     * the migration and insertRecipeFlip use, so migrated and live-created flips are covered.
     */
    public synchronized void deleteRecipeFlip(String displayName, String recipeKey, Instant timeOfCreation) {
        if (recipeKey == null || timeOfCreation == null) {
            return;
        }
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        String naturalKey = "recipe:" + accountId + ":" + recipeKey + ":" + timeOfCreation.toEpochMilli();
        try {
            Connection conn = getConnection();
            Long eventId = null;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM events WHERE account_id = ? AND natural_key = ?")) {
                ps.setInt(1, accountId);
                ps.setString(2, naturalKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        eventId = rs.getLong(1);
                    }
                }
            }
            if (eventId == null) {
                return;
            }
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                deleteEventsById(conn, Collections.singletonList(eventId));
                conn.commit();
                logger.info("Deleted SQLite recipe flip {} for {}", naturalKey, displayName);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error deleting recipe flip ", e);
        }
    }

    /**
     * Delete a single group's recipe flips created after a timestamp (the interval-reset flow
     * on a recipe group panel). Scoped to the recipe key: deleting one group must not touch
     * other groups' flips in the same interval. Strictly after, mirroring
     * RecipeFlipGroup.deleteFlips' isAfter check.
     */
    public synchronized void deleteRecipeFlipsSince(String displayName, String recipeKey, Instant since) {
        if (recipeKey == null) {
            return;
        }
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        long sinceMillis = since != null ? since.toEpochMilli() : 0L;
        try {
            Connection conn = getConnection();
            List<Long> eventIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT e.id FROM events e JOIN recipe_flips rf ON rf.event_id = e.id " +
                "WHERE e.account_id = ? AND e.type = 'recipe' AND rf.recipe_key = ? AND e.timestamp > ?")) {
                ps.setInt(1, accountId);
                ps.setString(2, recipeKey);
                ps.setLong(3, sinceMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        eventIds.add(rs.getLong(1));
                    }
                }
            }
            if (eventIds.isEmpty()) {
                return;
            }
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                deleteEventsById(conn, eventIds);
                conn.commit();
                logger.info("Deleted {} SQLite recipe flips for {} [{}] since {}", eventIds.size(), displayName, recipeKey, since);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error deleting recipe flips since ", e);
        }
    }

    /**
     * Delete events plus everything hanging off them (recipe components, consumption rows).
     */
    private void deleteEventsById(Connection conn, List<Long> eventIds) throws SQLException {
        for (int i = 0; i < eventIds.size(); i += 500) {
            List<Long> chunk = eventIds.subList(i, Math.min(i + 500, eventIds.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            execDeleteByLongs(conn, "DELETE FROM recipe_flip_inputs WHERE recipe_flip_id IN " +
                "(SELECT id FROM recipe_flips WHERE event_id IN (" + placeholders + "))", chunk);
            execDeleteByLongs(conn, "DELETE FROM recipe_flip_outputs WHERE recipe_flip_id IN " +
                "(SELECT id FROM recipe_flips WHERE event_id IN (" + placeholders + "))", chunk);
            execDeleteByLongs(conn, "DELETE FROM recipe_flips WHERE event_id IN (" + placeholders + ")", chunk);
            execDeleteByLongs(conn, "DELETE FROM consumed_trade WHERE event_id IN (" + placeholders + ")", chunk);
            execDeleteByLongs(conn, "DELETE FROM events WHERE id IN (" + placeholders + ")", chunk);
        }
    }

    private void execDelete(Connection conn, String sql, int accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }
    }

    private void execDeleteByLongs(Connection conn, String sql, List<Long> ids) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Long id : ids) {
                ps.setLong(idx++, id);
            }
            ps.executeUpdate();
        }
    }

    // --- Live recipe flip persistence ---

    /**
     * Persist a recipe flip created at runtime (so it survives restarts in SQLite mode).
     * Idempotent via the same natural-key scheme the migration uses.
     */
    public synchronized void insertRecipeFlip(String displayName, String recipeKey, RecipeFlip flip) {
        if (flip == null || flip.getTimeOfCreation() == null) {
            return;
        }
        int accountId = getOrCreateAccountId(displayName);
        long timestamp = flip.getTimeOfCreation().toEpochMilli();
        String naturalKey = "recipe:" + accountId + ":" + recipeKey + ":" + timestamp;
        try {
            Connection conn = getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                long eventId;
                // Detect the OR IGNORE skip via the update count; getGeneratedKeys() after an
                // ignored insert returns a stale rowid.
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO events (account_id, timestamp, type, cost, profit, note, natural_key) VALUES (?, ?, 'recipe', ?, ?, NULL, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, accountId);
                    ps.setLong(2, timestamp);
                    ps.setLong(3, flip.getExpense());
                    ps.setLong(4, flip.getProfit());
                    ps.setString(5, naturalKey);
                    if (ps.executeUpdate() == 0) {
                        conn.commit();
                        return; // already persisted (e.g. re-run)
                    }
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) {
                            conn.commit();
                            return;
                        }
                        eventId = rs.getLong(1);
                    }
                }

                long recipeFlipId;
                try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO recipe_flips (event_id, recipe_key, coin_cost) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, eventId);
                    ps.setString(2, recipeKey);
                    ps.setLong(3, flip.getCoinCost());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) {
                            conn.commit();
                            return;
                        }
                        recipeFlipId = rs.getLong(1);
                    }
                }

                insertRecipeFlipComponents(conn, accountId, eventId, recipeFlipId, flip.getInputs(), true);
                insertRecipeFlipComponents(conn, accountId, eventId, recipeFlipId, flip.getOutputs(), false);
                conn.commit();
                logger.debug("Persisted recipe flip to SQLite for account={}, recipeKey={}", displayName, recipeKey);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error persisting recipe flip", e);
        }
    }

    private void insertRecipeFlipComponents(Connection conn, int accountId, long eventId, long recipeFlipId,
                                            Map<Integer, Map<String, PartialOffer>> components, boolean isInput) throws SQLException {
        if (components == null) {
            return;
        }
        String componentSql = isInput
            ? "INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed, offer_json) VALUES (?, ?, ?, ?, ?)"
            : "INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed, offer_json) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement componentPs = conn.prepareStatement(componentSql);
             // Clamps consumption to the trade's remaining quantity so recipe data referencing
             // more than the trade holds (legacy/duplicate records) cannot over-consume it.
             PreparedStatement consumedPs = conn.prepareStatement(
                 "INSERT OR IGNORE INTO consumed_trade (trade_id, event_id, qty) " +
                 "SELECT ?, ?, MIN(?, t.qty - COALESCE((SELECT SUM(qty) FROM consumed_trade ct2 WHERE ct2.trade_id = t.id), 0)) " +
                 "FROM trades t " +
                 "WHERE t.id = ? " +
                 "AND t.qty - COALESCE((SELECT SUM(qty) FROM consumed_trade ct2 WHERE ct2.trade_id = t.id), 0) > 0")) {
            for (Map.Entry<Integer, Map<String, PartialOffer>> entry : components.entrySet()) {
                int itemId = entry.getKey();
                for (PartialOffer po : entry.getValue().values()) {
                    if (po == null || po.getAmountConsumed() <= 0) {
                        continue;
                    }
                    componentPs.setLong(1, recipeFlipId);
                    componentPs.setInt(2, itemId);
                    if (po.getOfferUuid() == null) {
                        componentPs.setNull(3, Types.VARCHAR);
                    } else {
                        componentPs.setString(3, po.getOfferUuid());
                    }
                    componentPs.setInt(4, po.getAmountConsumed());
                    componentPs.setString(5, serializeRecipeOffer(po));
                    componentPs.executeUpdate();

                    if (po.getOfferUuid() != null) {
                        try (PreparedStatement lookup = conn.prepareStatement("SELECT id FROM trades WHERE account_id = ? AND uuid = ?")) {
                            lookup.setInt(1, accountId);
                            lookup.setString(2, po.getOfferUuid());
                            try (ResultSet rs = lookup.executeQuery()) {
                                if (rs.next()) {
                                    long tradeId = rs.getLong(1);
                                    consumedPs.setLong(1, tradeId);
                                    consumedPs.setLong(2, eventId);
                                    consumedPs.setInt(3, po.getAmountConsumed());
                                    consumedPs.setLong(4, tradeId);
                                    consumedPs.executeUpdate();
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
