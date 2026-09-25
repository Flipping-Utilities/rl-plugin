package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.Flip;
import com.flippingutilities.model.HistoryManager;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.utilities.TaxCalculator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.runelite.api.GrandExchangeOfferState;

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
                    logger.error("Schema init failed while reading setting '{}'", key, initFailure);
                    return null;
                }
                return getSetting(key);
            }
            logger.error("Error reading setting '{}'", key, e);
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
                    logger.error("Schema init failed while setting settings key {}", key, initFailure);
                    return;
                }
                setSetting(key, value);
            } else {
                logger.error("Error setting settings key {}", key, e);
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
                    logger.error("Schema init failed while clearing setting for key {}", key, initFailure);
                    return;
                }
                clearSetting(key);
            } else {
                logger.error("Error clearing setting for key {}", key, e);
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

            if (currentVersion == 0) {
                // Fresh database: create all tables and indexes
                List<String> creates = SqliteSchema.getCreateStatementsInOrder();
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : creates) {
                        stmt.execute(sql);
                    }
                }
                List<String> indexes = SqliteSchema.getIndexStatements();
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : indexes) {
                        stmt.execute(sql);
                    }
                }
                // Stamp the version (idempotent DDL above, so a crash before this just re-runs it)
                String migration = SqliteSchema.getMigrationStatement();
                if (migration != null && !migration.trim().isEmpty()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(migration);
                    }
                }
            } else if (currentVersion < SqliteSchema.SCHEMA_VERSION) {
                // Incremental migration. Run inside a transaction so a partial upgrade rolls back.
                // Foreign keys are disabled during the migration because the rebuild steps
                // (ALTER TABLE ... RENAME / DROP TABLE) trip FK checks while child rows exist
                // (PRAGMA foreign_keys is a no-op inside a transaction, so it must be toggled
                // outside of it), and re-enabled + verified afterwards.
                List<String> migrationStmts = SqliteSchema.getMigrationStatements(currentVersion);
                // Bump the version inside the same transaction as the DDL so a crash between
                // the two can't leave the DB migrated-but-unversioned (and re-run is safe anyway
                // thanks to IF NOT EXISTS / INSERT-SELECT rebuilds).
                String versionStmt = SqliteSchema.getMigrationStatement();
                if (versionStmt != null && !versionStmt.trim().isEmpty()) {
                    migrationStmts = new ArrayList<>(migrationStmts);
                    migrationStmts.add(versionStmt);
                }
                try (Statement pragma = conn.createStatement()) {
                    pragma.execute("PRAGMA foreign_keys=OFF;");
                }
                boolean wasAutoCommit = conn.getAutoCommit();
                try {
                    conn.setAutoCommit(false);
                    try {
                        try (Statement stmt = conn.createStatement()) {
                            for (String sql : migrationStmts) {
                                stmt.execute(sql);
                            }
                        }
                        conn.commit();
                    } catch (SQLException ex) {
                        conn.rollback();
                        throw ex;
                    } finally {
                        conn.setAutoCommit(wasAutoCommit);
                    }
                    // Post-migration integrity check: report any FK violations the rebuild
                    // may have introduced (e.g. consumed_trade rows referencing dropped
                    // duplicate trades), then re-enable enforcement.
                    try (Statement check = conn.createStatement();
                         ResultSet rs = check.executeQuery("PRAGMA foreign_key_check;")) {
                        if (rs.next()) {
                            logger.error("Foreign key violations after schema migration (first: table={}, rowid={})",
                                rs.getString(1), rs.getLong(2));
                        }
                    }
                } finally {
                    try (Statement pragma = conn.createStatement()) {
                        pragma.execute("PRAGMA foreign_keys=ON;");
                    } catch (SQLException e) {
                        logger.warn("Could not re-enable foreign keys after migration", e);
                    }
                }
            }
        } catch (SQLException e) {
            // Rethrow instead of swallowing: callers (notably FlippingPlugin.initializeRepository)
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
            logger.error("Error upserting account for displayName={}", displayName, e);
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
            logger.error("Error loading session info for displayName={}", displayName, e);
        }

        // Load trades and convert to FlippingItem objects
        List<FlippingItem> tradeItems = loadTradeItems(accountId, displayName);
        data.getTrades().addAll(tradeItems);

        // Load recipe flip groups
        List<RecipeFlipGroup> recipeFlipGroups = loadRecipeFlipGroups(accountId);
        data.getRecipeFlipGroups().addAll(recipeFlipGroups);

        // Restore in-progress offers so offer continuity works across restarts in SQLite mode
        // (matches what the JSON path stores in AccountData.lastOffers).
        data.getLastOffers().putAll(loadAllSlots(displayName));

        // Recreate favorite-only items (favorited from search without ever trading them).
        // The JSON backend round-trips these through the trades list; without this they would
        // silently disappear (along with their favorite) on every SQLite reload.
        restoreFavoriteOnlyItems(displayName, data);

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
    private List<RecipeFlipGroup> loadRecipeFlipGroups(int accountId) {
        List<RecipeFlipGroup> groups = new ArrayList<>();

        // Query per-group stats from events table
        Map<String, long[]> groupStats = queryPerRecipeStats(accountId);

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
                        RecipeFlipGroup group = groupMap.computeIfAbsent(recipeKey, k -> {
                            RecipeFlipGroup g = new RecipeFlipGroup();
                            g.setRecipeKey(k);
                            // Set cached stats from events table
                            long[] stats = groupStats.get(k);
                            if (stats != null) {
                                g.setCachedTotalProfit(stats[0]);
                                g.setCachedTotalExpense(stats[1]);
                                g.setCachedFlipCount((int) stats[2]);
                                g.setHasCachedStats(true);
                            }
                            return g;
                        });

                        // Load inputs and outputs for this recipe flip
                        Map<Integer, Map<String, PartialOffer>> inputs = loadRecipeFlipInputs(recipeFlipId);
                        Map<Integer, Map<String, PartialOffer>> outputs = loadRecipeFlipOutputs(recipeFlipId);

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
            logger.error("Error loading recipe flip groups for accountId={}", accountId, e);
        }

        return groups;
    }

    /**
     * Query per-recipe-group stats from the events table.
     * Returns a map of recipe_key -> [totalProfit, totalExpense, flipCount].
     */
    private Map<String, long[]> queryPerRecipeStats(int accountId) {
        Map<String, long[]> stats = new HashMap<>();
        String sql = "SELECT rf.recipe_key, COALESCE(SUM(e.profit), 0) as total_profit, " +
            "COALESCE(SUM(e.cost), 0) as total_cost, COUNT(*) as flip_count " +
            "FROM events e JOIN recipe_flips rf ON rf.event_id = e.id " +
            "WHERE e.account_id = ? AND e.type = 'recipe' " +
            "GROUP BY rf.recipe_key";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString("recipe_key");
                        long profit = rs.getLong("total_profit");
                        long cost = rs.getLong("total_cost");
                        long count = rs.getLong("flip_count");
                        stats.put(key, new long[]{profit, cost, count});
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error querying per-recipe stats for accountId={}", accountId, e);
        }
        return stats;
    }

    /**
     * Load inputs for a recipe flip.
     * Queries price from the trades table using the offer UUID.
     */
    private Map<Integer, Map<String, PartialOffer>> loadRecipeFlipInputs(long recipeFlipId) {
        Map<Integer, Map<String, PartialOffer>> inputs = new HashMap<>();

        String sql = "SELECT rfi.item_id, rfi.offer_uuid, rfi.amount_consumed, t.price, t.timestamp, t.qty AS trade_qty " +
            "FROM recipe_flip_inputs rfi " +
            "LEFT JOIN trades t ON t.uuid = rfi.offer_uuid " +
            "WHERE rfi.recipe_flip_id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, recipeFlipId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int itemId = rs.getInt("item_id");
                        String offerUuid = rs.getString("offer_uuid");
                        int amountConsumed = rs.getInt("amount_consumed");
                        int price = rs.getInt("price");
                        long tradeTimestamp = rs.getLong("timestamp");
                        // The backing offer must carry the ORIGINAL trade quantity (not the
                        // consumed amount): remaining-history is displayed as offerQty -
                        // amountConsumed, so a qty of amountConsumed always shows 0 remaining.
                        // Trades store original quantities; consumption lives in consumed_trade.
                        int tradeQty = rs.getInt("trade_qty");
                        int stubQty = rs.wasNull() ? amountConsumed : tradeQty;

                        OfferEvent stubOffer = createStubOfferEvent(offerUuid, itemId, true, stubQty, price, tradeTimestamp);
                        PartialOffer po = new PartialOffer(stubOffer, amountConsumed);
                        inputs.computeIfAbsent(itemId, k -> new HashMap<>()).put(offerUuid, po);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading recipe flip inputs for recipeFlipId={}", recipeFlipId, e);
        }

        return inputs;
    }

    /**
     * Load outputs for a recipe flip.
     * Queries price from the trades table using the offer UUID.
     */
    private Map<Integer, Map<String, PartialOffer>> loadRecipeFlipOutputs(long recipeFlipId) {
        Map<Integer, Map<String, PartialOffer>> outputs = new HashMap<>();

        String sql = "SELECT rfo.item_id, rfo.offer_uuid, rfo.amount_consumed, t.price, t.timestamp, t.qty AS trade_qty " +
            "FROM recipe_flip_outputs rfo " +
            "LEFT JOIN trades t ON t.uuid = rfo.offer_uuid " +
            "WHERE rfo.recipe_flip_id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, recipeFlipId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int itemId = rs.getInt("item_id");
                        String offerUuid = rs.getString("offer_uuid");
                        int amountConsumed = rs.getInt("amount_consumed");
                        int price = rs.getInt("price");
                        long tradeTimestamp = rs.getLong("timestamp");
                        // See loadRecipeFlipInputs: the stub must carry the original trade
                        // quantity so remaining = tradeQty - amountConsumed is correct.
                        int tradeQty = rs.getInt("trade_qty");
                        int stubQty = rs.wasNull() ? amountConsumed : tradeQty;

                        OfferEvent stubOffer = createStubOfferEvent(offerUuid, itemId, false, stubQty, price, tradeTimestamp);
                        PartialOffer po = new PartialOffer(stubOffer, amountConsumed);
                        outputs.computeIfAbsent(itemId, k -> new HashMap<>()).put(offerUuid, po);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading recipe flip outputs for recipeFlipId={}", recipeFlipId, e);
        }

        return outputs;
    }

    /**
     * Create a stub OfferEvent for recipe flip display purposes.
     * This is used when loading recipe flips from SQLite where we don't have the full offer data.
     */
    private OfferEvent createStubOfferEvent(String uuid, int itemId, boolean isBuy, int qty, int price, long timestamp) {
        return new OfferEvent(
            uuid,
            isBuy,
            itemId,
            qty,
            price,
            Instant.ofEpochMilli(timestamp),
            0,
            isBuy ? net.runelite.api.GrandExchangeOfferState.BOUGHT : net.runelite.api.GrandExchangeOfferState.SOLD,
            0, 0, qty, null, false, null, "Item " + itemId, price, price * qty
        );
    }

    /**
     * Load trade items from SQLite, reconstructing FlippingItem objects with history.
     */
    private List<FlippingItem> loadTradeItems(int accountId, String displayName) {
        Map<Integer, List<OfferEvent>> offersByItem = new HashMap<>();

        // Load all trades for this account, grouped by item. NOTE: qty-0 rows (trades fully
        // consumed by recipe flips) are included on purpose — they are legitimate history and
        // recipe PartialOffers hydrate from them. Excluding them here made reloadFromSqlite
        // shrink the in-memory history, and the next storeData() then overwrote the JSON with
        // the degraded state, permanently destroying those offers in both backends.
        String sql = "SELECT uuid, item_id, timestamp, qty, price, is_buy FROM trades WHERE account_id = ? ORDER BY item_id, timestamp";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String uuid = rs.getString("uuid");
                        int itemId = rs.getInt("item_id");
                        long timestamp = rs.getLong("timestamp");
                        int qty = rs.getInt("qty");
                        int price = rs.getInt("price");
                        boolean isBuy = rs.getInt("is_buy") == 1;

                        OfferEvent offer = createOfferEvent(uuid, itemId, timestamp, qty, price, isBuy, displayName);
                        offersByItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(offer);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading trades for accountId={}", accountId, e);
        }

        // Convert to FlippingItem objects, restoring GE limit state where present
        Map<Integer, Map<String, Object>> geLimitStates = loadAllGeLimitStates(displayName);
        Map<Integer, Map<String, Object>> favorites = loadAllFavorites(displayName);
        List<FlippingItem> items = new ArrayList<>();
        for (Map.Entry<Integer, List<OfferEvent>> entry : offersByItem.entrySet()) {
            int itemId = entry.getKey();
            List<OfferEvent> offers = entry.getValue();

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
            logger.error("Error loading GE limit states for displayName={}", displayName, e);
        }
        return result;
    }

    /**
     * Create an OfferEvent from trade record data.
     */
    private OfferEvent createOfferEvent(String uuid, int itemId, long timestamp, int qty, int price, boolean isBuy, String displayName) {
        return new OfferEvent(
            uuid,
            isBuy,
            itemId,
            qty,
            price,
            Instant.ofEpochMilli(timestamp),
            0,
            isBuy ? net.runelite.api.GrandExchangeOfferState.BOUGHT : net.runelite.api.GrandExchangeOfferState.SOLD,
            0, 100, qty, null, false, displayName, "Item " + itemId, price, price * qty
        );
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
            logger.error("Error listing accounts", e);
        }

        return names;
    }
