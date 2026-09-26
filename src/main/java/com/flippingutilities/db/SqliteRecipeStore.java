package com.flippingutilities.db;

import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
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
 * Instance methods run within the owning SqliteStorage monitor and transaction.
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
            "WHERE account_id = :accountId ORDER BY recipe_key, timestamp";

        try {
            Connection conn = storage.getConnection();
            try (NamedStatement ps = NamedStatement.prepare(conn, groupSql)) {
                ps.bind("accountId", accountId);
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
                        Map<Integer, Map<String, PartialOffer>> inputs = loadRecipeFlipComponents(recipeFlipId, displayName, RecipeComponentTable.INPUTS);
                        Map<Integer, Map<String, PartialOffer>> outputs = loadRecipeFlipComponents(recipeFlipId, displayName, RecipeComponentTable.OUTPUTS);

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
                                                                           String displayName, RecipeComponentTable table) {
        Map<Integer, Map<String, PartialOffer>> components = new HashMap<>();
        String sql = "SELECT item_id, offer_uuid, amount_consumed, offer_json FROM " + table.tableName() +
            " WHERE recipe_flip_id = :recipeFlipId";
        try (NamedStatement ps = NamedStatement.prepare(storage.getConnection(), sql)) {
            ps.bind("recipeFlipId", recipeFlipId);
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
        String naturalKey = recipeNaturalKey(accountId, recipeKey, timeOfCreation.toEpochMilli());
        try {
            Connection conn = storage.getConnection();
            Long recipeId = null;
            try (NamedStatement ps = NamedStatement.prepare(conn,
                "SELECT id FROM recipe_flips WHERE account_id = :accountId AND natural_key = :naturalKey")) {
                ps.bind("accountId", accountId);
                ps.bind("naturalKey", naturalKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        recipeId = rs.getLong(1);
                    }
                }
            }
            if (recipeId == null) {
                return;
            }
            deleteRecipeFlipsById(conn, Collections.singletonList(recipeId));
            logger.info("Deleted SQLite recipe flip {} for {}", naturalKey, displayName);
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
            try (NamedStatement ps = NamedStatement.prepare(conn,
                "SELECT id FROM recipe_flips WHERE account_id = :accountId AND recipe_key = :recipeKey AND timestamp > :since")) {
                ps.bind("accountId", accountId);
                ps.bind("recipeKey", recipeKey);
                ps.bind("since", sinceMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        recipeIds.add(rs.getLong(1));
                    }
                }
            }
            if (recipeIds.isEmpty()) {
                return;
            }
            deleteRecipeFlipsById(conn, recipeIds);
            logger.info("Deleted {} SQLite recipe flips for {} [{}] since {}", recipeIds.size(), displayName, recipeKey, since);
        } catch (SQLException e) {
            throw new IllegalStateException("Error deleting recipe flips since ", e);
        }
    }

    void deleteRecipeFlipsById(Connection conn, List<Long> recipeIds) throws SQLException {
        for (int i = 0; i < recipeIds.size(); i += 500) {
            List<Long> chunk = recipeIds.subList(i, Math.min(i + 500, recipeIds.size()));
            String placeholders = NamedStatement.placeholders("recipeId", chunk.size());
            for (RecipeComponentTable table : RecipeComponentTable.values()) {
                execDeleteByLongs(conn, "DELETE FROM " + table.tableName() + " WHERE recipe_flip_id IN (" + placeholders + ")", chunk);
            }
            execDeleteByLongs(conn, "DELETE FROM recipe_flips WHERE id IN (" + placeholders + ")", chunk);
        }
    }

    private void execDeleteByLongs(Connection conn, String sql, List<Long> ids) throws SQLException {
        try (NamedStatement ps = NamedStatement.prepare(conn, sql)) {
            ps.bindList("recipeId", ids);
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
            insertRecipeFlip(conn, accountId, recipeKey, flip);
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
        String naturalKey = recipeNaturalKey(accountId, recipeKey, timestamp);
        long recipeId;
        try (NamedStatement ps = NamedStatement.prepare(conn,
            "INSERT INTO recipe_flips (account_id, timestamp, recipe_key, coin_cost, natural_key) " +
            "VALUES (:accountId, :timestamp, :recipeKey, :coinCost, :naturalKey) ON CONFLICT(natural_key) DO NOTHING",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.bind("accountId", accountId);
            ps.bind("timestamp", timestamp);
            ps.bind("recipeKey", recipeKey);
            ps.bind("coinCost", flip.getCoinCost());
            ps.bind("naturalKey", naturalKey);
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
        insertRecipeFlipComponents(conn, recipeId, flip.getInputs(), RecipeComponentTable.INPUTS);
        insertRecipeFlipComponents(conn, recipeId, flip.getOutputs(), RecipeComponentTable.OUTPUTS);
        return true;
    }

    private static void insertRecipeFlipComponents(Connection conn, long recipeId,
                                                   Map<Integer, Map<String, PartialOffer>> components,
                                                   RecipeComponentTable table) throws SQLException {
        if (components == null) {
            return;
        }
        try (NamedStatement ps = NamedStatement.prepare(conn, "INSERT INTO " + table.tableName() +
            " (recipe_flip_id, item_id, offer_uuid, amount_consumed, offer_json) " +
            "VALUES (:recipeId, :itemId, :offerUuid, :amountConsumed, :offerJson)")) {
            for (Map.Entry<Integer, Map<String, PartialOffer>> entry : components.entrySet()) {
                for (PartialOffer component : entry.getValue().values()) {
                    if (component == null || component.getAmountConsumed() <= 0) continue;
                    ps.bind("recipeId", recipeId);
                    ps.bind("itemId", entry.getKey());
                    ps.bind("offerUuid", component.getOfferUuid());
                    ps.bind("amountConsumed", component.getAmountConsumed());
                    ps.bind("offerJson", OfferJsonCodec.serializeRecipeOffer(component));
                    ps.addBatch();
                }
            }
            ps.executeBatch();
        }
    }

    private static String recipeNaturalKey(int accountId, String recipeKey, long timestamp) {
        return "recipe:" + accountId + ":" + recipeKey + ":" + timestamp;
    }
}
