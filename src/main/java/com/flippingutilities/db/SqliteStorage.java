package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.runelite.api.GrandExchangeOfferState;

/**
 * SQLite storage with connection management and schema initialization.
 * Wave 1: skeleton only. CRUD methods will be added in Wave 2.
 */
public class SqliteStorage {
    private static final Logger logger = LoggerFactory.getLogger(SqliteStorage.class);

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

    /**
     * Creates a storage bound to the given database file.
     *
     * @param dbFile Database file location
     */
    public SqliteStorage(File dbFile) {
        this.dbFile = dbFile;
    }

    /**
     * Returns a valid JDBC Connection to the SQLite database.
     * Creates connection if needed, enables WAL mode and sets busy timeout.
     * @return Active database connection
     * @throws SQLException if connection cannot be established
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            connection = DriverManager.getConnection(url);
            try (Statement stmt = connection.createStatement()) {
                // Enable WAL mode, set busy timeout, and foreign keys
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA busy_timeout=5000;");
                stmt.execute("PRAGMA foreign_keys=ON;");
            } catch (SQLException e) {
                logger.warn("Error configuring SQLite pragmas", e);
                throw e;
            }
        }
        return connection;
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
            // If the schema isn't initialized yet, try to initialize and retry once
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table")) {
                initializeSchema();
                return getSetting(key);
            }
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
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table")) {
                initializeSchema();
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
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table")) {
                initializeSchema();
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
            } else if (currentVersion < SqliteSchema.SCHEMA_VERSION) {
                // Incremental migration
                List<String> migrationStmts = SqliteSchema.getMigrationStatements(currentVersion);
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : migrationStmts) {
                        stmt.execute(sql);
                    }
                }
            }

            // Set schema version
            String migration = SqliteSchema.getMigrationStatement();
            if (migration != null && !migration.trim().isEmpty()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(migration);
                }
            }
        } catch (SQLException e) {
            logger.error("Error initializing SQLite schema", e);
        }
    }
/**
     * Upsert an account record. Creates new or updates existing.
     * @param displayName Account display name
     * @param playerId Player ID from RuneLite API
     */
    public synchronized void upsertAccount(String displayName, String playerId) {
        final String sql = "INSERT OR REPLACE INTO accounts (id, display_name, player_id, session_start, accumulated_time) " +
                "VALUES (" +
                "  (SELECT id FROM accounts WHERE display_name = ?)," +
                "  ?," +
                "  ?," +
                "  COALESCE((SELECT session_start FROM accounts WHERE display_name = ?), ?)," +
                "  COALESCE((SELECT accumulated_time FROM accounts WHERE display_name = ?), ?)" +
                ")";

        long nowMillis = Instant.now().toEpochMilli();

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, displayName);
                ps.setString(2, displayName);
                ps.setString(3, playerId);
                ps.setString(4, displayName);
                ps.setLong(5, nowMillis);
                ps.setString(6, displayName);
                ps.setLong(7, 0L);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error upserting account for displayName={}", displayName, e);
        }
    }