/**
     * Insert a trade record.
     * Returns the generated trade ID, or -1 if account not found.
     * @param displayName Account display name
     * @param itemId Item ID
     * @param timestamp Trade timestamp (epoch millis)
     * @param qty Quantity
     * @param price Price per item
     * @param isBuy True if buy, false if sell
     */
    public synchronized int insertTrade(String displayName, int itemId, long timestamp, int qty, int price, boolean isBuy) {
        return insertTrade(displayName, itemId, null, timestamp, qty, price, isBuy);
    }

    /**
     * Insert a single trade. The uuid is required to link recipe-flip consumption back to its trade row.
     */
    public synchronized int insertTrade(String displayName, int itemId, String uuid, long timestamp, int qty, int price, boolean isBuy) {
        return insertTrade(displayName, itemId, uuid, timestamp, qty, price, isBuy, -1L);
    }

    /**
     * Insert a single trade with an explicit tax amount. Uses INSERT OR IGNORE against
     * UNIQUE(account_id, uuid) so re-recording the same offer is a no-op instead of a duplicate.
     *
     * @param taxPaid total tax paid on the trade as recorded by the caller, or -1 to compute
     *                from the item, price, and timestamp
     * @return the new trade id, or -1 if the insert was ignored (duplicate account_id+uuid)
     * @throws IllegalStateException on database failure so transient errors are visible to
     *         callers instead of being conflated with the duplicate case
     */
    public synchronized int insertTrade(String displayName, int itemId, String uuid, long timestamp, int qty, int price, boolean isBuy, long taxPaid) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Trade insert: account not found for displayName={}", displayName);
            return -1;
        }

        long tax = isBuy ? 0 : (taxPaid >= 0 ? taxPaid : TaxCalculator.computeTax(itemId, timestamp, qty, price));
        final String sql = "INSERT OR IGNORE INTO trades (account_id, item_id, uuid, timestamp, qty, price, is_buy, tax) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                if (uuid == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, uuid);
                }
                ps.setLong(4, timestamp);
                ps.setInt(5, qty);
                ps.setInt(6, price);
                ps.setInt(7, isBuy ? 1 : 0);
                ps.setLong(8, tax);
                int updated = ps.executeUpdate();
                if (updated == 0) {
                    // OR IGNORE skipped a duplicate (account_id, uuid); don't consult
                    // getGeneratedKeys() as it would return a stale rowid.
                    logger.debug("Trade insert ignored (duplicate uuid) for displayName={}, uuid={}", displayName, uuid);
                    return -1;
                }
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error inserting trade for displayName={}, itemId={}", displayName, itemId, e);
            throw new IllegalStateException("Failed to insert trade for " + displayName, e);
        }
        return -1;
    }

    /**
     * Incrementally reconciles flip events for a single (account, item) after a new trade is
     * recorded. Loads all trades for the item with remaining (unconsumed) quantity, runs
     * {@link HistoryManager#getFlips} on them, and inserts any new flip events + consumed_trade
     * rows that don't already exist (idempotent via natural_key).
     *
     * <p>This is what makes live-traded items show profit immediately without a full re-sync.
     * Partial fills are handled naturally: each partial sell that matches against an existing
     * unconsumed buy emits its own flip event for the matched quantity.
     *
     * @param accountId the account ID
     * @param itemId the item ID
     */
    public synchronized void reconcileFlipsForItem(int accountId, int itemId) {
        // 1. Load unconsumed trades (remaining_qty > 0) for this item, ordered by timestamp.
        List<long[]> tradeRows = new ArrayList<>(); // each: {tradeId, timestamp, remainingQty, price, isBuy(0/1)}
        String sql =
            "SELECT t.id, t.timestamp, t.qty - COALESCE(c.consumed_qty, 0) AS remaining_qty, t.price, t.is_buy " +
            "FROM trades t " +
            "LEFT JOIN (SELECT trade_id, SUM(qty) AS consumed_qty FROM consumed_trade GROUP BY trade_id) c " +
            "  ON c.trade_id = t.id " +
            "WHERE t.account_id = ? AND t.item_id = ? AND t.qty > 0 " +
            "ORDER BY t.timestamp, t.id";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long tradeId = rs.getLong("id");
                        long timestamp = rs.getLong("timestamp");
                        int remainingQty = rs.getInt("remaining_qty");
                        int price = rs.getInt("price");
                        int isBuy = rs.getInt("is_buy");
                        if (remainingQty > 0) {
                            tradeRows.add(new long[]{tradeId, timestamp, remainingQty, price, isBuy});
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading unconsumed trades for reconcile acct={}, item={}", accountId, itemId, e);
            return;
        }

        if (tradeRows.isEmpty()) {
            return;
        }

        // 2. Build OfferEvents from the unconsumed trade rows. setMadeBy is required because
        //    HistoryManager.getFlips groups by madeBy (null keys would NPE).
        String accountName = getAccountName(accountId);
        List<OfferEvent> offers = new ArrayList<>();
        for (long[] row : tradeRows) {
            int remainingQty = (int) row[2];
            int price = (int) row[3];
            boolean isBuy = row[4] == 1;
            OfferEvent oe = createStubOfferEvent(null, itemId, isBuy, remainingQty, price, row[1]);
            oe.setMadeBy(accountName != null ? accountName : "reconcile");
            offers.add(oe);
        }

        // 3. Run the same flip-matching algorithm the migration and JSON path use.
        List<Flip> flips = HistoryManager.getFlips(offers);
        if (flips == null || flips.isEmpty()) {
            return;
        }

        // 4. FIFO-match each flip against the trade rows and insert new events + consumed_trade.
        insertReconciledFlips(accountId, itemId, tradeRows, flips);
    }

    /**
     * Resolves an account display name from account_id (inverse of getAccountId).
     */
    private String getAccountName(int accountId) {
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement("SELECT display_name FROM accounts WHERE id = ?")) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("display_name");
                    }
                }
            }
        } catch (SQLException e) {
            logger.debug("Could not resolve account name for id={}", accountId);
        }
        return null;
    }

    /**
     * FIFO-matches flips against trade rows and inserts new events + consumed_trade rows.
     * Idempotent: events with an existing natural_key are skipped (INSERT OR IGNORE).
     */
    private void insertReconciledFlips(int accountId, int itemId, List<long[]> tradeRows, List<Flip> flips) {
        // Build FIFO queues keyed by side. Each entry: {tradeId, remainingQty}.
        // These are mutable — consumption shrinks remainingQty as flips are processed.
        LinkedList<long[]> buyQueue = new LinkedList<>();
        LinkedList<long[]> sellQueue = new LinkedList<>();
        for (long[] row : tradeRows) {
            long[] entry = new long[]{row[0], row[2]}; // {tradeId, remainingQty}
            if (row[4] == 1) { // isBuy
                buyQueue.add(entry);
            } else {
                sellQueue.add(entry);
            }
        }

        String eventSql = "INSERT OR IGNORE INTO events (account_id, timestamp, type, cost, profit, note, natural_key) " +
            "VALUES (?, ?, 'flip', ?, ?, ?, ?)";
        String consumedSql = "INSERT OR IGNORE INTO consumed_trade (trade_id, event_id, qty) VALUES (?, ?, ?)";

        try {
            Connection conn = getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement eventPs = conn.prepareStatement(eventSql, Statement.RETURN_GENERATED_KEYS);
                     PreparedStatement consumedPs = conn.prepareStatement(consumedSql)) {

                    for (Flip flip : flips) {
                        if (flip == null || flip.getTime() == null) continue;

                        int flipQty = flip.getQuantity();
                        int buyPrice = flip.getBuyPrice();
                        int sellPrice = flip.getSellPrice();
                        long timestamp = flip.getTime().toEpochMilli();
                        String note = flip.isMarginCheck() ? "margin_check" : null;
                        // The first buy/sell trade this flip will consume is part of the key so
                        // that two identical same-millisecond partial fills backed by different
                        // trades produce distinct events (and aren't silently deduplicated).
                        long firstBuyTradeId = buyQueue.isEmpty() ? -1 : buyQueue.peekFirst()[0];
                        long firstSellTradeId = sellQueue.isEmpty() ? -1 : sellQueue.peekFirst()[0];
                        String naturalKey = "flip:" + accountId + ":" + itemId + ":" + timestamp + ":" + buyPrice + ":" + sellPrice + ":" + flipQty
                            + ":" + firstBuyTradeId + ":" + firstSellTradeId;

                        long cost = (long) buyPrice * flipQty;
                        long profit = (long) (sellPrice - buyPrice) * flipQty;

                        eventPs.setInt(1, accountId);
                        eventPs.setLong(2, timestamp);
                        eventPs.setLong(3, cost);
                        eventPs.setLong(4, profit);
                        if (note == null) {
                            eventPs.setNull(5, Types.VARCHAR);
                        } else {
                            eventPs.setString(5, note);
                        }
                        eventPs.setString(6, naturalKey);
                        // Detect the OR IGNORE skip via the update count, NOT getGeneratedKeys():
                        // sqlite-jdbc's getGeneratedKeys() runs SELECT last_insert_rowid() after
                        // any INSERT-pattern SQL and returns a row even when nothing was inserted,
                        // so the ignored case would surface a stale rowid from an unrelated table.
                        int eventUpdated = eventPs.executeUpdate();

                        long eventId;
                        if (eventUpdated == 0) {
                            // OR IGNORE skipped (natural_key already exists from a prior reconcile).
                            // Still need to advance the FIFO queues by the flip's qty so
                            // subsequent flips match correctly.
                            advanceQueue(buyQueue, flipQty);
                            advanceQueue(sellQueue, flipQty);
                            continue;
                        }
                        try (ResultSet rs = eventPs.getGeneratedKeys()) {
                            if (!rs.next()) {
                                advanceQueue(buyQueue, flipQty);
                                advanceQueue(sellQueue, flipQty);
                                continue;
                            }
                            eventId = rs.getLong(1);
                        }

                        // Link consumed buy and sell trades to this event.
                        consumeFromQueue(consumedPs, buyQueue, flipQty, eventId);
                        consumeFromQueue(consumedPs, sellQueue, flipQty, eventId);
                    }

                    consumedPs.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            logger.error("Error inserting reconciled flips for acct={}, item={}", accountId, itemId, e);
        }
    }

    /**
     * Consumes qty from the front of the queue, inserting consumed_trade rows.
     */
    private void consumeFromQueue(PreparedStatement consumedPs, LinkedList<long[]> queue, int qtyNeeded, long eventId) throws SQLException {
        int remaining = qtyNeeded;
        while (remaining > 0 && !queue.isEmpty()) {
            long[] front = queue.peekFirst();
            int available = (int) front[1];
            if (available <= 0) {
                queue.pollFirst();
                continue;
            }
            int consume = Math.min(remaining, available);
            consumedPs.setLong(1, front[0]); // tradeId
            consumedPs.setLong(2, eventId);
            consumedPs.setInt(3, consume);
            consumedPs.addBatch();

            front[1] = available - consume;
            remaining -= consume;
            if (front[1] <= 0) {
                queue.pollFirst();
            }
        }
    }

    /**
     * Advances the queue by qtyNeeded without inserting consumed_trade rows (used when an event
     * was skipped by INSERT OR IGNORE but we still need to keep the FIFO positions consistent).
     */
    private void advanceQueue(LinkedList<long[]> queue, int qtyNeeded) {
        int remaining = qtyNeeded;
        while (remaining > 0 && !queue.isEmpty()) {
            long[] front = queue.peekFirst();
            int available = (int) front[1];
            if (available <= 0) {
                queue.pollFirst();
                continue;
            }
            int consume = Math.min(remaining, available);
            front[1] = available - consume;
            remaining -= consume;
            if (front[1] <= 0) {
                queue.pollFirst();
            }
        }
    }


