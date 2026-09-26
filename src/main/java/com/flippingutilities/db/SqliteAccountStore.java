package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.RecipeFlipGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Account identity, session state, account reconstruction, and whole-account deletion.
 * Called only while the owning SqliteStorage monitor is held; shares its connection.
 */
final class SqliteAccountStore {
    private static final Logger logger = LoggerFactory.getLogger(SqliteAccountStore.class);

    private final SqliteStorage storage;
    private final Map<String, Integer> accountIdCache;
    private final SqliteOfferStore offers;
    private final SqliteRecipeStore recipes;
    private final SqliteItemStateStore itemState;

    SqliteAccountStore(SqliteStorage storage, Map<String, Integer> accountIdCache,
                       SqliteOfferStore offers, SqliteRecipeStore recipes, SqliteItemStateStore itemState) {
        this.storage = storage;
        this.accountIdCache = accountIdCache;
        this.offers = offers;
        this.recipes = recipes;
        this.itemState = itemState;
    }

    /**
     * Upsert an account record. Creates new or updates existing.
     * @param displayName Account display name
     * @param playerId Player ID from RuneLite API
     */
    void upsertAccount(String displayName, String playerId) {
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
            Connection conn = storage.getConnection();
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
    AccountData loadAccount(String displayName) {
        AccountData data = new AccountData();
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return null;
        }

        // Load session info
        final String sessionSql = "SELECT session_start, accumulated_time FROM accounts WHERE id = ?";
        try {
            Connection conn = storage.getConnection();
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
        data.getLastOffers().putAll(offers.loadAllSlots(displayName, partialHistory));
        List<FlippingItem> tradeItems = offers.loadTradeItems(accountId, displayName, partialHistory);
        data.getTrades().addAll(tradeItems);

        // Load recipe flip groups
        List<RecipeFlipGroup> recipeFlipGroups = recipes.loadRecipeFlipGroups(accountId, displayName);
        data.getRecipeFlipGroups().addAll(recipeFlipGroups);

        // Recreate favorite-only items (favorited from search without ever trading them).
        // The JSON backend round-trips these through the trades list; without this they would
        // silently disappear (along with their favorite) on every SQLite reload.
        itemState.restoreFavoriteOnlyItems(displayName, data);
        itemState.restoreItemVisibility(accountId, displayName, data);
        itemState.restoreGeLimitStates(displayName, data);

        // This model was reconstructed from the current schema, not a legacy JSON file.
        data.setVersion(AccountData.CURRENT_VERSION);
        return data;
    }

    /**
     * List all account display names.
     * @return List of display names sorted alphabetically
     */
    List<String> listAccounts() {
        final String sql = "SELECT display_name FROM accounts ORDER BY display_name";
        List<String> names = new ArrayList<>();

        try {
            Connection conn = storage.getConnection();
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

    int getOrCreateAccountId(String displayName) {
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

    Integer getAccountId(String displayName) {
        Integer cached = accountIdCache.get(displayName);
        if (cached != null) {
            return cached;
        }
        final String sql = "SELECT id FROM accounts WHERE display_name = ?";
        try {
            Connection conn = storage.getConnection();
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

    /**
     * Update accumulated session time for an account.
     * @param displayName Account display name
     * @param accumulatedTimeMillis Total accumulated session time in milliseconds
     */
    void updateAccountSessionTime(String displayName, long accumulatedTimeMillis) {
        int accountId = getOrCreateAccountId(displayName);

        String sql = "UPDATE accounts SET accumulated_time = ? WHERE id = ?";
        try {
            Connection conn = storage.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, accumulatedTimeMillis);
                ps.setInt(2, accountId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error updating session time", e);
        }
    }

    /**
     * Delete ALL data for an account (trades, recipe flips, slots,
     * favorites, GE limit state, session time, and the migrated_ flag). Mirrors
     * TradePersister.deleteFile for the JSON backend.
     */
    void deleteAccountData(String displayName) {
        Integer accountId = getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        try {
            Connection conn = storage.getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                execDelete(conn, "DELETE FROM recipe_flip_inputs WHERE recipe_flip_id IN " +
                    "(SELECT id FROM recipe_flips WHERE account_id = ?)", accountId);
                execDelete(conn, "DELETE FROM recipe_flip_outputs WHERE recipe_flip_id IN " +
                    "(SELECT id FROM recipe_flips WHERE account_id = ?)", accountId);
                execDelete(conn, "DELETE FROM recipe_flips WHERE account_id = ?", accountId);
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

    private void execDelete(Connection conn, String sql, int accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            ps.executeUpdate();
        }
    }
}