/**
     * Load account data from SQLite, fully reconstructing trades and flips.
     * @param displayName Account display name
     * @return AccountData object with all trades loaded
     */
    public synchronized AccountData loadAccount(String displayName) {
        AccountData data = new AccountData();
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return data;
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

        return data;
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

        String sql = "SELECT rfi.item_id, rfi.offer_uuid, rfi.amount_consumed, t.price " +
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
                        int price = rs.getInt("price"); // 0 if not found in trades

                        // Create a stub OfferEvent with the item info for display purposes
                        OfferEvent stubOffer = createStubOfferEvent(itemId, true, amountConsumed, price);
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

        String sql = "SELECT rfo.item_id, rfo.offer_uuid, rfo.amount_consumed, t.price " +
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
                        int price = rs.getInt("price"); // 0 if not found in trades

                        // Create a stub OfferEvent with the item info for display purposes
                        OfferEvent stubOffer = createStubOfferEvent(itemId, false, amountConsumed, price);
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
    private OfferEvent createStubOfferEvent(int itemId, boolean isBuy, int qty, int price) {
        return new OfferEvent(
            java.util.UUID.randomUUID().toString(),
            isBuy,
            itemId,
            qty,
            price,
            Instant.now(),
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

        // Load all trades for this account, grouped by item
        String sql = "SELECT item_id, timestamp, qty, price, is_buy FROM trades WHERE account_id = ? AND qty > 0 ORDER BY item_id, timestamp";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int itemId = rs.getInt("item_id");
                        long timestamp = rs.getLong("timestamp");
                        int qty = rs.getInt("qty");
                        int price = rs.getInt("price");
                        boolean isBuy = rs.getInt("is_buy") == 1;

                        // Create OfferEvent from trade record
                        OfferEvent offer = createOfferEvent(itemId, timestamp, qty, price, isBuy, displayName);
                        offersByItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(offer);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading trades for accountId={}", accountId, e);
        }

        // Convert to FlippingItem objects
        List<FlippingItem> items = new ArrayList<>();
        for (Map.Entry<Integer, List<OfferEvent>> entry : offersByItem.entrySet()) {
            int itemId = entry.getKey();
            List<OfferEvent> offers = entry.getValue();

            FlippingItem item = new FlippingItem(itemId, "Item " + itemId, 70, displayName);
            item.getHistory().getCompressedOfferEvents().addAll(offers);
            items.add(item);
        }

        return items;
    }

    /**
     * Create an OfferEvent from trade record data.
     */
    private OfferEvent createOfferEvent(int itemId, long timestamp, int qty, int price, boolean isBuy, String displayName) {
        return new OfferEvent(
            java.util.UUID.randomUUID().toString(), // uuid
            isBuy, // buy
            itemId, // itemId
            qty, // currentQuantityInTrade
            price, // price
            Instant.ofEpochMilli(timestamp), // time
            0, // slot
            isBuy ? net.runelite.api.GrandExchangeOfferState.BOUGHT : net.runelite.api.GrandExchangeOfferState.SOLD, // state
            0, // tickArrivedAt
            0, // ticksSinceFirstOffer
            qty, // totalQuantityInTrade
            null, // tradeStartedAt
            false, // beforeLogin
            displayName, // madeBy
            "Item " + itemId, // itemName
            price, // listedPrice
            price * qty // spent
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
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Trade insert: account not found for displayName={}", displayName);
            return -1;
        }

        final String sql = "INSERT INTO trades (account_id, item_id, timestamp, qty, price, is_buy) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, accountId);
            ps.setInt(2, itemId);
            ps.setLong(3, timestamp);
            ps.setInt(4, qty);
            ps.setInt(5, price);
            ps.setInt(6, isBuy ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.error("Error inserting trade for displayName={}, itemId={}", displayName, itemId, e);
        }
        return -1;
    }

    public synchronized void syncAccountData(String displayName, AccountData data) {
        if (data == null) {
            return;
        }

        upsertAccount(displayName, null);
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Account sync: account not found for displayName={}", displayName);
            return;
        }

        try {
            Connection conn = getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try {
                clearAccountTradeAndEventData(conn, accountId);
                Map<String, Integer> tradeIdsByUuid = insertTradesForAccount(conn, accountId, data);
                insertRecipeFlipsForAccount(conn, accountId, data, tradeIdsByUuid);
                conn.commit();
                logger.debug("Synced account data to SQLite for {}", displayName);
            } catch (Exception e) {
                conn.rollback();
                logger.warn("Failed syncing account {} to SQLite", displayName, e);
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            logger.warn("Error preparing SQLite sync for {}", displayName, e);
        }
    }

    private void clearAccountTradeAndEventData(Connection conn, int accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM recipe_flip_inputs WHERE recipe_flip_id IN (" +
                "SELECT rf.id FROM recipe_flips rf JOIN events e ON rf.event_id = e.id WHERE e.account_id = ?" +
            ")")) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM recipe_flip_outputs WHERE recipe_flip_id IN (" +
                "SELECT rf.id FROM recipe_flips rf JOIN events e ON rf.event_id = e.id WHERE e.account_id = ?" +
            ")")) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM recipe_flips WHERE event_id IN (SELECT id FROM events WHERE account_id = ?)")) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM consumed_trade WHERE event_id IN (SELECT id FROM events WHERE account_id = ?)")) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM consumed_trade WHERE trade_id IN (SELECT id FROM trades WHERE account_id = ?)")) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM events WHERE account_id = ?")) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM trades WHERE account_id = ?")) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }
    }

    private Map<String, Integer> insertTradesForAccount(Connection conn, int accountId, AccountData data) throws SQLException {
        Map<String, Integer> tradeIdsByUuid = new HashMap<>();
        String sql = "INSERT INTO trades (account_id, item_id, uuid, timestamp, qty, price, is_buy) VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (FlippingItem item : data.getTrades()) {
                for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                    long timestamp = offer.getTime() != null ? offer.getTime().toEpochMilli() : System.currentTimeMillis();
                    ps.setInt(1, accountId);
                    ps.setInt(2, offer.getItemId());
                    if (offer.getUuid() == null) {
                        ps.setNull(3, Types.VARCHAR);
                    } else {
                        ps.setString(3, offer.getUuid());
                    }
                    ps.setLong(4, timestamp);
                    ps.setInt(5, offer.getCurrentQuantityInTrade());
                    ps.setInt(6, offer.getPreTaxPrice());
                    ps.setInt(7, offer.isBuy() ? 1 : 0);
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next() && offer.getUuid() != null) {
                            tradeIdsByUuid.put(offer.getUuid(), keys.getInt(1));
                        }
                    }
                }
            }
        }

        return tradeIdsByUuid;
    }

    private void insertRecipeFlipsForAccount(Connection conn, int accountId, AccountData data, Map<String, Integer> tradeIdsByUuid) throws SQLException {
        if (data.getRecipeFlipGroups() == null || data.getRecipeFlipGroups().isEmpty()) {
            return;
        }

        String eventSql = "INSERT INTO events (account_id, timestamp, type, cost, profit, note) VALUES (?, ?, 'recipe', ?, ?, ?)";
        String recipeSql = "INSERT INTO recipe_flips (event_id, recipe_key, coin_cost) VALUES (?, ?, ?)";
        String inputSql = "INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
        String outputSql = "INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
        String consumedSql = "INSERT INTO consumed_trade (trade_id, qty, event_id) VALUES (?, ?, ?)";

        try (PreparedStatement eventPs = conn.prepareStatement(eventSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement recipePs = conn.prepareStatement(recipeSql, Statement.RETURN_GENERATED_KEYS);
             PreparedStatement inputPs = conn.prepareStatement(inputSql);
             PreparedStatement outputPs = conn.prepareStatement(outputSql);
             PreparedStatement consumedPs = conn.prepareStatement(consumedSql)) {

            for (RecipeFlipGroup group : data.getRecipeFlipGroups()) {
                String recipeKey = group.getRecipeKey();

                for (RecipeFlip flip : group.getRecipeFlips()) {
                    long timestamp = flip.getTimeOfCreation() != null ? flip.getTimeOfCreation().toEpochMilli() : System.currentTimeMillis();
                    eventPs.setInt(1, accountId);
                    eventPs.setLong(2, timestamp);
                    eventPs.setInt(3, safeLongToInt(flip.getExpense()));
                    eventPs.setInt(4, safeLongToInt(flip.getProfit()));
                    eventPs.setNull(5, Types.VARCHAR);
                    eventPs.executeUpdate();

                    int eventId;
                    try (ResultSet eventKeys = eventPs.getGeneratedKeys()) {
                        if (!eventKeys.next()) {
                            continue;
                        }
                        eventId = eventKeys.getInt(1);
                    }

                    recipePs.setInt(1, eventId);
                    recipePs.setString(2, recipeKey);
                    recipePs.setLong(3, flip.getCoinCost());
                    recipePs.executeUpdate();

                    int recipeFlipId;
                    try (ResultSet recipeKeys = recipePs.getGeneratedKeys()) {
                        if (!recipeKeys.next()) {
                            continue;
                        }
                        recipeFlipId = recipeKeys.getInt(1);
                    }

                    insertRecipeFlipComponent(inputPs, consumedPs, tradeIdsByUuid, recipeFlipId, eventId, flip.getInputs());
                    insertRecipeFlipComponent(outputPs, consumedPs, tradeIdsByUuid, recipeFlipId, eventId, flip.getOutputs());
                }
            }
        }
    }

    private void insertRecipeFlipComponent(
        PreparedStatement componentPs,
        PreparedStatement consumedPs,
        Map<String, Integer> tradeIdsByUuid,
        int recipeFlipId,
        int eventId,
        Map<Integer, Map<String, PartialOffer>> component
    ) throws SQLException {
        if (component == null || component.isEmpty()) {
            return;
        }

        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : component.entrySet()) {
            int itemId = entry.getKey();
            for (PartialOffer partialOffer : entry.getValue().values()) {
                String offerUuid = partialOffer.getOfferUuid();
                int amountConsumed = partialOffer.amountConsumed;

                componentPs.setInt(1, recipeFlipId);
                componentPs.setInt(2, itemId);
                if (offerUuid == null) {
                    componentPs.setNull(3, Types.VARCHAR);
                } else {
                    componentPs.setString(3, offerUuid);
                }
                componentPs.setInt(4, amountConsumed);
                componentPs.executeUpdate();

                if (offerUuid != null && amountConsumed > 0) {
                    Integer tradeId = tradeIdsByUuid.get(offerUuid);
                    if (tradeId != null) {
                        consumedPs.setInt(1, tradeId);
                        consumedPs.setInt(2, amountConsumed);
                        consumedPs.setInt(3, eventId);
                        consumedPs.executeUpdate();
                    }
                }
            }
        }
    }

    private int safeLongToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
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

        final String sql = "INSERT OR REPLACE INTO active_slots (" +
            "id, account_id, slot_index, offer_uuid, item_id, is_buy, price, qty, total_qty, state, time" +
            ") VALUES (" +
            "(SELECT id FROM active_slots WHERE account_id = ? AND slot_index = ?)," +
            " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?" +
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

        final String sql = "SELECT offer_uuid, item_id, is_buy, price, qty, total_qty, state, time " +
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

        final String sql = "SELECT slot_index, offer_uuid, item_id, is_buy, price, qty, total_qty, state, time " +
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
    public synchronized void upsertGeLimitState(String displayName, int itemId, Instant nextRefresh, int itemsBought) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("GE limit state upsert: account not found for displayName={}", displayName);
            return;
        }

        final String sql = "INSERT OR REPLACE INTO ge_limit_state (" +
                "id, account_id, item_id, next_refresh, items_bought" +
                ") VALUES (" +
                "(SELECT id FROM ge_limit_state WHERE account_id = ? AND item_id = ?)," +
                " ?, ?, ?, ?" +
                ")";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // Bind: 2 for subquery, then 1 for id, 1 for account_id, 1 for item_id, 1 for next_refresh, 1 for items_bought
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                ps.setInt(3, accountId);
                ps.setInt(4, itemId);
                ps.setLong(5, nextRefresh.toEpochMilli());
                ps.setInt(6, itemsBought);
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

        final String sql = "SELECT next_refresh, items_bought FROM ge_limit_state WHERE account_id = ? AND item_id = ?";
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

        // Aggregate trade stats, excluding FULLY consumed trades
        // Partially consumed trades contribute (remaining_qty * price)
        // Fully consumed trades are excluded (remaining_qty = 0)
        String sql = "SELECT " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 1 THEN (t.qty - COALESCE(ct.qty, 0)) * t.price ELSE 0 END), 0) as total_expense, " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 0 THEN (t.qty - COALESCE(ct.qty, 0)) * t.price ELSE 0 END), 0) as total_revenue, " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 0 THEN (t.qty - COALESCE(ct.qty, 0)) * t.price ELSE -(t.qty - COALESCE(ct.qty, 0)) * t.price END), 0) as total_profit, " +
            "COUNT(DISTINCT t.id) as flip_count " +
            "FROM trades t " +
            "LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "WHERE t.account_id = ? AND t.timestamp > ? AND (ct.id IS NULL OR ct.qty < t.qty)";

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
                        // Tax is typically 1% of sell value for items > 100gp, capped at 5M
                        // For simplicity, estimate as 1% of revenue (actual calculation is more complex)
                        long revenue = rs.getLong("total_revenue");
                        long estimatedTax = Math.min(revenue / 100, 5_000_000);
                        result.put("taxPaid", estimatedTax);
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
        final String sql = "SELECT id FROM accounts WHERE display_name = ?";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, displayName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("id");
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
        } catch ( SQLException e) {
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
        final String sql = "INSERT OR REPLACE INTO consumed_trade (id, trade_id, qty, event_id) " +
            "VALUES ((SELECT id FROM consumed_trade WHERE trade_id = ?), ?, ?, ?)";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, tradeId);
                ps.setInt(2, tradeId);
                ps.setInt(3, qty);
                if (eventId == null) {
                    ps.setNull(4, Types.INTEGER);
                } else {
                    ps.setInt(4, eventId);
                }
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error consuming trade tradeId={}, qty={}, eventId={}", tradeId, qty, eventId, e);
        }
    }