/**
     * Load all trades for an account since a timestamp.
     * @param displayName Account display name
     * @param since Only include trades after this time (null = all)
     * @return List of trade maps with id, itemId, timestamp, qty, price, isBuy
     */
    public synchronized List<Map<String, Object>> loadTrades(String displayName, Instant since) {
        Integer accountId = getAccountId(displayName);
        List<Map<String, Object>> results = new ArrayList<>();
        if (accountId == null) {
            return results;
        }

        final String sql = "SELECT id, item_id, timestamp, qty, price, is_buy " +
            "FROM trades " +
            "WHERE account_id = ? AND timestamp > ? AND qty > 0 " +
            "ORDER BY timestamp, id";

        long sinceMillis = since == null ? 0L : since.toEpochMilli();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setLong(2, sinceMillis);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>(6);
                        row.put("id", rs.getLong("id"));
                        row.put("itemId", rs.getInt("item_id"));
                        row.put("timestamp", rs.getLong("timestamp"));
                        row.put("qty", rs.getInt("qty"));
                        row.put("price", rs.getInt("price"));
                        row.put("isBuy", rs.getInt("is_buy"));
                        results.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading trades for displayName={}", displayName, e);
        }

        return results;
    }
/**
     * Load trades for a specific item.
     * @param displayName Account display name
     * @param itemId Item ID to filter
     * @param since Only include trades after this time (null = all)
     * @return List of trade maps
     */
    public synchronized List<Map<String, Object>> loadTradesByItem(String displayName, int itemId, Instant since) {
        Integer accountId = getAccountId(displayName);
        List<Map<String, Object>> results = new ArrayList<>();
        if (accountId == null) {
            return results;
        }

        final String sql = "SELECT id, item_id, timestamp, qty, price, is_buy " +
            "FROM trades " +
            "WHERE account_id = ? AND item_id = ? AND timestamp > ? " +
            "ORDER BY timestamp, id";

        long sinceMillis = since == null ? 0L : since.toEpochMilli();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                ps.setLong(3, sinceMillis);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>(6);
                        row.put("id", rs.getLong("id"));
                        row.put("itemId", rs.getInt("item_id"));
                        row.put("timestamp", rs.getLong("timestamp"));
                        row.put("qty", rs.getInt("qty"));
                        row.put("price", rs.getInt("price"));
                        row.put("isBuy", rs.getInt("is_buy"));
                        results.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading trades for displayName={}, itemId={}", displayName, itemId, e);
        }

        return results;
    }

    /**
     * Query items list with aggregated profit/ROI for UI display.
     * This replaces in-memory aggregation by computing stats in SQL.
     * 
     * @param displayName Account to query
     * @param since Only include trades after this timestamp
     * @param sortBy "TIME", "PROFIT", or "ROI"
     * @param limit Max results to return
     * @param offset Pagination offset
     * @return List of item maps with: itemId, totalQty, buyQty, sellQty, totalProfit, roi, latestTimestamp
     */
    public synchronized List<Map<String, Object>> queryItemsList(String displayName, Instant since, String sortBy, int limit, int offset) {
        Integer accountId = getAccountId(displayName);
        List<Map<String, Object>> results = new ArrayList<>();
        if (accountId == null) {
            return results;
        }

        // Map sortBy to actual column name
        String orderColumn = "latest_timestamp";
        if ("PROFIT".equalsIgnoreCase(sortBy)) {
            orderColumn = "total_profit";
        } else if ("ROI".equalsIgnoreCase(sortBy)) {
            orderColumn = "roi";
        }

        String sql = "WITH trade_remaining AS (" +
            "  SELECT " +
            "    t.item_id, " +
            "    t.is_buy, " +
            "    t.price, " +
            "    t.timestamp, " +
            "    MAX(t.qty - COALESCE(ct.consumed_qty, 0), 0) as remaining_qty " +
            "  FROM trades t " +
            "  LEFT JOIN (" +
            "    SELECT trade_id, SUM(qty) as consumed_qty " +
            "    FROM consumed_trade " +
            "    GROUP BY trade_id" +
            "  ) ct ON t.id = ct.trade_id " +
            "  WHERE t.account_id = ? AND t.timestamp > ?" +
            ") " +
            "SELECT " +
            "  item_id, " +
            "  SUM(remaining_qty) as total_qty, " +
            "  SUM(CASE WHEN is_buy = 1 THEN remaining_qty ELSE 0 END) as buy_qty, " +
            "  SUM(CASE WHEN is_buy = 0 THEN remaining_qty ELSE 0 END) as sell_qty, " +
            "  SUM(CASE WHEN is_buy = 0 THEN remaining_qty * price ELSE -remaining_qty * price END) as total_profit, " +
            "  CASE " +
            "    WHEN SUM(CASE WHEN is_buy = 1 THEN remaining_qty * price ELSE 0 END) > 0 " +
            "    THEN (SUM(CASE WHEN is_buy = 0 THEN remaining_qty * price ELSE -remaining_qty * price END) * 100.0 / " +
            "          SUM(CASE WHEN is_buy = 1 THEN remaining_qty * price ELSE 0 END)) " +
            "    ELSE 0 " +
            "  END as roi, " +
            "  MAX(timestamp) as latest_timestamp " +
            "FROM trade_remaining " +
            "WHERE remaining_qty > 0 " +
            "GROUP BY item_id " +
            "HAVING total_qty > 0 " +
            "ORDER BY " + orderColumn + " DESC " +
            "LIMIT ? OFFSET ?";

        long sinceMillis = since == null ? 0L : since.toEpochMilli();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setLong(2, sinceMillis);
                ps.setInt(3, limit);
                ps.setInt(4, offset);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        row.put("itemId", rs.getInt("item_id"));
                        row.put("totalQty", rs.getInt("total_qty"));
                        row.put("buyQty", rs.getInt("buy_qty"));
                        row.put("sellQty", rs.getInt("sell_qty"));
                        row.put("totalProfit", rs.getLong("total_profit"));
                        row.put("roi", rs.getDouble("roi"));
                        row.put("latestTimestamp", rs.getLong("latest_timestamp"));
                        results.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error querying items list for displayName={}", displayName, e);
        }

        return results;
    }
