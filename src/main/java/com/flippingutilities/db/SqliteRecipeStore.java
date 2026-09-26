package com.flippingutilities.db;

import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe flip snapshots and their component persistence.
 * Instance methods run under the owning SqliteStorage monitor and share its connection.
 * The static migration writer uses its caller's connection and transaction.
 */
final class SqliteRecipeStore {
    private static final Logger logger = LoggerFactory.getLogger(SqliteRecipeStore.class);

    private final SqliteStorage storage;

    SqliteRecipeStore(SqliteStorage storage) {
        this.storage = storage;
    }

    /**
     * Load recipe flip groups from SQLite for an account.
     */
    List<RecipeFlipGroup> loadRecipeFlipGroups(int accountId, String displayName) {
        List<RecipeFlipGroup> groups = new ArrayList<>();

        String groupSql = "SELECT id, recipe_key, coin_cost, timestamp FROM recipe_flips " +
            "WHERE account_id = ? ORDER BY recipe_key, timestamp";

        try {
            Connection conn = storage.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(groupSql)) {
                ps.setInt(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, RecipeFlipGroup> groupMap = new HashMap<>();

                    while (rs.next()) {
                        String recipeKey = rs.getString("recipe_key");
                        long timestamp = rs.getLong("timestamp");
                        long coinCost = rs.getLong("coin_cost");
                        long recipeFlipId = rs.getLong("id");

                        // Get or create the group
                        RecipeFlipGroup group = groupMap.computeIfAbsent(recipeKey, RecipeFlipGroup::new);

                        // Load inputs and outputs for this recipe flip
                        Map<Integer, Map<String, PartialOffer>> inputs = loadRecipeFlipComponents(recipeFlipId, displayName, true);
                        Map<Integer, Map<String, PartialOffer>> outputs = loadRecipeFlipComponents(recipeFlipId, displayName, false);

                        // Create RecipeFlip
                        RecipeFlip flip = new RecipeFlip(
                            Instant.ofEpochMilli(timestamp),
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
        try (PreparedStatement ps = storage.getConnection().prepareStatement(sql)) {
            ps.setLong(1, recipeFlipId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    String uuid = rs.getString("offer_uuid");
                    OfferEvent offer = OfferJsonCodec.deserializeOffer(rs.getString("offer_json"));
                    if (offer != null) {
                        offer.setMadeBy(displayName);
                        offer.setItemName("Item " + itemId);
                    }
                    PartialOffer component = new PartialOffer(uuid, rs.getInt("amount_consumed"));
                    component.setOffer(offer);
                    components.computeIfAbsent(itemId, ignored -> new HashMap<>()).put(uuid, component);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Error loading recipe components", e);
        }
        return components;
    }

    /**
     * Delete a single recipe flip (the per-flip delete button in the recipe panel) together
     * with its components. Identified by the same natural key that both
     * the migration and insertRecipeFlip use, so migrated and live-created flips are covered.
     */
    void deleteRecipeFlip(String displayName, String recipeKey, Instant timeOfCreation) {
        if (recipeKey == null || timeOfCreation == null) {
            return;
        }
        Integer accountId = storage.getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        String naturalKey = "recipe:" + accountId + ":" + recipeKey + ":" + timeOfCreation.toEpochMilli();
        try {
            Connection conn = storage.getConnection();
            Long recipeId = null;
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM recipe_flips WHERE account_id = ? AND natural_key = ?")) {
                ps.setInt(1, accountId);
                ps.setString(2, naturalKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        recipeId = rs.getLong(1);
                    }
                }
            }
            if (recipeId == null) {
                return;
            }
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                deleteRecipeFlipsById(conn, Collections.singletonList(recipeId));
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
    void deleteRecipeFlipsSince(String displayName, String recipeKey, Instant since) {
        if (recipeKey == null) {
            return;
        }
        Integer accountId = storage.getAccountId(displayName);
        if (accountId == null) {
            return;
        }
        long sinceMillis = since != null ? since.toEpochMilli() : 0L;
        try {
            Connection conn = storage.getConnection();
            List<Long> recipeIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM recipe_flips WHERE account_id = ? AND recipe_key = ? AND timestamp > ?")) {
                ps.setInt(1, accountId);
                ps.setString(2, recipeKey);
                ps.setLong(3, sinceMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        recipeIds.add(rs.getLong(1));
                    }
                }
            }
            if (recipeIds.isEmpty()) {
                return;
            }
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                deleteRecipeFlipsById(conn, recipeIds);
                conn.commit();
                logger.info("Deleted {} SQLite recipe flips for {} [{}] since {}", recipeIds.size(), displayName, recipeKey, since);
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

    void deleteRecipeFlipsById(Connection conn, List<Long> recipeIds) throws SQLException {
        for (int i = 0; i < recipeIds.size(); i += 500) {
            List<Long> chunk = recipeIds.subList(i, Math.min(i + 500, recipeIds.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            execDeleteByLongs(conn, "DELETE FROM recipe_flip_inputs WHERE recipe_flip_id IN (" + placeholders + ")", chunk);
            execDeleteByLongs(conn, "DELETE FROM recipe_flip_outputs WHERE recipe_flip_id IN (" + placeholders + ")", chunk);
            execDeleteByLongs(conn, "DELETE FROM recipe_flips WHERE id IN (" + placeholders + ")", chunk);
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

    /** Persists a live recipe atomically, using the same writer as migration. */
    void insertRecipeFlip(String displayName, String recipeKey, RecipeFlip flip) {
        if (flip == null || flip.getTimeOfCreation() == null) {
            return;
        }
        int accountId = storage.getOrCreateAccountId(displayName);
        try {
            Connection conn = storage.getConnection();
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                insertRecipeFlip(conn, accountId, recipeKey, flip);
                conn.commit();
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

    /** Caller owns the transaction; returns false when this flip was already persisted. */
    static boolean insertRecipeFlip(Connection conn, int accountId, String recipeKey, RecipeFlip flip) throws SQLException {
        if (flip == null || flip.getTimeOfCreation() == null) {
            return false;
        }
        long timestamp = flip.getTimeOfCreation().toEpochMilli();
        String naturalKey = "recipe:" + accountId + ":" + recipeKey + ":" + timestamp;
        long recipeId;
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO recipe_flips (account_id, timestamp, recipe_key, coin_cost, natural_key) " +
            "VALUES (?, ?, ?, ?, ?) ON CONFLICT(natural_key) DO NOTHING", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, accountId);
            ps.setLong(2, timestamp);
            ps.setString(3, recipeKey);
            ps.setLong(4, flip.getCoinCost());
            ps.setString(5, naturalKey);
            // An ignored insert leaves a stale rowid in sqlite-jdbc's generated keys.
            if (ps.executeUpdate() == 0) {
                return false;
            }
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) {
                    throw new SQLException("Missing generated recipe id");
                }
                recipeId = rs.getLong(1);
            }
        }
        insertRecipeFlipComponents(conn, recipeId, flip.getInputs(), true);
        insertRecipeFlipComponents(conn, recipeId, flip.getOutputs(), false);
        return true;
    }

    private static void insertRecipeFlipComponents(Connection conn, long recipeId,
                                                   Map<Integer, Map<String, PartialOffer>> components, boolean inputs) throws SQLException {
        if (components == null) {
            return;
        }
        String table = inputs ? "recipe_flip_inputs" : "recipe_flip_outputs";
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + table +
            " (recipe_flip_id, item_id, offer_uuid, amount_consumed, offer_json) VALUES (?, ?, ?, ?, ?)")) {
            for (Map.Entry<Integer, Map<String, PartialOffer>> entry : components.entrySet()) {
                for (PartialOffer component : entry.getValue().values()) {
                    if (component == null || component.getAmountConsumed() <= 0) continue;
                    ps.setLong(1, recipeId);
                    ps.setInt(2, entry.getKey());
                    ps.setString(3, component.getOfferUuid());
                    ps.setInt(4, component.getAmountConsumed());
                    ps.setString(5, OfferJsonCodec.serializeRecipeOffer(component));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }
}
