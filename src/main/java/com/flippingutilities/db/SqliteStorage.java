package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.OfferEvent;
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
                // Enable WAL mode and set busy timeout
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA busy_timeout=5000;");
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
            // Create tables in order
            List<String> creates = SqliteSchema.getCreateStatementsInOrder();
            try (Statement stmt = conn.createStatement()) {
                for (String sql : creates) {
                    stmt.execute(sql);
                }
            }

            // Create indexes
            List<String> indexes = SqliteSchema.getIndexStatements();
            try (Statement stmt = conn.createStatement()) {
                for (String sql : indexes) {
                    stmt.execute(sql);
                }
            }

            // Migration/versioning via user_version pragma
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
     * Load account data from SQLite.
     * @param displayName Account display name
     * @return AccountData object (empty if not found)
     */
    public synchronized AccountData loadAccount(String displayName) {
        final String sql = "SELECT session_start, accumulated_time FROM accounts WHERE display_name = ?";

        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, displayName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new AccountData();
                    }

                    long sessionStartMillis = rs.getLong("session_start");
                    boolean sessionStartWasNull = rs.wasNull();
                    long accumulatedTimeMillis = rs.getLong("accumulated_time");
                    boolean accumulatedTimeWasNull = rs.wasNull();

                    AccountData data = new AccountData();
                    if (!sessionStartWasNull && sessionStartMillis > 0) {
                        try {
                            java.lang.reflect.Field f = AccountData.class.getDeclaredField("sessionStartTime");
                            f.setAccessible(true);
                            f.set(data, Instant.ofEpochMilli(sessionStartMillis));
                        } catch (Exception ex) {
                            logger.warn("Error setting sessionStartTime for displayName={}", displayName, ex);
                        }
                    }
                    if (!accumulatedTimeWasNull) {
                        try {
                            java.lang.reflect.Field f = AccountData.class.getDeclaredField("accumulatedSessionTimeMillis");
                            f.setAccessible(true);
                            f.setLong(data, accumulatedTimeMillis);
                        } catch (Exception ex) {
                            logger.warn("Error setting accumulatedSessionTimeMillis for displayName={}", displayName, ex);
                        }
                    }
                    return data;
                }
            }
        } catch (SQLException e) {
            logger.error("Error loading account for displayName={}", displayName, e);
            return new AccountData();
        }
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

        // Query aggregates per item_id, excluding consumed trades
        String sql = "SELECT " +
            "t.item_id, " +
            "SUM(t.qty) as total_qty, " +
            "SUM(CASE WHEN t.is_buy = 1 THEN t.qty ELSE 0 END) as buy_qty, " +
            "SUM(CASE WHEN t.is_buy = 0 THEN t.qty ELSE 0 END) as sell_qty, " +
            "SUM(CASE WHEN t.is_buy = 0 THEN t.qty * t.price ELSE -t.qty * t.price END) as total_profit, " +
            "CASE " +
            "  WHEN SUM(CASE WHEN t.is_buy = 1 THEN t.qty * t.price ELSE 0 END) > 0 " +
            "  THEN (SUM(CASE WHEN t.is_buy = 0 THEN t.qty * t.price ELSE -t.qty * t.price END) * 100.0 / " +
            "        SUM(CASE WHEN t.is_buy = 1 THEN t.qty * t.price ELSE 0 END)) " +
            "  ELSE 0 " +
            "END as roi, " +
            "MAX(t.timestamp) as latest_timestamp " +
            "FROM trades t " +
            "LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "WHERE t.account_id = ? AND t.timestamp > ? AND ct.id IS NULL " +
            "GROUP BY t.item_id " +
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

        // Aggregate trade stats, excluding consumed trades
        String sql = "SELECT " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 1 THEN t.qty * t.price ELSE 0 END), 0) as total_expense, " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 0 THEN t.qty * t.price ELSE 0 END), 0) as total_revenue, " +
            "COALESCE(SUM(CASE WHEN t.is_buy = 0 THEN t.qty * t.price ELSE -t.qty * t.price END), 0) as total_profit, " +
            "COUNT(DISTINCT t.id) as flip_count " +
            "FROM trades t " +
            "LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
            "WHERE t.account_id = ? AND t.timestamp > ? AND ct.id IS NULL";

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
            "WHERE account_id = ? " +
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
}