/**
     * Upsert an active slot with an offer event.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     * @param offer The offer event to store (cleared if null/complete)
     */
    public synchronized void upsertSlot(String displayName, int slotIndex, OfferEvent offer) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Slot upsert: account not found for displayName={}", displayName);
            return;
        }

        if (offer == null || offer.isComplete() || offer.isCausedByEmptySlot()) {
            clearSlot(displayName, slotIndex);
            return;
        }

        JsonObject json = SLOT_GSON.toJsonTree(offer).getAsJsonObject();
        String uuid = (json.has("uuid") && !json.get("uuid").isJsonNull()) ? json.get("uuid").getAsString() : null;
        int itemId = (json.has("id") && !json.get("id").isJsonNull()) ? json.get("id").getAsInt() : 0;
        boolean isBuy = (json.has("b") && !json.get("b").isJsonNull()) && json.get("b").getAsBoolean();
        int price = (json.has("p") && !json.get("p").isJsonNull()) ? json.get("p").getAsInt() : 0;
        int qty = (json.has("cQIT") && !json.get("cQIT").isJsonNull()) ? json.get("cQIT").getAsInt() : 0;
        int totalQty = (json.has("tQIT") && !json.get("tQIT").isJsonNull()) ? json.get("tQIT").getAsInt() : 0;
        String state = (json.has("st") && !json.get("st").isJsonNull()) ? json.get("st").getAsString() : null;
        Long timeMillis = (json.has("t") && !json.get("t").isJsonNull()) ? json.get("t").getAsLong() : null;
        Long tradeStartedAtMillis = (json.has("tradeStartedAt") && !json.get("tradeStartedAt").isJsonNull())
            ? json.get("tradeStartedAt").getAsLong() : null;

        final String sql = "INSERT OR REPLACE INTO active_slots (" +
            "id, account_id, slot_index, offer_uuid, item_id, is_buy, price, qty, total_qty, state, time, trade_started_at" +
            ") VALUES (" +
            "(SELECT id FROM active_slots WHERE account_id = ? AND slot_index = ?)," +
            " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?" +
            ")";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                ps.setInt(i++, accountId);
                ps.setInt(i++, slotIndex);
                ps.setInt(i++, accountId);
                ps.setInt(i++, slotIndex);
                if (uuid == null) {
                    ps.setNull(i++, Types.VARCHAR);
                } else {
                    ps.setString(i++, uuid);
                }
                ps.setInt(i++, itemId);
                ps.setInt(i++, isBuy ? 1 : 0);
                ps.setInt(i++, price);
                ps.setInt(i++, qty);
                ps.setInt(i++, totalQty);

                if (state == null || state.trim().isEmpty()) {
                    ps.setNull(i++, Types.VARCHAR);
                } else {
                    ps.setString(i++, state);
                }

                if (timeMillis == null) {
                    ps.setNull(i++, Types.BIGINT);
                } else {
                    ps.setLong(i++, timeMillis);
                }
                if (tradeStartedAtMillis == null) {
                    ps.setNull(i++, Types.BIGINT);
                } else {
                    ps.setLong(i++, tradeStartedAtMillis);
                }

                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error upserting active slot for displayName={}, slotIndex={}", displayName, slotIndex, e);
        }
    }
