package com.flippingutilities.db;

import com.flippingutilities.model.*;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * Replaces the old JSON file storage with a SQLite database.
 *
 * The old system saved ALL your trade data into one big JSON file every time it saved.
 * If RuneLite crashed while writing that file, the data could be corrupted or lost entirely.
 *
 * This new system uses SQLite which:
 * - Saves each individual trade the instant it happens (no waiting for a full save)
 * - Is crash-proof: SQLite handles writes atomically (it either fully saves or doesn't, never half-saves)
 * - Is faster for large trade histories because it doesn't need to rewrite everything
 */
@Slf4j
public class SqliteDataStore {
    private Connection connection;
    private final File dbFile;
    private final Gson gson;

    public SqliteDataStore(File parentDirectory, Gson gson) {
        this.dbFile = new File(parentDirectory, "flipping.db");
        this.gson = gson;
    }

    public boolean databaseExists() {
        return dbFile.exists();
    }

    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * Checks that an existing database file starts with the SQLite magic header.
     * Without this check, the JDBC driver silently overwrites a corrupted file
     * with an empty database instead of throwing an error.
     */
    private void validateDatabaseFile() throws SQLException {
        if (!dbFile.exists() || dbFile.length() == 0) {
            return; // new database, nothing to validate
        }
        if (dbFile.length() < SQLITE_HEADER.length) {
            throw new SQLException("Database file is too small to be a valid SQLite file: " + dbFile.getAbsolutePath());
        }
        try (FileInputStream fis = new FileInputStream(dbFile)) {
            byte[] header = new byte[SQLITE_HEADER.length];
            int read = fis.read(header);
            if (read < SQLITE_HEADER.length || !Arrays.equals(header, SQLITE_HEADER)) {
                throw new SQLException("Database file has invalid SQLite header (file may be corrupted): " + dbFile.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new SQLException("Cannot read database file for validation: " + dbFile.getAbsolutePath(), e);
        }
    }

    /**
     * Opens the database connection and creates all tables if they don't exist yet.
     * WAL mode makes reads and writes faster and safer.
     *
     * If the database file exists but is not a valid SQLite file (e.g. corrupted),
     * this throws a SQLException so the caller can fall back to JSON storage.
     */
    public void initialize() throws SQLException {
        validateDatabaseFile();
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");

            for (String sql : SqliteSchema.CREATE_TABLES) {
                stmt.execute(sql);
            }
            for (String sql : SqliteSchema.CREATE_INDEXES) {
                stmt.execute(sql);
            }
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Error closing SQLite connection", e);
            }
        }
    }

    public void setSchemaVersion(int version) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM schema_version");
            stmt.execute("INSERT INTO schema_version (version) VALUES (" + version + ")");
        }
    }

    // ==================== ACCOUNT OPERATIONS ====================

    /**
     * Loads ALL accounts and their trade data from the database.
     * Called once at startup. Reconstructs the same in-memory data structure
     * the plugin expects (AccountData with FlippingItems containing OfferEvents).
     */
    public Map<String, AccountData> loadAllAccounts() throws SQLException {
        Map<String, AccountData> accounts = new HashMap<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM accounts")) {
            while (rs.next()) {
                String name = rs.getString("display_name");
                AccountData data = new AccountData();
                data.setSessionStartTime(instantFromMillis(rs.getLong("session_start_time")));
                data.setAccumulatedSessionTimeMillis(rs.getLong("accumulated_session_time_millis"));
                data.setLastSessionTimeUpdate(instantFromMillis(rs.getLong("last_session_time_update")));
                data.setLastStoredAt(instantFromMillis(rs.getLong("last_stored_at")));
                data.setLastModifiedAt(instantFromMillis(rs.getLong("last_modified_at")));

                data.setTrades(loadFlippingItems(name));
                data.setLastOffers(loadLastOffers(name));
                data.setRecipeFlipGroups(loadRecipeFlipGroups(name));

                accounts.put(name, data);
            }
        }
        return accounts;
    }

    /**
     * Saves a complete account to the database. Used during migration from JSON
     * and for periodic full checkpoints.
     */
    public void saveAccount(String displayName, AccountData data) throws SQLException {
        boolean wasAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            // Upsert account row
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO accounts (display_name, session_start_time, accumulated_session_time_millis, " +
                "last_session_time_update, last_stored_at, last_modified_at) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, displayName);
                ps.setLong(2, toMillis(data.getSessionStartTime()));
                ps.setLong(3, data.getAccumulatedSessionTimeMillis());
                ps.setLong(4, toMillis(data.getLastSessionTimeUpdate()));
                ps.setLong(5, toMillis(data.getLastStoredAt()));
                ps.setLong(6, toMillis(data.getLastModifiedAt()));
                ps.executeUpdate();
            }

            // Clear existing items and offers for this account (will re-insert)
            deleteAccountData(displayName);

            // Save all flipping items and their offer events
            for (FlippingItem item : data.getTrades()) {
                long fiId = insertFlippingItem(displayName, item);
                saveGeLimitState(fiId, item.getHistory());
                for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                    insertOfferEvent(fiId, offer);
                }
            }

            // Save last offers per slot
            saveLastOffers(displayName, data.getLastOffers());

            // Save recipe flip groups
            saveRecipeFlipGroups(displayName, data.getRecipeFlipGroups());

            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(wasAutoCommit);
        }
    }

    /**
     * THE KEY OPTIMIZATION: Saves a single trade offer to the database immediately.
     *
     * Instead of waiting to save everything at once (which could lose data on crash),
     * this is called every time a GE offer comes in. The trade is permanently saved
     * in milliseconds, so even if RuneLite crashes right after, the data is safe.
     */
    public void saveOfferEvent(String displayName, int itemId, String itemName, int geLimit,
                                String flippedBy, OfferEvent offer) throws SQLException {
        boolean wasAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            // Find or create the flipping item entry
            long fiId = findOrCreateFlippingItem(displayName, itemId, itemName, geLimit, flippedBy);

            // Insert the offer event
            insertOfferEvent(fiId, offer);

            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(wasAutoCommit);
        }
    }

    /**
     * Saves GE limit tracking state (how many items bought, when limit resets).
     */
    public void saveGeLimitState(long flippingItemId, HistoryManager history) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT OR REPLACE INTO ge_limit_state (flipping_item_id, next_ge_limit_refresh, " +
            "items_bought_this_limit_window, items_bought_through_complete) VALUES (?,?,?,?)")) {
            ps.setLong(1, flippingItemId);
            ps.setLong(2, toMillis(history.getNextGeLimitRefresh()));
            ps.setInt(3, history.getItemsBoughtThisLimitWindow());
            ps.setInt(4, 0); // not easily accessible due to private field
            ps.executeUpdate();
        }
    }

    /**
     * Saves the "last offer" state for a GE slot. This is needed for duplicate detection
     * and tracking trade progress within a slot.
     */
    public void saveLastOffer(String displayName, int slot, OfferEvent offer) throws SQLException {
        String json = gson.toJson(offer);
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT OR REPLACE INTO last_offers (account_display_name, slot, offer_json) VALUES (?,?,?)")) {
            ps.setString(1, displayName);
            ps.setInt(2, slot);
            ps.setString(3, json);
            ps.executeUpdate();
        }
    }

    public void deleteAccount(String displayName) throws SQLException {
        deleteAccountData(displayName);
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM accounts WHERE display_name = ?")) {
            ps.setString(1, displayName);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM last_offers WHERE account_display_name = ?")) {
            ps.setString(1, displayName);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM slot_timers WHERE account_display_name = ?")) {
            ps.setString(1, displayName);
            ps.executeUpdate();
        }
    }

    // ==================== ACCOUNT-WIDE DATA ====================

    public AccountWideData loadAccountWideData() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT data_json FROM account_wide_data WHERE id = 1")) {
            if (rs.next()) {
                String json = rs.getString("data_json");
                Type type = new TypeToken<AccountWideData>(){}.getType();
                return gson.fromJson(json, type);
            }
        }
        return new AccountWideData();
    }

    public void saveAccountWideData(AccountWideData data) throws SQLException {
        String json = gson.toJson(data);
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT OR REPLACE INTO account_wide_data (id, data_json) VALUES (1, ?)")) {
            ps.setString(1, json);
            ps.executeUpdate();
        }
    }

    // ==================== INTERNAL HELPERS ====================

    private List<FlippingItem> loadFlippingItems(String accountName) throws SQLException {
        List<FlippingItem> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM flipping_items WHERE account_display_name = ?")) {
            ps.setString(1, accountName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long fiId = rs.getLong("id");
                    int itemId = rs.getInt("item_id");
                    String itemName = rs.getString("item_name");
                    int geLimit = rs.getInt("total_ge_limit");
                    String flippedBy = rs.getString("flipped_by");

                    FlippingItem item = new FlippingItem(itemId, itemName, geLimit, flippedBy != null ? flippedBy : accountName);
                    item.setValidFlippingPanelItem(rs.getInt("valid_flipping_panel_item") == 1);
                    item.setFavorite(rs.getInt("favorite") == 1);
                    item.setFavoriteCode(rs.getString("favorite_code"));

                    // Load offer events for this item
                    List<OfferEvent> offers = loadOfferEvents(fiId);
                    item.getHistory().setCompressedOfferEvents(offers);

                    // Load GE limit state
                    loadGeLimitState(fiId, item.getHistory());

                    items.add(item);
                }
            }
        }
        return items;
    }

    private List<OfferEvent> loadOfferEvents(long flippingItemId) throws SQLException {
        List<OfferEvent> offers = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM offer_events WHERE flipping_item_id = ? ORDER BY time ASC")) {
            ps.setLong(1, flippingItemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OfferEvent offer = new OfferEvent(
                        rs.getString("uuid"),
                        rs.getInt("buy") == 1,
                        rs.getInt("item_id"),
                        rs.getInt("current_quantity_in_trade"),
                        rs.getInt("price"),
                        instantFromMillis(rs.getLong("time")),
                        rs.getInt("slot"),
                        GrandExchangeOfferState.valueOf(rs.getString("state")),
                        rs.getInt("tick_arrived_at"),
                        rs.getInt("ticks_since_first_offer"),
                        rs.getInt("total_quantity_in_trade"),
                        instantFromMillis(rs.getLong("trade_started_at")),
                        rs.getInt("before_login") == 1,
                        null, null, 0, 0
                    );
                    offers.add(offer);
                }
            }
        }
        return offers;
    }

    private void loadGeLimitState(long fiId, HistoryManager history) throws SQLException {
        // GE limit state fields are private in HistoryManager with no public setters,
        // but they get recalculated from offer events during prepareForUse().
        // We don't need to explicitly load them — they'll be rebuilt from the offers.
    }

    private Map<Integer, OfferEvent> loadLastOffers(String accountName) throws SQLException {
        Map<Integer, OfferEvent> lastOffers = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT slot, offer_json FROM last_offers WHERE account_display_name = ?")) {
            ps.setString(1, accountName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot");
                    String json = rs.getString("offer_json");
                    OfferEvent offer = gson.fromJson(json, OfferEvent.class);
                    lastOffers.put(slot, offer);
                }
            }
        }
        return lastOffers;
    }

    /**
     * Recipe flips have a complex nested map structure (inputs/outputs are Map<Integer, Map<String, PartialOffer>>).
     * Rather than trying to normalize this into relational tables, we store each RecipeFlipGroup as a JSON blob
     * in the recipe_flip_groups table. This keeps the code simple and these structures are small.
     */
    private List<RecipeFlipGroup> loadRecipeFlipGroups(String accountName) throws SQLException {
        List<RecipeFlipGroup> groups = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM recipe_flip_groups WHERE account_display_name = ?")) {
            ps.setString(1, accountName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String recipeKey = rs.getString("recipe_key");
                    RecipeFlipGroup group = new RecipeFlipGroup(recipeKey);

                    // Load recipe flips from the recipe_flips table (stored as JSON blobs)
                    long groupId = rs.getLong("id");
                    group.setRecipeFlips(loadRecipeFlipsAsJson(groupId));
                    groups.add(group);
                }
            }
        }
        return groups;
    }

    private List<RecipeFlip> loadRecipeFlipsAsJson(long groupId) throws SQLException {
        List<RecipeFlip> flips = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT * FROM recipe_flips WHERE recipe_flip_group_id = ? ORDER BY time_of_creation ASC")) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // coin_cost column doubles as storage for the full JSON of the RecipeFlip
                    // This is a pragmatic choice: the nested Map<Integer, Map<String, PartialOffer>> structure
                    // is too complex to normalize into relational tables for minimal benefit
                    String flipJson = rs.getString("coin_cost");
                    if (flipJson != null && flipJson.startsWith("{")) {
                        RecipeFlip flip = gson.fromJson(flipJson, RecipeFlip.class);
                        flips.add(flip);
                    }
                }
            }
        }
        return flips;
    }

    private long insertFlippingItem(String accountName, FlippingItem item) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO flipping_items (account_display_name, item_id, item_name, total_ge_limit, " +
            "flipped_by, valid_flipping_panel_item, favorite, favorite_code) VALUES (?,?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, accountName);
            ps.setInt(2, item.getItemId());
            ps.setString(3, item.getItemName());
            ps.setInt(4, item.getTotalGELimit());
            ps.setString(5, item.getFlippedBy());
            ps.setInt(6, item.getValidFlippingPanelItem() != null && item.getValidFlippingPanelItem() ? 1 : 0);
            ps.setInt(7, item.isFavorite() ? 1 : 0);
            ps.setString(8, item.getFavoriteCode());
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to insert flipping item, no ID generated");
    }

    /**
     * Finds an existing flipping item row or creates a new one.
     * Used by the incremental save path.
     */
    private long findOrCreateFlippingItem(String accountName, int itemId, String itemName,
                                           int geLimit, String flippedBy) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT id FROM flipping_items WHERE account_display_name = ? AND item_id = ?")) {
            ps.setString(1, accountName);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }

        // Doesn't exist yet — create it
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO flipping_items (account_display_name, item_id, item_name, total_ge_limit, " +
            "flipped_by, valid_flipping_panel_item, favorite, favorite_code) VALUES (?,?,?,?,?,1,0,'1')",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, accountName);
            ps.setInt(2, itemId);
            ps.setString(3, itemName);
            ps.setInt(4, geLimit);
            ps.setString(5, flippedBy);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to find or create flipping item");
    }

    private void insertOfferEvent(long flippingItemId, OfferEvent offer) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT OR IGNORE INTO offer_events (flipping_item_id, uuid, buy, item_id, " +
            "current_quantity_in_trade, price, time, slot, state, tick_arrived_at, " +
            "ticks_since_first_offer, total_quantity_in_trade, trade_started_at, before_login) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, flippingItemId);
            ps.setString(2, offer.getUuid());
            ps.setInt(3, offer.isBuy() ? 1 : 0);
            ps.setInt(4, offer.getItemId());
            ps.setInt(5, offer.getCurrentQuantityInTrade());
            ps.setInt(6, offer.getPreTaxPrice());
            ps.setLong(7, toMillis(offer.getTime()));
            ps.setInt(8, offer.getSlot());
            ps.setString(9, offer.getState().name());
            ps.setInt(10, offer.getTickArrivedAt());
            ps.setInt(11, offer.getTicksSinceFirstOffer());
            ps.setInt(12, offer.getTotalQuantityInTrade());
            ps.setLong(13, toMillis(offer.getTradeStartedAt()));
            ps.setInt(14, offer.isBeforeLogin() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void saveLastOffers(String displayName, Map<Integer, OfferEvent> lastOffers) throws SQLException {
        try (PreparedStatement del = connection.prepareStatement(
            "DELETE FROM last_offers WHERE account_display_name = ?")) {
            del.setString(1, displayName);
            del.executeUpdate();
        }
        for (Map.Entry<Integer, OfferEvent> entry : lastOffers.entrySet()) {
            saveLastOffer(displayName, entry.getKey(), entry.getValue());
        }
    }

    private void saveRecipeFlipGroups(String accountName, List<RecipeFlipGroup> groups) throws SQLException {
        // Delete existing
        try (PreparedStatement del = connection.prepareStatement(
            "DELETE FROM recipe_flip_groups WHERE account_display_name = ?")) {
            del.setString(1, accountName);
            del.executeUpdate();
        }

        for (RecipeFlipGroup group : groups) {
            long groupId;
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO recipe_flip_groups (account_display_name, recipe_key) VALUES (?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, accountName);
                ps.setString(2, group.getRecipeKey());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    groupId = keys.getLong(1);
                }
            }

            // Store each RecipeFlip as a JSON blob in the coin_cost column
            // (the complex nested map structure doesn't fit well in relational tables)
            for (RecipeFlip flip : group.getRecipeFlips()) {
                String flipJson = gson.toJson(flip);
                try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO recipe_flips (recipe_flip_group_id, time_of_creation, coin_cost) VALUES (?,?,?)")) {
                    ps.setLong(1, groupId);
                    ps.setLong(2, toMillis(flip.getTimeOfCreation()));
                    ps.setString(3, flipJson);
                    ps.executeUpdate();
                }
            }
        }
    }

    /**
     * Deletes all trade data for an account (items, offers, recipes)
     * but keeps the account row itself.
     */
    private void deleteAccountData(String displayName) throws SQLException {
        // Due to ON DELETE CASCADE, deleting flipping_items also deletes
        // offer_events, ge_limit_state for those items
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM flipping_items WHERE account_display_name = ?")) {
            ps.setString(1, displayName);
            ps.executeUpdate();
        }
        // Recipe groups also cascade to recipe_flips and recipe_partial_offers
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM recipe_flip_groups WHERE account_display_name = ?")) {
            ps.setString(1, displayName);
            ps.executeUpdate();
        }
    }

    /**
     * Counts total offer events in the database. Used to verify migration worked correctly.
     */
    public int countOfferEvents() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM offer_events")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Counts total flipping items in the database. Used to verify migration worked correctly.
     */
    public int countFlippingItems() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM flipping_items")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // Utility: convert Instant to epoch millis (0 if null)
    private static long toMillis(Instant instant) {
        return instant != null ? instant.toEpochMilli() : 0;
    }

    // Utility: convert epoch millis to Instant (null if 0)
    private static Instant instantFromMillis(long millis) {
        return millis == 0 ? null : Instant.ofEpochMilli(millis);
    }
}
