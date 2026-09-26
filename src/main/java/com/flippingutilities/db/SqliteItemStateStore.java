package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Favorites, item visibility, and GE limit state independent of retained offer history.
 * Called only while the owning SqliteStorage monitor is held; shares its connection.
 */
final class SqliteItemStateStore {
    private final SqliteStorage storage;

    SqliteItemStateStore(SqliteStorage storage) {
        this.storage = storage;
    }

    /**
     * Restores items without trade rows when they retain a favorite or custom search code.
     */
    void restoreFavoriteOnlyItems(String displayName, AccountData data) {
        Map<Integer, Map<String, Object>> favorites = loadAllFavorites(displayName);
        for (Map.Entry<Integer, Map<String, Object>> entry : favorites.entrySet()) {
            int itemId = entry.getKey();
            boolean isFavorite = entry.getValue().get("isFavorite") instanceof Boolean && (Boolean) entry.getValue().get("isFavorite");
            Object favoriteCode = entry.getValue().get("favoriteCode");
            boolean hasCustomCode = favoriteCode instanceof String && !"1".equals(favoriteCode);
            if (!isFavorite && !hasCustomCode) {
                continue;
            }
            boolean hasTrades = data.getTrades().stream().anyMatch(item -> item.getItemId() == itemId);
            if (hasTrades) {
                continue;
            }
            FlippingItem item = new FlippingItem(itemId, "Item " + itemId, 70, displayName);
            item.setFavorite(isFavorite);
            if (favoriteCode instanceof String) {
                item.setFavoriteCode((String) favoriteCode);
            }
            data.getTrades().add(item);
        }
    }

    void upsertItemVisibility(String displayName, int itemId, boolean visible) {
        int accountId = storage.getOrCreateAccountId(displayName);
        String sql = "INSERT OR REPLACE INTO item_visibility (account_id, item_id, is_visible) VALUES (?, ?, ?)";
        try (PreparedStatement statement = storage.getConnection().prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setInt(2, itemId);
            statement.setBoolean(3, visible);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not persist item visibility for " + displayName, e);
        }
    }

    void restoreItemVisibility(int accountId, String displayName, AccountData data) {
        Map<Integer, FlippingItem> items = new HashMap<>();
        for (FlippingItem item : data.getTrades()) {
            items.put(item.getItemId(), item);
        }
        String sql = "SELECT item_id, is_visible FROM item_visibility WHERE account_id = ?";
        try (PreparedStatement statement = storage.getConnection().prepareStatement(sql)) {
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

    /** Deleting offer history does not reset an item's active GE limit window. */
    void restoreGeLimitStates(String displayName, AccountData data) {
        Map<Integer, FlippingItem> items = new HashMap<>();
        for (FlippingItem item : data.getTrades()) {
            items.put(item.getItemId(), item);
        }
        for (Map.Entry<Integer, Map<String, Object>> entry : loadAllGeLimitStates(displayName).entrySet()) {
            int itemId = entry.getKey();
            FlippingItem item = items.get(itemId);
            if (item == null) {
                item = new FlippingItem(itemId, "Item " + itemId, 70, displayName);
                data.getTrades().add(item);
            }
            Map<String, Object> state = entry.getValue();
            item.getHistory().setNextGeLimitRefresh((Instant) state.get("nextRefresh"));
            item.getHistory().setItemsBoughtThisLimitWindow((Integer) state.get("itemsBought"));
            item.getHistory().setItemsBoughtThroughCompleteOffers((Integer) state.get("itemsBoughtThroughCompleteOffers"));
        }
    }

    /**
     * Load GE limit state for every item of an account in one query.
     * @return Map of itemId -> {nextRefresh (Instant, nullable), itemsBought (int)}
     */
    Map<Integer, Map<String, Object>> loadAllGeLimitStates(String displayName) {
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        Integer accountId = storage.getAccountId(displayName);
        if (accountId == null) {
            return result;
        }

        final String sql = "SELECT item_id, next_refresh, items_bought, items_bought_complete FROM ge_limit_state WHERE account_id = ?";
        try {
            Connection conn = storage.getConnection();
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
     * Upsert GE limit state for an item.
     * @param displayName Account display name
     * @param itemId Item ID
     * @param nextRefresh Time when GE limit resets
     * @param itemsBought Number of items bought this limit window
     */
    void upsertGeLimitState(String displayName, int itemId, Instant nextRefresh, int itemsBought, int itemsBoughtThroughCompleteOffers) {
        int accountId = storage.getOrCreateAccountId(displayName);

        final String sql = "INSERT OR REPLACE INTO ge_limit_state (" +
                "id, account_id, item_id, next_refresh, items_bought, items_bought_complete" +
                ") VALUES (" +
                "(SELECT id FROM ge_limit_state WHERE account_id = ? AND item_id = ?)," +
                " ?, ?, ?, ?, ?" +
                ")";

        try {
            Connection conn = storage.getConnection();
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
     * Upsert a favorite status for an item under an account.
     * @param displayName Account display name
     * @param itemId Item ID
     * @param isFavorite Whether the item is favorited
     * @param favoriteCode Quick search code
     */
    void upsertFavorite(String displayName, int itemId, boolean isFavorite, String favoriteCode) {
        int accountId = storage.getOrCreateAccountId(displayName);

        String sql = "INSERT OR REPLACE INTO item_favorites (account_id, item_id, is_favorite, favorite_code) VALUES (?, ?, ?, ?)";
        try {
            Connection conn = storage.getConnection();
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
     * Load all favorites for an account.
     * @return Map of itemId -> Map with "isFavorite" and "favoriteCode"
     */
    Map<Integer, Map<String, Object>> loadAllFavorites(String displayName) {
        Integer accountId = storage.getAccountId(displayName);
        Map<Integer, Map<String, Object>> result = new HashMap<>();
        if (accountId == null) return result;

        String sql = "SELECT item_id, is_favorite, favorite_code FROM item_favorites WHERE account_id = ?";
        try {
            Connection conn = storage.getConnection();
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
}