/**
     * Load an offer event from a specific slot.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     * @return OfferEvent or null if slot is empty
     */
    public synchronized OfferEvent loadSlot(String displayName, int slotIndex) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return null;
        }

        final String sql = "SELECT offer_uuid, item_id, is_buy, price, qty, total_qty, state, time, trade_started_at " +
            "FROM active_slots WHERE account_id = ? AND slot_index = ?";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, slotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }

                    String stateStr = rs.getString("state");
                    if (stateStr != null && !stateStr.trim().isEmpty()) {
                        try {
                            GrandExchangeOfferState.valueOf(stateStr);
                        } catch (IllegalArgumentException ex) {
                            logger.warn("Unknown offer state '{}' in active_slots for displayName={}, slotIndex={}", stateStr, displayName, slotIndex);
                            stateStr = GrandExchangeOfferState.EMPTY.name();
                        }
                    }

                    JsonObject json = new JsonObject();
                    String uuid = rs.getString("offer_uuid");
                    if (uuid == null) {
                        json.add("uuid", JsonNull.INSTANCE);
                    } else {
                        json.addProperty("uuid", uuid);
                    }
                    json.addProperty("b", rs.getInt("is_buy") == 1);
                    json.addProperty("id", rs.getInt("item_id"));
                    json.addProperty("cQIT", rs.getInt("qty"));
                    json.addProperty("tQIT", rs.getInt("total_qty"));
                    json.addProperty("p", rs.getInt("price"));
                    json.addProperty("s", slotIndex);
                    if (stateStr == null || stateStr.trim().isEmpty()) {
                        json.add("st", JsonNull.INSTANCE);
                    } else {
                        json.addProperty("st", stateStr);
                    }
                    long timeMillis = rs.getLong("time");
                    if (rs.wasNull() || timeMillis <= 0) {
                        json.add("t", JsonNull.INSTANCE);
                    } else {
                        json.addProperty("t", timeMillis);
                    }
                    long tradeStartMillis = rs.getLong("trade_started_at");
                    if (rs.wasNull() || tradeStartMillis <= 0) {
                        json.add("tradeStartedAt", JsonNull.INSTANCE);
                    } else {
                        json.addProperty("tradeStartedAt", tradeStartMillis);
                    }

                    OfferEvent offer = SLOT_GSON.fromJson(json, OfferEvent.class);

                    if (offer.isComplete() || offer.isCausedByEmptySlot()) {
                        return null;
                    }

                    return offer;
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading active slot for displayName={}, slotIndex={}", displayName, slotIndex, e);
            return null;
        }
    }
