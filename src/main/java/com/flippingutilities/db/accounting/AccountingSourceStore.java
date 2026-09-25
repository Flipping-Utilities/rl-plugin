package com.flippingutilities.db.accounting;

import com.flippingutilities.accounting.AccountingRecipe;
import com.flippingutilities.accounting.AccountingSource;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.model.OfferEvent;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Canonical observations and repeatable batch normalization. Caller owns the write transaction. */
final class AccountingSourceStore {
    private AccountingSourceStore() { }

    static void state(Connection connection, long accountId) throws SQLException {
        execute(connection, "INSERT OR IGNORE INTO accounting_state(account_id) VALUES (?)", accountId);
    }

    static void changed(Connection connection, long accountId) throws SQLException {
        state(connection, accountId);
        execute(connection, "UPDATE accounting_state SET source_revision=source_revision+1,dirty=1 WHERE account_id=?", accountId);
    }

    static boolean capture(Connection connection, long accountId, OfferEvent offer,
                           List<String> replaced, boolean reportable, boolean restricted) throws SQLException {
        if (offer == null || offer.getItemId() <= 0 || offer.getCurrentQuantityInTrade() < 0) return false;
        state(connection, accountId);
        String payload = SqliteStorage.serializeOffer(offer);
        if (offer.getItemName() != null && !offer.getItemName().trim().isEmpty()) {
            execute(connection, "INSERT INTO accounting_items(item_id,item_name) VALUES (?,?) ON CONFLICT(item_id) DO UPDATE SET item_name=excluded.item_name",
                offer.getItemId(), offer.getItemName());
        }
        String alias = offer.getUuid() == null ? stable("legacy:" + accountId + ":" + payload) : offer.getUuid();
        String observationId = alias;
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT payload FROM accounting_observations WHERE account_id=? AND source_uuid=? ORDER BY sequence DESC LIMIT 1")) {
            bind(query, accountId, alias);
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    if (row.getString(1).equals(payload)) return false;
                    // A reused UUID with changed content is retained as a correction, never overwritten.
                    observationId = alias + ":revision:" + stable(payload);
                }
            }
        }
        if (exists(connection, "SELECT 1 FROM accounting_observations WHERE account_id=? AND observation_id=?", accountId, observationId)) return false;
        String predecessor = offer.getPredecessorUuid();
        if (predecessor == null && replaced != null && !replaced.isEmpty()) predecessor = replaced.get(0);
        String order = orderFor(connection, accountId, predecessor);
        if (order == null) order = orderFor(connection, accountId, alias);
        if (order == null) order = offer.getOrderId() == null ? "order:" + alias : offer.getOrderId();
        execute(connection, "INSERT OR IGNORE INTO accounting_orders(account_id,order_id) VALUES (?,?)", accountId, order);
        Long priorQuantity = null;
        Long priorAmount = null;
        Long priorTax = null;
        boolean priorEstimated = false;
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT quantity,amount_gp,tax_gp,estimated FROM accounting_observations WHERE account_id=? AND order_id=? ORDER BY sequence DESC LIMIT 1")) {
            bind(query, accountId, order);
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    priorQuantity = row.getLong(1);
                    priorAmount = nullableLong(row, 2);
                    priorTax = nullableLong(row, 3);
                    priorEstimated = row.getBoolean(4);
                }
            }
        }
        long quantity = offer.getCurrentQuantityInTrade();
        Long amount = offer.getCumulativeAmount();
        boolean estimated = amount == null;
        if (amount == null && offer.getPreTaxPrice() >= 0) amount = Math.multiplyExact(quantity, (long) offer.getPreTaxPrice());
        // Client cumulative money is preserved exactly; per-unit legacy tax is explicitly estimated.
        Long tax = null;
        if (offer.isBuy()) tax = 0L;
        else if (offer.getTime() != null) tax = Math.multiplyExact(quantity, (long) offer.getTaxPaidPerItem());
        Instant effective = offer.getObservedAt() == null ? offer.getTime() : offer.getObservedAt();
        long sequence;
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO accounting_observations(account_id,observation_id,source_uuid,order_id,predecessor_uuid,item_id,is_buy,quantity," +
                "amount_gp,tax_gp,observed_at,margin_eligible,estimated,restricted,enriched,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, accountId, observationId, alias, order, predecessor, offer.getItemId(), offer.isBuy(), quantity,
                amount, tax, epoch(effective), offer.isMarginCheck(), estimated || !offer.isBuy(), restricted,
                offer.getCumulativeAmount() != null || offer.getOrderId() != null || offer.getObservedAt() != null, payload);
            insert.executeUpdate();
            try (ResultSet row = insert.getGeneratedKeys()) {
                if (!row.next()) throw new SQLException("Missing observation sequence");
                sequence = row.getLong(1);
            }
        }
        long before = priorQuantity == null ? 0 : priorQuantity;
        boolean appendOnly = false;
        if (!reportable && !restricted) {
            execute(connection, "UPDATE accounting_orders SET suppressed_quantity=MAX(suppressed_quantity,?) WHERE account_id=? AND order_id=?",
                quantity, accountId, order);
        }
        if (quantity > before && (priorAmount == null || amount == null || amount >= priorAmount)) {
            long delta = quantity - before;
            Long deltaAmount = difference(amount, priorAmount, before);
            Long deltaTax = difference(tax, priorTax, before);
            if (reportable || restricted) {
                appendOnly = effective != null && !offer.isMarginCheck() && !restricted;
                execute(connection, "INSERT INTO accounting_sources(account_id,source_id,order_id,item_id,is_buy,quantity,amount_gp,tax_gp," +
                        "recognized_at,sequence,margin_eligible,estimated,restricted) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    accountId, observationId, order, offer.getItemId(), offer.isBuy(), delta, deltaAmount, deltaTax,
                    epoch(effective), sequence, offer.isMarginCheck() && before == 0, estimated || priorEstimated || !offer.isBuy(), restricted);
            }
        } else if (quantity < before || !Objects.equals(amount, priorAmount) || !Objects.equals(tax, priorTax)) {
            if (priorQuantity != null) correct(connection, accountId, order, quantity, amount, tax);
        } else {
            // Completion can turn a one-unit order into a margin check without adding another fill.
            execute(connection, "UPDATE accounting_sources SET margin_eligible=? WHERE account_id=? AND order_id=? AND quantity=1",
                offer.isMarginCheck() && quantity == 1, accountId, order);
        }
        changed(connection, accountId);
        execute(connection, "INSERT INTO accounting_dirty_items(account_id,item_id,append_only) VALUES (?,?,?) " +
            "ON CONFLICT(account_id,item_id) DO UPDATE SET append_only=MIN(accounting_dirty_items.append_only,excluded.append_only)", accountId, offer.getItemId(), appendOnly);
        return true;
    }

    private static void correct(Connection connection, long accountId, String order, long quantity,
                                Long amount, Long tax) throws SQLException {
        long count;
        long suppressed;
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT (SELECT COUNT(*) FROM accounting_sources WHERE account_id=? AND order_id=?),suppressed_quantity " +
                "FROM accounting_orders WHERE account_id=? AND order_id=?")) {
            bind(query, accountId, order, accountId, order);
            try (ResultSet row = query.executeQuery()) { row.next(); count = row.getLong(1); suppressed = row.getLong(2); }
        }
        if (count == 1 && suppressed == 0 && quantity > 0) {
            // A single aggregate has unambiguous lineage. Keep its original recognition timestamp.
            execute(connection, "UPDATE accounting_sources SET quantity=?,amount_gp=?,tax_gp=?,estimated=1 WHERE account_id=? AND order_id=?",
                quantity, amount, tax, accountId, order);
            return;
        }
        execute(connection, "UPDATE accounting_orders SET conflicted=1 WHERE account_id=? AND order_id=?", accountId, order);
        execute(connection, "UPDATE accounting_sources SET amount_gp=NULL,tax_gp=NULL,estimated=1 WHERE account_id=? AND order_id=?", accountId, order);
        // Retain only provable quantity, oldest segments first. Ambiguous value remains unavailable.
        long remaining = Math.max(0, quantity - suppressed);
        List<Object[]> batches = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT source_id,quantity FROM accounting_sources WHERE account_id=? AND order_id=? ORDER BY sequence")) {
            bind(query, accountId, order);
            try (ResultSet row = query.executeQuery()) { while (row.next()) batches.add(new Object[]{row.getString(1), row.getLong(2)}); }
        }
        for (Object[] batch : batches) {
            long retained = Math.min(remaining, (Long) batch[1]);
            if (retained == 0) execute(connection, "DELETE FROM accounting_sources WHERE account_id=? AND source_id=?", accountId, batch[0]);
            else execute(connection, "UPDATE accounting_sources SET quantity=? WHERE account_id=? AND source_id=?", retained, accountId, batch[0]);
            remaining -= retained;
        }
    }

    static void suppress(Connection connection, long accountId, List<String> aliases) throws SQLException {
        for (String alias : aliases) {
            String order = orderFor(connection, accountId, alias);
            if (order == null) continue;
            execute(connection, "UPDATE accounting_orders SET suppressed_quantity=MAX(suppressed_quantity," +
                "COALESCE((SELECT quantity FROM accounting_observations WHERE account_id=? AND order_id=? ORDER BY sequence DESC LIMIT 1),0)) " +
                "WHERE account_id=? AND order_id=?", accountId, order, accountId, order);
            execute(connection, "DELETE FROM accounting_sources WHERE account_id=? AND order_id=?", accountId, order);
            execute(connection, "INSERT OR IGNORE INTO accounting_dirty_items(account_id,item_id) SELECT account_id,item_id " +
                "FROM accounting_observations WHERE account_id=? AND order_id=?", accountId, order);
            execute(connection, "UPDATE accounting_dirty_items SET append_only=0 WHERE account_id=? AND item_id IN " +
                "(SELECT item_id FROM accounting_observations WHERE account_id=? AND order_id=?)", accountId, accountId, order);
            changed(connection, accountId);
        }
    }

    static String orderFor(Connection connection, long accountId, String alias) throws SQLException {
        if (alias == null) return null;
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT order_id FROM accounting_observations WHERE account_id=? AND source_uuid=? ORDER BY sequence DESC LIMIT 1")) {
            bind(query, accountId, alias);
            try (ResultSet row = query.executeQuery()) { return row.next() ? row.getString(1) : null; }
        }
    }

    static List<AccountingSource> sources(Connection connection, long accountId) throws SQLException {
        List<AccountingSource> sources = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT * FROM accounting_sources WHERE account_id=? ORDER BY recognized_at,sequence")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) sources.add(new AccountingSource(row.getString("source_id"), row.getString("order_id"), accountId,
                    row.getInt("item_id"), row.getBoolean("is_buy"), row.getLong("quantity"), nullableLong(row, "amount_gp"),
                    nullableLong(row, "tax_gp"), instant(row, "recognized_at"), row.getLong("sequence"), row.getBoolean("margin_eligible"),
                    row.getBoolean("restricted"), row.getBoolean("estimated")));
            }
        }
        return sources;
    }

    static void syncRecipes(Connection connection, long accountId) throws SQLException {
        java.util.Map<Long, String> recipeIds = new java.util.LinkedHashMap<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT id,natural_key FROM recipe_flips WHERE account_id=? ORDER BY id")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) { while (row.next()) recipeIds.put(row.getLong(1), row.getString(2)); }
        }
        for (java.util.Map.Entry<Long, String> entry : recipeIds.entrySet()) {
            long id = entry.getKey();
            String recipeId = entry.getValue();
            if (exists(connection, "SELECT 1 FROM accounting_recipes WHERE account_id=? AND recipe_id=?", accountId, recipeId)) continue;
            execute(connection, "INSERT INTO accounting_recipes(account_id,recipe_id,storage_recipe_id,recipe_key,recorded_at,coin_cost_gp,definition_json,execution_count) " +
                "SELECT account_id,?,id,recipe_key,timestamp,coin_cost,definition_json,execution_count FROM recipe_flips WHERE id=? AND account_id=?", recipeId, id, accountId);
            for (boolean input : new boolean[]{true, false}) {
                String table = input ? "recipe_flip_inputs" : "recipe_flip_outputs";
                try (PreparedStatement query = connection.prepareStatement("SELECT * FROM " + table + " WHERE recipe_flip_id=? ORDER BY id")) {
                    bind(query, id);
                    try (ResultSet row = query.executeQuery()) {
                        while (row.next()) {
                            if (row.getLong("amount_consumed") <= 0) continue;
                            String componentId = (input ? "input:" : "output:") + row.getLong("id");
                            String alias = row.getString("offer_uuid");
                            String payload = row.getString("offer_json");
                            OfferEvent evidence = SqliteStorage.deserializeOffer(payload);
                            if (orderFor(connection, accountId, alias) == null && evidence != null) {
                                capture(connection, accountId, evidence, Collections.emptyList(), true, true);
                                alias = evidence.getUuid() == null ? stable("legacy:" + accountId + ":" + SqliteStorage.serializeOffer(evidence)) : evidence.getUuid();
                            }
                            execute(connection, "INSERT INTO accounting_recipe_components(account_id,recipe_id,component_id,source_uuid,item_id," +
                                "is_input,quantity,payload) VALUES (?,?,?,?,?,?,?,?)", accountId, recipeId, componentId, alias,
                                row.getInt("item_id"), input, row.getLong("amount_consumed"), payload);
                        }
                    }
                }
            }
            changed(connection, accountId);
        }
        execute(connection, "INSERT OR IGNORE INTO accounting_dirty_items(account_id,item_id) SELECT c.account_id,c.item_id " +
            "FROM accounting_recipe_components c JOIN accounting_recipes r ON r.account_id=c.account_id AND r.recipe_id=c.recipe_id " +
            "WHERE c.account_id=? AND r.deleted=0", accountId);
        int removed = execute(connection, "UPDATE accounting_recipes SET deleted=1 WHERE account_id=? AND deleted=0 AND recipe_id " +
            "NOT IN (SELECT natural_key FROM recipe_flips WHERE account_id=?)", accountId, accountId);
        if (removed > 0) {
            changed(connection, accountId);
            execute(connection, "INSERT OR IGNORE INTO accounting_dirty_items(account_id,item_id) SELECT account_id,item_id " +
                "FROM accounting_recipe_components WHERE account_id=?", accountId);
        }
        execute(connection, "UPDATE accounting_dirty_items SET append_only=0 WHERE account_id=? AND item_id IN " +
            "(SELECT item_id FROM accounting_recipe_components WHERE account_id=?)", accountId, accountId);
    }

    static List<AccountingRecipe> recipes(Connection connection, long accountId) throws SQLException {
        List<AccountingRecipe> recipes = new ArrayList<>();
        java.util.Map<String, Long> reserved = new java.util.HashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT * FROM accounting_recipes WHERE account_id=? AND deleted=0 ORDER BY recorded_at,storage_recipe_id")) {
            bind(query, accountId);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    String recipeId = row.getString("recipe_id");
                    recipes.add(new AccountingRecipe(recipeId, accountId, instant(row, "recorded_at"),
                        components(connection, accountId, recipeId, true, reserved), components(connection, accountId, recipeId, false, reserved),
                        nullableLong(row, "coin_cost_gp")));
                }
            }
        }
        return recipes;
    }

    private static List<AccountingRecipe.Component> components(Connection connection, long accountId, String recipeId,
                                                               boolean input, java.util.Map<String, Long> reserved) throws SQLException {
        List<AccountingRecipe.Component> result = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT * FROM accounting_recipe_components WHERE account_id=? AND recipe_id=? AND is_input=? ORDER BY component_id")) {
            bind(query, accountId, recipeId, input);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    String alias = row.getString("source_uuid");
                    String order = orderFor(connection, accountId, alias);
                    long remaining = row.getLong("quantity");
                    try (PreparedStatement batches = connection.prepareStatement(
                        "SELECT source_id,quantity FROM accounting_sources WHERE account_id=? AND order_id=? AND is_buy=? AND item_id=? " +
                            "AND sequence<=(SELECT MAX(sequence) FROM accounting_observations WHERE account_id=? AND source_uuid=?) ORDER BY sequence")) {
                        bind(batches, accountId, order, input, row.getInt("item_id"), accountId, alias);
                        try (ResultSet batch = batches.executeQuery()) {
                            while (batch.next() && remaining > 0) {
                                long alreadyReserved = reserved.getOrDefault(batch.getString("source_id"), 0L);
                                long used = Math.min(remaining, batch.getLong("quantity") - alreadyReserved);
                                if (used <= 0) continue;
                                result.add(new AccountingRecipe.Component(batch.getString("source_id"), used));
                                reserved.put(batch.getString("source_id"), Math.addExact(alreadyReserved, used));
                                remaining -= used;
                            }
                        }
                    }
                    if (remaining > 0) result.add(new AccountingRecipe.Component("missing:" + recipeId + ":" + row.getString("component_id"), remaining));
                    if (evidenceConflict(connection, accountId, order, alias, row.getString("payload"))) {
                        // Reserve real quantities, but do not silently revalue a historical declaration from conflicting evidence.
                        result.add(new AccountingRecipe.Component("missing:evidence:" + recipeId + ":" + row.getString("component_id"), 1));
                    }
                }
            }
        }
        return result;
    }

    private static boolean evidenceConflict(Connection connection, long accountId, String order, String alias, String payload) throws SQLException {
        OfferEvent snapshot = SqliteStorage.deserializeOffer(payload);
        if (snapshot == null || order == null) return false;
        try (PreparedStatement query = connection.prepareStatement("SELECT COALESCE(SUM(quantity),0),CASE WHEN COUNT(*)=COUNT(amount_gp) THEN SUM(amount_gp) END " +
            "FROM accounting_sources WHERE account_id=? AND order_id=? AND sequence<=(SELECT MAX(sequence) FROM accounting_observations WHERE account_id=? AND source_uuid=?)")) {
            bind(query, accountId, order, accountId, alias);
            try (ResultSet row = query.executeQuery()) {
                row.next();
                long quantity = snapshot.getCurrentQuantityInTrade();
                Long expected = snapshot.getCumulativeAmount();
                if (expected == null && snapshot.getPreTaxPrice() >= 0) expected = Math.multiplyExact(quantity, (long) snapshot.getPreTaxPrice());
                return row.getLong(1) != quantity || !Objects.equals(nullableLong(row, 2), expected);
            }
        }
    }

    private static Long difference(Long current, Long previous, long priorQuantity) {
        if (current == null || (priorQuantity > 0 && previous == null)) return null;
        long delta = Math.subtractExact(current, previous == null ? 0 : previous);
        return delta < 0 ? null : delta;
    }

    static String stable(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    static Long epoch(Instant value) { return value == null ? null : value.toEpochMilli(); }
    static Instant instant(ResultSet row, String column) throws SQLException {
        Long value = nullableLong(row, column);
        return value == null ? null : Instant.ofEpochMilli(value);
    }
    static Long nullableLong(ResultSet row, String column) throws SQLException { long value = row.getLong(column); return row.wasNull() ? null : value; }
    static Long nullableLong(ResultSet row, int column) throws SQLException { long value = row.getLong(column); return row.wasNull() ? null : value; }
    static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i] instanceof Boolean ? ((Boolean) values[i] ? 1 : 0) : values[i]);
    }
    static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { bind(statement, values); return statement.executeUpdate(); }
    }
    static boolean exists(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { bind(statement, values); try (ResultSet row = statement.executeQuery()) { return row.next(); } }
    }
}
