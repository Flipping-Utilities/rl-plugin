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

    /** Returns a valid JDBC Connection to the SQLite database. If the connection
     * is not yet created, it will be established. WAL mode is enabled and a busy
     * timeout is configured. */
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

    /** Close the underlying connection if present. */
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

    /** Initialize database schema using SqliteSchema DDL. */
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

    public synchronized void insertTrade(String displayName, int itemId, long timestamp, int qty, int price, boolean isBuy) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            logger.warn("Trade insert: account not found for displayName={}", displayName);
            return;
        }

        final String sql = "INSERT INTO trades (account_id, item_id, timestamp, qty, price, is_buy) VALUES (?, ?, ?, ?, ?, ?)";
        try {
            Connection conn = getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, accountId);
                ps.setInt(2, itemId);
                ps.setLong(3, timestamp);
                ps.setInt(4, qty);
                ps.setInt(5, price);
                ps.setInt(6, isBuy ? 1 : 0);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("Error inserting trade for displayName={}, itemId={}", displayName, itemId, e);
        }
    }

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

    // GE limit state upsert: map displayName -> account_id, then insert/update ge_limit_state
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

    // GE limit state load: return map with nextRefresh (Instant) and itemsBought, or null if not found
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

    // Internal helper: find account_id from display_name
    private synchronized Integer getAccountId(String displayName) {
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
}