/**
     * Load all active slots for an account.
     * @param displayName Account display name
     * @return Map of slot index to OfferEvent (empty slots excluded)
     */
    public synchronized Map<Integer, OfferEvent> loadAllSlots(String displayName) {
        Integer accountId = getAccountId(displayName);
        Map<Integer, OfferEvent> slots = new HashMap<>();
        if (accountId == null) {
            return slots;
        }

        final String sql = "SELECT slot_index, offer_uuid, item_id, is_buy, price, qty, total_qty, state, time, trade_started_at " +
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

                        String stateStr = rs.getString("state");
                        if (stateStr != null && !stateStr.trim().isEmpty()) {
                            try {
                                GrandExchangeOfferState.valueOf(stateStr);
                            } catch (IllegalArgumentException ex) {
                                logger.warn("Unknown offer state '{}' in active_slots for displayName={}, slotIndex={}", stateStr, displayName, idx);
                                stateStr = GrandExchangeOfferState.EMPTY.name();
                            }
                        }

                        JsonObject json = new JsonObject();
                        String uuid = rs.getString("offer_uuid");
                        if (uuid == null) {
                            json.add("uuid", JsonNull.INSTANCE);
                        } else {
                            json.addProperty("uuid", uuid);
                        }
                        json.addProperty("b", rs.getInt("is_buy") == 1);
                        json.addProperty("id", rs.getInt("item_id"));
                        json.addProperty("cQIT", rs.getInt("qty"));
                        json.addProperty("tQIT", rs.getInt("total_qty"));
                        json.addProperty("p", rs.getInt("price"));
                        json.addProperty("s", idx);
                        if (stateStr == null || stateStr.trim().isEmpty()) {
                            json.add("st", JsonNull.INSTANCE);
                        } else {
                            json.addProperty("st", stateStr);
                        }
                        long timeMillis = rs.getLong("time");
                        if (rs.wasNull() || timeMillis <= 0) {
                            json.add("t", JsonNull.INSTANCE);
                        } else {
                            json.addProperty("t", timeMillis);
                        }
                        long tradeStartMillis = rs.getLong("trade_started_at");
                        if (rs.wasNull() || tradeStartMillis <= 0) {
                            json.add("tradeStartedAt", JsonNull.INSTANCE);
                        } else {
                            json.addProperty("tradeStartedAt", tradeStartMillis);
                        }

                        OfferEvent offer = SLOT_GSON.fromJson(json, OfferEvent.class);

                        if (!offer.isComplete() && !offer.isCausedByEmptySlot()) {
                            slots.put(idx, offer);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading all active slots for displayName={}", displayName, e);
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
            logger.error("Error clearing active slot for displayName={}, slotIndex={}", displayName, slotIndex, e);
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
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("GE limit state upsert: account not found for displayName={}", displayName);
            return;
        }

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
            logger.error("Error upserting ge_limit_state for displayName={}, itemId={}", displayName, itemId, e);
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
            logger.error("Error loading ge_limit_state for displayName={}" , displayName, e);
            return null;
        }
    }

    /**
     * Query aggregate statistics for an account.
     * Computes total profit, expense, revenue, flip count, and tax in a single query.
     * 
     * @param displayName Account to query
     * @param since Only include trades after this timestamp
     * @return Map with: totalProfit, totalExpense, totalRevenue, flipCount, taxPaid
     */
    public synchronized Map<String, Object> queryAggregateStats(String displayName, Instant since) {
        Integer accountId = getAccountId(displayName);
        Map<String, Object> result = new HashMap<>();
        
        // Default values
        result.put("totalProfit", 0L);
        result.put("totalExpense", 0L);
        result.put("totalRevenue", 0L);
        result.put("flipCount", 0);
        result.put("taxPaid", 0L);
        
        if (accountId == null) {
            return result;
        }

        // Aggregate trade stats using a CTE to pre-aggregate consumed_trade per trade first.
        // Without the CTE, a trade consumed by multiple events appears N times in the SUM
        // and silently multiplies expense/revenue/profit. The CTE produces one row per trade,
        // so a plain SUM across all trades is correct (no GROUP BY needed).
        // Tax is read directly from the trades.tax column (populated by insertTradesForAccount
        // and insertTrade) rather than estimated. MAX(x, 0) is SQLite's scalar max, clamping
        // remaining_qty to non-negative.
        String sql = "WITH consumed AS (" +
            "  SELECT trade_id, SUM(qty) AS consumed_qty FROM consumed_trade GROUP BY trade_id" +
            ") " +
            "SELECT " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 1 THEN MAX(t.qty - COALESCE(c.consumed_qty, 0), 0) * t.price ELSE 0 END), 0) AS total_expense, " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 0 THEN MAX(t.qty - COALESCE(c.consumed_qty, 0), 0) * t.price ELSE 0 END), 0) AS total_revenue, " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 0 THEN MAX(t.qty - COALESCE(c.consumed_qty, 0), 0) * t.price ELSE -MAX(t.qty - COALESCE(c.consumed_qty, 0), 0) * t.price END), 0) AS total_profit, " +
            "COALESCE(SUM(t.tax), 0) AS total_tax, " +
            // NOTE: this counts trade rows, not buy+sell flip pairs. Kept under flip_count for
            // backwards-compatibility with the Map key contract; callers that need real flip
            // counts should use SqliteFlipRepository.getAggregateStats (counts events).
            "COUNT(DISTINCT t.id) AS flip_count " +
            "FROM trades t " +
            "LEFT JOIN consumed c ON t.id = c.trade_id " +
            "WHERE t.account_id = ? AND t.timestamp > ?";

        long sinceMillis = since == null ? 0L : since.toEpochMilli();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setLong(2, sinceMillis);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        result.put("totalExpense", rs.getLong("total_expense"));
                        result.put("totalRevenue", rs.getLong("total_revenue"));
                        result.put("totalProfit", rs.getLong("total_profit"));
                        result.put("flipCount", rs.getInt("flip_count"));
                        // Sum the real per-trade tax paid (sell-side only, recorded at insert time).
                        result.put("taxPaid", rs.getLong("total_tax"));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error querying aggregate stats for displayName={}", displayName, e);
        }

        return result;
    }

// Internal helper: find account_id from display_name
    synchronized Integer getAccountId(String displayName) { // package-private for repository access
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
            logger.error("Error fetching account id for displayName={}", displayName, e);
        }
        return null;
    }

// Slot timer CRUD methods
    /**
     * Upsert a slot timer with last activity time.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     * @param lastActivity Time of last activity
     */
    public synchronized void upsertSlotTimer(String displayName, int slotIndex, Instant lastActivity) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Slot timer upsert: account not found for displayName={}", displayName);
            return;
        }
        final String sql = "INSERT OR REPLACE INTO slot_timers (" +
                "id, account_id, slot_index, last_activity" +
                ") VALUES (" +
                "(SELECT id FROM slot_timers WHERE account_id = ? AND slot_index = ?), " +
                " ?, ?, ?" +
                ")";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int i = 1;
                ps.setInt(i++, accountId);
                ps.setInt(i++, slotIndex);
                ps.setInt(i++, accountId);
                ps.setInt(i++, slotIndex);
                ps.setLong(i++, lastActivity.toEpochMilli());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error upserting slot timer for displayName={}, slotIndex={}", displayName, slotIndex, e);
        }
    }
