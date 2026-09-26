package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static com.flippingutilities.db.SqliteBindings.bind;

/**
 * Favorites, item visibility, and GE limit state independent of retained offer history.
 * Called within the owning SqliteStorage monitor and transaction; shares its connection.
 */
final class SqliteItemStateStore {
    static final String IS_FAVORITE = "isFavorite";
    static final String FAVORITE_CODE = "favoriteCode";
    static final String NEXT_REFRESH = "nextRefresh";
    static final String ITEMS_BOUGHT = "itemsBought";
    static final String ITEMS_BOUGHT_THROUGH_COMPLETE_OFFERS = "itemsBoughtThroughCompleteOffers";

    private final SqliteStorage storage;

    SqliteItemStateStore(SqliteStorage storage) {
        this.storage = storage;
    }

    /**
     * Restores items without trade rows when they retain a favorite or custom search code.
     */
    void restoreFavoriteOnlyItems(String displayName, AccountData data) {
        Map<Integer, Map<String, Object>> favorites = loadAllFavorites(displayName);
        Map<Integer, FlippingItem> items = indexItems(data);
        for (Map.Entry<Integer, Map<String, Object>> entry : favorites.entrySet()) {
            int itemId = entry.getKey();
            boolean isFavorite = entry.getValue().get(IS_FAVORITE) instanceof Boolean && (Boolean) entry.getValue().get(IS_FAVORITE);
            Object favoriteCode = entry.getValue().get(FAVORITE_CODE);
            boolean hasCustomCode = favoriteCode instanceof String && !FlippingItem.DEFAULT_FAVORITE_CODE.equals(favoriteCode);
            if (!isFavorite && !hasCustomCode) {
                continue;
            }
            if (items.containsKey(itemId)) {
                continue;
            }
            FlippingItem item = getOrCreateItem(data, displayName, itemId, items);
            item.setFavorite(isFavorite);
            if (favoriteCode instanceof String) {
                item.setFavoriteCode((String) favoriteCode);
            }
        }
    }

    void upsertItemVisibility(String displayName, int itemId, boolean visible) {
        int accountId = storage.getOrCreateAccountId(displayName);
        String sql = "INSERT OR REPLACE INTO item_visibility (account_id, item_id, is_visible) " +
            "VALUES (?, ?, ?)";
        try (PreparedStatement statement = storage.getConnection().prepareStatement(sql)) {
            bind(statement, accountId, itemId, visible);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not persist item visibility for " + displayName, e);
        }
    }

    void restoreItemVisibility(int accountId, String displayName, AccountData data) {
        Map<Integer, FlippingItem> items = indexItems(data);
        String sql = "SELECT item_id, is_visible FROM item_visibility WHERE account_id = ?";
        try (PreparedStatement statement = storage.getConnection().prepareStatement(sql)) {
            bind(statement, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int itemId = rows.getInt("item_id");
                    FlippingItem item = getOrCreateItem(data, displayName, itemId, items);
                    item.setValidFlippingPanelItem(rows.getBoolean("is_visible"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load item visibility for " + displayName, e);
        }
    }

    /** Deleting offer history does not reset an item's active GE limit window. */
    void restoreGeLimitStates(String displayName, AccountData data) {
        Map<Integer, FlippingItem> items = indexItems(data);
        for (Map.Entry<Integer, Map<String, Object>> entry : loadAllGeLimitStates(displayName).entrySet()) {
            int itemId = entry.getKey();
            FlippingItem item = getOrCreateItem(data, displayName, itemId, items);
            Map<String, Object> state = entry.getValue();
            item.getHistory().setNextGeLimitRefresh((Instant) state.get(NEXT_REFRESH));
            item.getHistory().setItemsBoughtThisLimitWindow((Integer) state.get(ITEMS_BOUGHT));
            item.getHistory().setItemsBoughtThroughCompleteOffers((Integer) state.get(ITEMS_BOUGHT_THROUGH_COMPLETE_OFFERS));
        }
    }

    private Map<Integer, FlippingItem> indexItems(AccountData data) {
        Map<Integer, FlippingItem> items = new HashMap<>();
        for (FlippingItem item : data.getTrades()) {
            items.put(item.getItemId(), item);
        }
        return items;
    }

    private FlippingItem getOrCreateItem(AccountData data, String displayName, int itemId,
                                        Map<Integer, FlippingItem> items) {
        return items.computeIfAbsent(itemId, id -> {
            FlippingItem item = new FlippingItem(id, "Item " + id, 70, displayName);
            data.getTrades().add(item);
            return item;
        });
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

        final String sql = "SELECT item_id, next_refresh, items_bought, items_bought_complete " +
            "FROM ge_limit_state WHERE account_id = ?";
        try {
            Connection conn = storage.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> state = new HashMap<>(4);
                        long nextRefreshMillis = rs.getLong("next_refresh");
                        state.put(NEXT_REFRESH, rs.wasNull() ? null : Instant.ofEpochMilli(nextRefreshMillis));
                        state.put(ITEMS_BOUGHT, rs.getInt("items_bought"));
                        state.put(ITEMS_BOUGHT_THROUGH_COMPLETE_OFFERS, rs.getInt("items_bought_complete"));
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

        final String sql = "INSERT INTO ge_limit_state " +
            "(account_id, item_id, next_refresh, items_bought, items_bought_complete) " +
            "VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT(account_id, item_id) DO UPDATE SET " +
            "next_refresh = excluded.next_refresh, items_bought = excluded.items_bought, " +
            "items_bought_complete = excluded.items_bought_complete";

        try {
            Connection conn = storage.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, accountId, itemId, nextRefresh == null ? null : nextRefresh.toEpochMilli(),
                    itemsBought, itemsBoughtThroughCompleteOffers);
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

        String sql = "INSERT OR REPLACE INTO item_favorites (account_id, item_id, is_favorite, favorite_code) " +
            "VALUES (?, ?, ?, ?)";
        try {
            Connection conn = storage.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, accountId, itemId, isFavorite,
                    favoriteCode != null ? favoriteCode : FlippingItem.DEFAULT_FAVORITE_CODE);
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
                bind(ps, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int itemId = rs.getInt("item_id");
                        Map<String, Object> fav = new HashMap<>(2);
                        fav.put(IS_FAVORITE, rs.getInt("is_favorite") == 1);
                        fav.put(FAVORITE_CODE, rs.getString("favorite_code"));
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