/**
     * Remove consumed status from a trade.
     * @param tradeId Trade ID to unconsume
     */
    public synchronized void unconsumeTrade(int tradeId) {
        final String sql = "DELETE FROM consumed_trade WHERE trade_id = ?";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, tradeId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error unconsuming trade tradeId={}", tradeId, e);
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

    // --- Transaction helper ---

    /**
     * Run a block of work inside a database transaction.
     * Commits on success, rolls back on exception.
     */
    public synchronized void runInTransaction(java.util.function.Consumer<Connection> work) {
        try {
            Connection conn = getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                work.accept(conn);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                logger.error("Transaction rolled back", e);
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            logger.error("Error starting transaction", e);
        }
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
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accumulatedTimeMillis);
            ps.setInt(2, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error updating session time for displayName={}", displayName, e);
        }
    }

    // --- AccountWideData persistence (stored as JSON blob in settings) ---

    private static final Gson ACCOUNT_WIDE_GSON = new GsonBuilder()
        .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
            @Override
            public void write(JsonWriter out, Instant value) throws java.io.IOException {
                if (value == null) { out.nullValue(); return; }
                out.value(value.toString());
            }
            @Override
            public Instant read(JsonReader in) throws java.io.IOException {
                JsonToken token = in.peek();
                if (token == JsonToken.NULL) { in.nextNull(); return null; }
                return Instant.parse(in.nextString());
            }
        })
        .create();

    /**
     * Load AccountWideData from the settings table.
     * @return AccountWideData or null if not stored
     */
    public synchronized com.flippingutilities.model.AccountWideData loadAccountWideData() {
        String json = getSetting("accountwide_data");
        if (json == null || json.isEmpty()) return null;
        try {
            return ACCOUNT_WIDE_GSON.fromJson(json, com.flippingutilities.model.AccountWideData.class);
        } catch (Exception e) {
            logger.error("Error deserializing AccountWideData", e);
            return null;
        }
    }

    /**
     * Save AccountWideData to the settings table as JSON.
     * @param data The AccountWideData to persist
     */
    public synchronized void saveAccountWideData(com.flippingutilities.model.AccountWideData data) {
        if (data == null) return;
        try {
            String json = ACCOUNT_WIDE_GSON.toJson(data);
            setSetting("accountwide_data", json);
        } catch (Exception e) {
            logger.error("Error serializing AccountWideData", e);
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
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.setInt(2, itemId);
            ps.setInt(3, isFavorite ? 1 : 0);
            ps.setString(4, favoriteCode != null ? favoriteCode : "1");
            ps.executeUpdate();
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
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
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
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
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
        } catch (SQLException e) {
            logger.error("Error loading all favorites for displayName={}", displayName, e);
        }
        return result;
    }
}