/**
     * Load last activity time for a slot.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     * @return Last activity time or null
     */
    public synchronized Instant loadSlotTimer(String displayName, int slotIndex) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return null;
        }
        final String sql = "SELECT last_activity FROM slot_timers WHERE account_id = ? AND slot_index = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, slotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    long last = rs.getLong("last_activity");
                    if (rs.wasNull()) {
                        return null;
                    }
                    return Instant.ofEpochMilli(last);
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading slot timer for displayName={}, slotIndex={}", displayName, slotIndex, e);
            return null;
        }
    }
/**
     * Load all slot timers for an account.
     * @param displayName Account display name
     * @return Map of slot index to last activity time (null for inactive slots)
     */
    public synchronized Map<Integer, Instant> loadAllSlotTimers(String displayName) {
        Integer accountId = getAccountId(displayName);
        Map<Integer, Instant> timers = new HashMap<>();
        for (int i = 0; i < 8; i++) timers.put(i, null);
        if (accountId == null) {
            return timers;
        }
        final String sql = "SELECT slot_index, last_activity FROM slot_timers WHERE account_id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int idx = rs.getInt("slot_index");
                        long lastMillis = rs.getLong("last_activity");
                        Instant instant = rs.wasNull() ? null : Instant.ofEpochMilli(lastMillis);
                        timers.put(idx, instant);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading all slot timers for displayName={}", displayName, e);
        }
        return timers;
    }
/**
     * Insert an event record (flip, recipe, or void).
     * @param displayName Account display name
     * @param type Event type: "flip", "recipe", or "void"
     * @param cost Total cost in coins
     * @param profit Profit in coins
     * @param note Optional note (can be null)
     * @return Generated event ID, or -1 on failure
     */
    public synchronized int insertEvent(String displayName, String type, int cost, int profit, String note) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Event insert: account not found for displayName={}", displayName);
            return -1;
        }

        if (!"flip".equals(type) && !"recipe".equals(type) && !"void".equals(type)) {
            logger.warn("Event insert: invalid type='{}' for displayName={}", type, displayName);
            return -1;
        }

        final String sql = "INSERT INTO events (account_id, timestamp, type, cost, profit, note) VALUES (?, ?, ?, ?, ?, ?)";
        long nowMillis = Instant.now().toEpochMilli();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, accountId);
                ps.setLong(2, nowMillis);
                ps.setString(3, type);
                ps.setInt(4, cost);
                ps.setInt(5, profit);
                if (note == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, note);
                }

                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error inserting event for displayName={}, type={}", displayName, type, e);
        }

        return -1;
    }

    /**
     * Insert an event record with a specific timestamp (for migration).
     * @param displayName Account display name
     * @param type Event type: "flip", "recipe", or "void"
     * @param timestamp Event timestamp in epoch milliseconds
     * @param cost Total cost in coins
     * @param profit Profit in coins
     * @param note Optional note (can be null)
     * @return Generated event ID, or -1 on failure
     */
    public synchronized int insertEventWithTimestamp(String displayName, String type, long timestamp, int cost, int profit, String note) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Event insert: account not found for displayName={}", displayName);
            return -1;
        }

        if (!"flip".equals(type) && !"recipe".equals(type) && !"void".equals(type)) {
            logger.warn("Event insert: invalid type='{}' for displayName={}", type, displayName);
            return -1;
        }

        final String sql = "INSERT INTO events (account_id, timestamp, type, cost, profit, note) VALUES (?, ?, ?, ?, ?, ?)";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, accountId);
                ps.setLong(2, timestamp);
                ps.setString(3, type);
                ps.setInt(4, cost);
                ps.setInt(5, profit);
                if (note == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, note);
                }

                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error inserting event for displayName={}, type={}", displayName, type, e);
        }

        return -1;
    }

    /**
     * Load events for an account since a timestamp.
     * @param displayName Account display name
     * @param since Only include events after this time (null = all)
     * @return List of event maps with id, timestamp, type, cost, profit, note
     */
    public synchronized List<Map<String, Object>> loadEvents(String displayName, Instant since) {
        Integer accountId = getAccountId(displayName);
        List<Map<String, Object>> results = new ArrayList<>();
        if (accountId == null) {
            return results;
        }

        final String sql = "SELECT id, timestamp, type, cost, profit, note " +
            "FROM events " +
            "WHERE account_id = ? AND timestamp > ? " +
            "ORDER BY timestamp, id";

        long sinceMillis = since == null ? 0L : since.toEpochMilli();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setLong(2, sinceMillis);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>(6);
                        row.put("id", rs.getLong("id"));
                        row.put("timestamp", rs.getLong("timestamp"));
                        row.put("type", rs.getString("type"));
                        row.put("cost", rs.getInt("cost"));
                        row.put("profit", rs.getInt("profit"));
                        row.put("note", rs.getString("note"));
                        results.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading events for displayName={}", displayName, e);
        }

        return results;
    }

    /**
     * Query recipe flip groups for UI display.
     * Groups recipe flip events by recipe_key with aggregated totals.
     *
     * @param displayName Account to query
     * @param since Only include events after this timestamp
     * @return List of group maps with: recipeKey, totalCount, totalProfit, totalCost, events
     */
    public synchronized List<Map<String, Object>> queryRecipeFlipGroups(String displayName, Instant since) {
        Integer accountId = getAccountId(displayName);
        List<Map<String, Object>> results = new ArrayList<>();
        if (accountId == null) {
            return results;
        }

        // First, get aggregated group stats
        String groupSql = "SELECT " +
            "rf.recipe_key, " +
            "COUNT(DISTINCT rf.id) as total_count, " +
            "SUM(rf.coin_cost) as total_cost, " +
            "SUM(e.profit) as total_profit " +
            "FROM recipe_flips rf " +
            "JOIN events e ON rf.event_id = e.id " +
            "WHERE e.account_id = ? AND e.timestamp > ? AND e.type = 'recipe' " +
            "GROUP BY rf.recipe_key " +
            "ORDER BY total_profit DESC";

        long sinceMillis = since == null ? 0L : since.toEpochMilli();

        try {
            Connection conn = getConnection();

            // Get groups
            Map<String, Map<String, Object>> groupMap = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(groupSql)) {
                ps.setInt(1, accountId);
                ps.setLong(2, sinceMillis);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> group = new HashMap<>();
                        String recipeKey = rs.getString("recipe_key");
                        group.put("recipeKey", recipeKey);
                        group.put("totalCount", rs.getInt("total_count"));
                        group.put("totalCost", rs.getLong("total_cost"));
                        group.put("totalProfit", rs.getLong("total_profit"));
                        group.put("events", new ArrayList<Map<String, Object>>());
                        groupMap.put(recipeKey, group);
                    }
                }
            }

            // Get individual events for each group
            String eventSql = "SELECT e.id, e.timestamp, e.profit, e.cost, rf.recipe_key " +
                "FROM events e " +
                "JOIN recipe_flips rf ON e.id = rf.event_id " +
                "WHERE e.account_id = ? AND e.timestamp > ? AND e.type = 'recipe' " +
                "ORDER BY e.timestamp DESC";

            try (PreparedStatement ps = conn.prepareStatement(eventSql)) {
                ps.setInt(1, accountId);
                ps.setLong(2, sinceMillis);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String recipeKey = rs.getString("recipe_key");
                        Map<String, Object> group = groupMap.get(recipeKey);
                        if (group != null) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> events = (List<Map<String, Object>>) group.get("events");
                            Map<String, Object> event = new HashMap<>();
                            event.put("id", rs.getLong("id"));
                            event.put("timestamp", rs.getLong("timestamp"));
                            event.put("profit", rs.getLong("profit"));
                            event.put("cost", rs.getLong("cost"));
                            events.add(event);
                        }
                    }
                }
            }

            results.addAll(groupMap.values());

        } catch (SQLException e) {
            logger.error("Error querying recipe flip groups for displayName={}", displayName, e);
        }

        return results;
    }
