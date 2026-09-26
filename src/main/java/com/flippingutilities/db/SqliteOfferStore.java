package com.flippingutilities.db;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offer history and active slots, including their atomic update and deletion paths.
 * Called only while the owning SqliteStorage monitor is held; shares its connection.
 */
final class SqliteOfferStore {
    private static final Logger logger = LoggerFactory.getLogger(SqliteOfferStore.class);

    private final SqliteStorage storage;
    private final SqliteItemStateStore itemState;
    private final SqliteRecipeStore recipes;

    SqliteOfferStore(SqliteStorage storage, SqliteItemStateStore itemState, SqliteRecipeStore recipes) {
        this.storage = storage;
        this.itemState = itemState;
        this.recipes = recipes;
    }

    /**
     * Load trade items from SQLite, reconstructing FlippingItem objects with history.
     */
    List<FlippingItem> loadTradeItems(int accountId, String displayName, Map<Integer, OfferEvent> slots) {
        Map<Integer, List<OfferEvent>> offersByItem = new HashMap<>();

        // Load original offers, including those fully consumed by recipes. Recipe
        // consumption is applied by the account model without removing stored history.
        String sql = "SELECT item_id, offer_json FROM trades WHERE account_id = :accountId ORDER BY item_id, timestamp, id";
        try {
            Connection conn = storage.getConnection();
            try (NamedStatement ps = NamedStatement.prepare(conn, sql)) {
                ps.bind("accountId", accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int itemId = rs.getInt("item_id");
                        OfferEvent offer = OfferJsonCodec.deserializeOffer(rs.getString("offer_json"));
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

        // Convert to FlippingItem objects, restoring favorites where present.
        Map<Integer, Map<String, Object>> favorites = itemState.loadAllFavorites(displayName);
        List<FlippingItem> items = new ArrayList<>();
        for (Map.Entry<Integer, List<OfferEvent>> entry : offersByItem.entrySet()) {
            int itemId = entry.getKey();
            List<OfferEvent> offers = entry.getValue();
            offers.sort(Comparator.comparing(OfferEvent::getTime, Comparator.nullsFirst(Comparator.naturalOrder())));

            FlippingItem item = new FlippingItem(itemId, "Item " + itemId, 70, displayName);
            // Restore persisted favorite state (M4: favorites round-trip across reloads)
            Map<String, Object> fav = favorites.get(itemId);
            if (fav != null) {
                Object isFavorite = fav.get(SqliteItemStateStore.IS_FAVORITE);
                if (isFavorite instanceof Boolean && (Boolean) isFavorite) {
                    item.setFavorite(true);
                }
                Object favoriteCode = fav.get(SqliteItemStateStore.FAVORITE_CODE);
                if (favoriteCode instanceof String) {
                    item.setFavoriteCode((String) favoriteCode);
                }
            }
            item.getHistory().getCompressedOfferEvents().addAll(offers);
            items.add(item);
        }

        return items;
    }

    /** Records the original offer, preserving classification and continuity across reloads. */
    void recordTrade(String displayName, OfferEvent offer) {
        int accountId = storage.getOrCreateAccountId(displayName);
        // Repeated writes of the same snapshot are idempotent. Successive live GE events
        // have different UUIDs; recordOfferUpdate removes their exact replaced snapshots.
        String sql = "INSERT INTO trades " +
            "(account_id, item_id, uuid, timestamp, qty, price, is_buy, offer_json) " +
            "VALUES (:accountId, :itemId, :uuid, :timestamp, :quantity, :price, :buy, :offerJson) " +
            "ON CONFLICT(account_id, uuid) DO UPDATE SET " +
            "timestamp = excluded.timestamp, qty = excluded.qty, price = excluded.price, " +
            "offer_json = excluded.offer_json " +
            "WHERE excluded.qty >= trades.qty";
        try (NamedStatement statement = NamedStatement.prepare(storage.getConnection(), sql)) {
            statement.bind("accountId", accountId);
            statement.bind("itemId", offer.getItemId());
            statement.bind("uuid", offer.getUuid());
            statement.bind("timestamp", offer.getTime() == null ? 0L : offer.getTime().toEpochMilli());
            statement.bind("quantity", offer.getCurrentQuantityInTrade());
            statement.bind("price", offer.getPreTaxPrice());
            statement.bind("buy", offer.isBuy() ? 1 : 0);
            statement.bind("offerJson", OfferJsonCodec.serializeOffer(offer));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record trade for " + displayName, e);
        }
    }

    /**
     * Upsert an active slot with an offer event.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     * @param offer The offer event to store, including completed offers awaiting collection
     */
    void upsertSlot(String displayName, int slotIndex, OfferEvent offer, boolean historyVisible) {
        if (offer == null || offer.isCausedByEmptySlot()) {
            clearSlot(displayName, slotIndex);
            return;
        }
        int accountId = storage.getOrCreateAccountId(displayName);

        final String sql = "INSERT OR REPLACE INTO active_slots " +
            "(account_id, slot_index, offer_uuid, offer_json, history_visible) " +
            "VALUES (:accountId, :slotIndex, :offerUuid, :offerJson, :historyVisible)";
        try (NamedStatement ps = NamedStatement.prepare(storage.getConnection(), sql)) {
            ps.bind("accountId", accountId);
            ps.bind("slotIndex", slotIndex);
            ps.bind("offerUuid", offer.getUuid());
            ps.bind("offerJson", OfferJsonCodec.serializeOffer(offer));
            ps.bind("historyVisible", historyVisible ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not persist active slot for " + displayName, e);
        }
    }

    Map<Integer, OfferEvent> loadAllSlots(String displayName, Map<Integer, OfferEvent> partialHistory) {
        Integer accountId = storage.getAccountId(displayName);
        Map<Integer, OfferEvent> slots = new HashMap<>();
        if (accountId == null) {
            return slots;
        }

        final String sql = "SELECT slot_index, offer_json, history_visible " +
            "FROM active_slots WHERE account_id = :accountId";

        try {
            Connection conn = storage.getConnection();
            try (NamedStatement ps = NamedStatement.prepare(conn, sql)) {
                ps.bind("accountId", accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int idx = rs.getInt("slot_index");
                        OfferEvent offer = OfferJsonCodec.deserializeOffer(rs.getString("offer_json"));
                        offer.setMadeBy(displayName);

                        if (!offer.isCausedByEmptySlot()) {
                            slots.put(idx, offer);
                            // Completed offers already belong to trades. Keeping their slot
                            // snapshot must not resurrect deleted history or add a duplicate.
                            if (!offer.isComplete() && rs.getBoolean("history_visible")) {
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
    void clearSlot(String displayName, int slotIndex) {
        Integer accountId = storage.getAccountId(displayName);
        if (accountId == null) {
            return;
        }

        final String sql = "DELETE FROM active_slots WHERE account_id = :accountId AND slot_index = :slotIndex";
        try {
            Connection conn = storage.getConnection();
            try (NamedStatement ps = NamedStatement.prepare(conn, sql)) {
                ps.bind("accountId", accountId);
                ps.bind("slotIndex", slotIndex);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error clearing active slot", e);
        }
    }

    /** Applies the live model's exact history replacement without deleting recipe snapshots. */
    void recordOfferUpdate(String displayName, OfferEvent offer, List<String> replacedUuids) {
        persistOfferHistory(displayName, offer, replacedUuids, null);
    }

    /** Retains a collected fill even when the last event was a partial cancellation correction. */
    void archiveOfferAndClearSlot(String displayName, int slotIndex, OfferEvent archived) {
        persistOfferHistory(displayName, archived, Collections.emptyList(), slotIndex);
    }

    private void persistOfferHistory(String displayName, OfferEvent offer, List<String> replacedUuids,
                                     Integer clearedSlot) {
        int accountId = storage.getOrCreateAccountId(displayName);
        try {
            Connection conn = storage.getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (int i = 0; i < replacedUuids.size(); i += 500) {
                    List<String> chunk = replacedUuids.subList(i, Math.min(i + 500, replacedUuids.size()));
                    String placeholders = NamedStatement.placeholders("uuid", chunk.size());
                    try (NamedStatement ps = NamedStatement.prepare(conn,
                        "DELETE FROM trades WHERE account_id = :accountId AND uuid IN (" + placeholders + ")")) {
                        bindAccountUuids(ps, accountId, chunk);
                        ps.executeUpdate();
                    }
                }
                if (offer != null && offer.getCurrentQuantityInTrade() > 0) {
                    // Accepted partials also retain a history snapshot. Future events remove
                    // their exact predecessors; clearing a slot must not erase filled units.
                    recordTrade(displayName, offer);
                }
                if (clearedSlot != null) {
                    clearSlot(displayName, clearedSlot);
                } else {
                    upsertSlot(displayName, offer.getSlot(), offer, true);
                    itemState.upsertItemVisibility(displayName, offer.getItemId(), true);
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error persisting offer history", e);
        }
    }

    /** Deletes offers and every recipe that references them, scoped to one account. */
    void deleteTradesByUuid(String displayName, List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return;
        }
        Integer accountId = storage.getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        try {
            Connection conn = storage.getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (int i = 0; i < uuids.size(); i += 500) {
                    List<String> chunk = uuids.subList(i, Math.min(i + 500, uuids.size()));
                    String placeholders = NamedStatement.placeholders("uuid", chunk.size());
                    // Preserve active-slot continuity while hiding the deleted partial fill.
                    try (NamedStatement ps = NamedStatement.prepare(conn,
                        "UPDATE active_slots SET history_visible = 0 WHERE account_id = :accountId AND offer_uuid IN (" + placeholders + ")")) {
                        bindAccountUuids(ps, accountId, chunk);
                        ps.executeUpdate();
                    }
                    Set<Long> recipeIds = new HashSet<>();
                    for (RecipeComponentTable table : RecipeComponentTable.values()) {
                        try (NamedStatement ps = NamedStatement.prepare(conn,
                            "SELECT DISTINCT c.recipe_flip_id FROM " + table.tableName() + " c " +
                            "JOIN recipe_flips rf ON rf.id = c.recipe_flip_id " +
                            "WHERE rf.account_id = :accountId AND c.offer_uuid IN (" + placeholders + ")")) {
                            bindAccountUuids(ps, accountId, chunk);
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) {
                                    recipeIds.add(rs.getLong(1));
                                }
                            }
                        }
                    }
                    recipes.deleteRecipeFlipsById(conn, new ArrayList<>(recipeIds));
                    try (NamedStatement ps = NamedStatement.prepare(conn,
                        "DELETE FROM trades WHERE account_id = :accountId AND uuid IN (" + placeholders + ")")) {
                        bindAccountUuids(ps, accountId, chunk);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
                logger.info("Deleted SQLite offers by uuid for {}", displayName);
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

    private void bindAccountUuids(NamedStatement statement, int accountId, List<String> uuids) throws SQLException {
        statement.bind("accountId", accountId).bindList("uuid", uuids);
    }
}