/**
     * Mark a trade as consumed by an event.
     * @param tradeId Trade ID to consume
     * @param qty Quantity consumed
     * @param eventId Event ID that consumed it (can be null for voided trades)
     */
    public synchronized void consumeTrade(int tradeId, int qty, Integer eventId) {
        final String sql = "INSERT OR REPLACE INTO consumed_trade (trade_id, event_id, qty) VALUES (?, ?, ?)";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, tradeId);
                if (eventId == null) {
                    ps.setNull(2, Types.INTEGER);
                } else {
                    ps.setInt(2, eventId);
                }
                ps.setInt(3, qty);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error consuming trade tradeId={}, qty={}, eventId={}", tradeId, qty, eventId, e);
        }
    }

    /**
     * Load unique items for an account with their latest trade data.
     * Used to reconstruct FlippingItem objects when loading from SQLite.
     * @param displayName Account display name
     * @return List of maps with itemId, latestTimestamp, totalQty, latestPrice, isBuy
     */
    public synchronized List<Map<String, Object>> loadUniqueItems(String displayName) {
        Integer accountId = getAccountId(displayName);
        List<Map<String, Object>> results = new ArrayList<>();
        if (accountId == null) {
            return results;
        }

        final String sql = "SELECT item_id, MAX(timestamp) as latest_timestamp, " +
            "SUM(qty) as total_qty, " +
            "SUM(CASE WHEN is_buy = 1 THEN qty ELSE 0 END) as total_buy_qty, " +
            "SUM(CASE WHEN is_buy = 0 THEN qty ELSE 0 END) as total_sell_qty " +
            "FROM trades " +
            "WHERE account_id = ? AND qty > 0 " +
            "GROUP BY item_id " +
            "ORDER BY latest_timestamp DESC";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>(5);
                        row.put("itemId", rs.getInt("item_id"));
                        row.put("latestTimestamp", rs.getLong("latest_timestamp"));
                        row.put("totalQty", rs.getInt("total_qty"));
                        row.put("totalBuyQty", rs.getInt("total_buy_qty"));
                        row.put("totalSellQty", rs.getInt("total_sell_qty"));
                        results.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading unique items for displayName={}", displayName, e);
        }

        return results;
    }

    // --- Session time persistence ---

    /**
     * Update accumulated session time for an account.
     * @param displayName Account display name
     * @param accumulatedTimeMillis Total accumulated session time in milliseconds
     */
    public synchronized void updateAccountSessionTime(String displayName, long accumulatedTimeMillis) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) return;

        String sql = "UPDATE accounts SET accumulated_time = ? WHERE id = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, accumulatedTimeMillis);
                ps.setInt(2, accountId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error updating session time for displayName={}", displayName, e);
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
        Integer accountId = getAccountId(displayName);
        if (accountId == null) return;

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
            logger.error("Error upserting favorite for displayName={}, itemId={}", displayName, itemId, e);
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
            logger.error("Error loading favorite for displayName={}, itemId={}", displayName, itemId, e);
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
            logger.error("Error loading all favorites for displayName={}", displayName, e);
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
                execDelete(conn, "DELETE FROM slot_timers WHERE account_id = ?", accountId);
                execDelete(conn, "DELETE FROM item_favorites WHERE account_id = ?", accountId);
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
            logger.error("Error deleting account data for displayName={}", displayName, e);
        }
    }

    /**
     * Delete all trades at/after a timestamp (the interval-reset flow in the stats panel),
     * together with the events those trades back (a flip needs both sides, so deleting one
     * side's trades invalidates the event) and their consumption rows.
     */
    public synchronized void deleteOffersSince(String displayName, Instant since) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        long sinceMillis = since != null ? since.toEpochMilli() : 0L;
        try {
            Connection conn = getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                List<Long> eventIds = selectEventIdsTouchingWindow(conn, accountId, sinceMillis);
                if (!eventIds.isEmpty()) {
                    deleteEventsById(conn, eventIds);
                }
                execDelete(conn, "DELETE FROM consumed_trade WHERE trade_id IN " +
                    "(SELECT id FROM trades WHERE account_id = ? AND timestamp >= ?)", accountId, sinceMillis);
                execDelete(conn, "DELETE FROM trades WHERE account_id = ? AND timestamp >= ?", accountId, sinceMillis);
                conn.commit();
                logger.info("Deleted SQLite offers for {} since {}", displayName, since);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            logger.error("Error deleting offers since {} for displayName={}", since, displayName, e);
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
            logger.error("Error deleting trades by uuid for displayName={}", displayName, e);
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
            logger.error("Error deleting recipe flip {} for displayName={}", naturalKey, displayName, e);
        }
    }

    /**
     * Delete all recipe flips created after a timestamp (the interval-reset flow on a recipe
     * group panel). Strictly after, mirroring RecipeFlipGroup.deleteFlips' isAfter check.
     */
    public synchronized void deleteRecipeFlipsSince(String displayName, Instant since) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        long sinceMillis = since != null ? since.toEpochMilli() : 0L;
        try {
            Connection conn = getConnection();
            List<Long> eventIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM events WHERE account_id = ? AND type = 'recipe' AND timestamp > ?")) {
                ps.setInt(1, accountId);
                ps.setLong(2, sinceMillis);
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
                logger.info("Deleted {} SQLite recipe flips for {} since {}", eventIds.size(), displayName, since);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            logger.error("Error deleting recipe flips since {} for displayName={}", since, displayName, e);
        }
    }

    /**
     * Event ids that either fall in the window or consume trades in the window.
     */
    private List<Long> selectEventIdsTouchingWindow(Connection conn, int accountId, long sinceMillis) throws SQLException {
        List<Long> eventIds = new ArrayList<>();
        String sql = "SELECT DISTINCT e.id FROM events e " +
            "LEFT JOIN consumed_trade ct ON ct.event_id = e.id " +
            "LEFT JOIN trades t ON t.id = ct.trade_id " +
            "WHERE e.account_id = ? AND (e.timestamp >= ? OR t.timestamp >= ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setLong(2, sinceMillis);
            ps.setLong(3, sinceMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    eventIds.add(rs.getLong(1));
                }
            }
        }
        return eventIds;
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

    private void execDelete(Connection conn, String sql, int accountId, long sinceMillis) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setLong(2, sinceMillis);
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
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Recipe flip insert: account not found for displayName={}", displayName);
            return;
        }
        long timestamp = flip.getTimeOfCreation().toEpochMilli();
        String naturalKey = "recipe:" + accountId + ":" + recipeKey + ":" + timestamp;
        try {
            Connection conn = getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                long eventId;
                // Detect the OR IGNORE skip via the update count; getGeneratedKeys() after an
                // ignored insert returns a stale rowid (same hazard as insertTrade/reconcile).
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
            logger.error("Error persisting recipe flip for displayName={}, recipeKey={}", displayName, recipeKey, e);
        }
    }

    private void insertRecipeFlipComponents(Connection conn, int accountId, long eventId, long recipeFlipId,
                                            Map<Integer, Map<String, PartialOffer>> components, boolean isInput) throws SQLException {
        if (components == null) {
            return;
        }
        String componentSql = isInput
            ? "INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)"
            : "INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
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
