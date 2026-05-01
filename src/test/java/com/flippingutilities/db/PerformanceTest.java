package com.flippingutilities.db;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PerformanceTest {
    private static final String DISPLAY_NAME = "PerfPlayer";
    private static final String PLAYER_ID = "perf-player-id";
    private static final int TOTAL_TRADES = 50_000;
    private static final int ITEM_COUNT = 100;
    private static final int FIRST_ITEM_ID = 10_000;
    private static final int TARGET_ITEM_ID = FIRST_ITEM_ID + 7;
    private static final int RECIPE_EVENT_COUNT = 200;
    private static final int RECIPE_GROUP_COUNT = 10;
    private static final int RECIPE_INPUTS_PER_EVENT = 2;
    private static final int QUERY_BUDGET_MS = 500;
    private static final long FIXTURE_WINDOW_MILLIS = Duration.ofDays(30).toMillis();
    private static final long QUERY_WINDOW_MILLIS = Duration.ofDays(15).toMillis();

    private File testDbFile;
    private SqliteStorage storage;
    private int accountId;
    private long baseTimestamp;
    private Instant querySince;

    @Before
    public void setUp() throws Exception {
        testDbFile = Files.createTempFile("perf_test_", ".db").toFile();
        testDbFile.deleteOnExit();
        storage = new SqliteStorage(testDbFile);
        storage.initializeSchema();
        populateFixture();
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
        if (testDbFile != null && testDbFile.exists()) {
            testDbFile.delete();
        }
    }

    @Test
    public void testKeyQueriesCompleteWithinPerformanceBudget() throws Exception {
        List<Map<String, Object>> trades = assertQueryUnderBudget(
            "loadTrades(displayName, since)",
            new QueryCall<List<Map<String, Object>>>() {
                @Override
                public List<Map<String, Object>> run() {
                    return storage.loadTrades(DISPLAY_NAME, querySince);
                }
            }
        );
        assertFalse("Filtered trades should not be empty", trades.isEmpty());

        List<Map<String, Object>> tradesByItem = assertQueryUnderBudget(
            "loadTradesByItem(displayName, itemId, since)",
            new QueryCall<List<Map<String, Object>>>() {
                @Override
                public List<Map<String, Object>> run() {
                    return storage.loadTradesByItem(DISPLAY_NAME, TARGET_ITEM_ID, querySince);
                }
            }
        );
        assertFalse("Per-item trades should not be empty", tradesByItem.isEmpty());

        List<Map<String, Object>> items = assertQueryUnderBudget(
            "queryItemsList(displayName, since, sortBy, limit, offset)",
            new QueryCall<List<Map<String, Object>>>() {
                @Override
                public List<Map<String, Object>> run() {
                    return storage.queryItemsList(DISPLAY_NAME, querySince, "PROFIT", 25, 0);
                }
            }
        );
        assertFalse("Paginated items list should not be empty", items.isEmpty());

        Map<String, Object> aggregateStats = assertQueryUnderBudget(
            "queryAggregateStats(displayName, since)",
            new QueryCall<Map<String, Object>>() {
                @Override
                public Map<String, Object> run() {
                    return storage.queryAggregateStats(DISPLAY_NAME, querySince);
                }
            }
        );
        assertTrue(
            "Aggregate stats should include flips",
            ((Number) aggregateStats.get("flipCount")).intValue() > 0
        );

        List<Map<String, Object>> recipeGroups = assertQueryUnderBudget(
            "queryRecipeFlipGroups(displayName, since)",
            new QueryCall<List<Map<String, Object>>>() {
                @Override
                public List<Map<String, Object>> run() {
                    return storage.queryRecipeFlipGroups(DISPLAY_NAME, querySince);
                }
            }
        );
        assertFalse("Recipe groups should not be empty", recipeGroups.isEmpty());

        List<Map<String, Object>> uniqueItems = assertQueryUnderBudget(
            "loadUniqueItems(displayName)",
            new QueryCall<List<Map<String, Object>>>() {
                @Override
                public List<Map<String, Object>> run() {
                    return storage.loadUniqueItems(DISPLAY_NAME);
                }
            }
        );
        assertTrue("Fixture should span many items", uniqueItems.size() >= ITEM_COUNT);
    }

    @Test
    public void testExplainQueryPlanForKeyQueries() throws Exception {
        logExplainPlan(
            "loadTrades(displayName, since)",
            "SELECT id, item_id, timestamp, qty, price, is_buy FROM trades " +
                "WHERE account_id = ? AND timestamp > ? AND qty > 0 ORDER BY timestamp, id",
            new StatementBinder() {
                @Override
                public void bind(PreparedStatement statement) throws SQLException {
                    statement.setInt(1, accountId);
                    statement.setLong(2, querySince.toEpochMilli());
                }
            }
        );

        logExplainPlan(
            "loadTradesByItem(displayName, itemId, since)",
            "SELECT id, item_id, timestamp, qty, price, is_buy FROM trades " +
                "WHERE account_id = ? AND item_id = ? AND timestamp > ? ORDER BY timestamp, id",
            new StatementBinder() {
                @Override
                public void bind(PreparedStatement statement) throws SQLException {
                    statement.setInt(1, accountId);
                    statement.setInt(2, TARGET_ITEM_ID);
                    statement.setLong(3, querySince.toEpochMilli());
                }
            }
        );

        logExplainPlan(
            "queryItemsList(displayName, since, PROFIT, limit, offset)",
            "SELECT t.item_id, SUM(t.qty) as total_qty, " +
                "SUM(CASE WHEN t.is_buy = 1 THEN t.qty ELSE 0 END) as buy_qty, " +
                "SUM(CASE WHEN t.is_buy = 0 THEN t.qty ELSE 0 END) as sell_qty, " +
                "SUM(CASE WHEN t.is_buy = 0 THEN t.qty * t.price ELSE -t.qty * t.price END) as total_profit, " +
                "CASE WHEN SUM(CASE WHEN t.is_buy = 1 THEN t.qty * t.price ELSE 0 END) > 0 " +
                "THEN (SUM(CASE WHEN t.is_buy = 0 THEN t.qty * t.price ELSE -t.qty * t.price END) * 100.0 / " +
                "SUM(CASE WHEN t.is_buy = 1 THEN t.qty * t.price ELSE 0 END)) ELSE 0 END as roi, " +
                "MAX(t.timestamp) as latest_timestamp FROM trades t " +
                "LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
                "WHERE t.account_id = ? AND t.timestamp > ? AND ct.id IS NULL " +
                "GROUP BY t.item_id ORDER BY total_profit DESC LIMIT ? OFFSET ?",
            new StatementBinder() {
                @Override
                public void bind(PreparedStatement statement) throws SQLException {
                    statement.setInt(1, accountId);
                    statement.setLong(2, querySince.toEpochMilli());
                    statement.setInt(3, 25);
                    statement.setInt(4, 0);
                }
            }
        );

        logExplainPlan(
            "queryAggregateStats(displayName, since)",
            "SELECT COALESCE(SUM(CASE WHEN t.is_buy = 1 THEN (t.qty - COALESCE(ct.qty, 0)) * t.price ELSE 0 END), 0) as total_expense, " +
                "COALESCE(SUM(CASE WHEN t.is_buy = 0 THEN (t.qty - COALESCE(ct.qty, 0)) * t.price ELSE 0 END), 0) as total_revenue, " +
                "COALESCE(SUM(CASE WHEN t.is_buy = 0 THEN (t.qty - COALESCE(ct.qty, 0)) * t.price ELSE -(t.qty - COALESCE(ct.qty, 0)) * t.price END), 0) as total_profit, " +
                "COUNT(DISTINCT t.id) as flip_count FROM trades t " +
                "LEFT JOIN consumed_trade ct ON t.id = ct.trade_id " +
                "WHERE t.account_id = ? AND t.timestamp > ? AND (ct.id IS NULL OR ct.qty < t.qty)",
            new StatementBinder() {
                @Override
                public void bind(PreparedStatement statement) throws SQLException {
                    statement.setInt(1, accountId);
                    statement.setLong(2, querySince.toEpochMilli());
                }
            }
        );

        logExplainPlan(
            "queryRecipeFlipGroups(displayName, since) group query",
            "SELECT rf.recipe_key, COUNT(DISTINCT rf.id) as total_count, SUM(rf.coin_cost) as total_cost, SUM(e.profit) as total_profit " +
                "FROM recipe_flips rf JOIN events e ON rf.event_id = e.id " +
                "WHERE e.account_id = ? AND e.timestamp > ? AND e.type = 'recipe' " +
                "GROUP BY rf.recipe_key ORDER BY total_profit DESC",
            new StatementBinder() {
                @Override
                public void bind(PreparedStatement statement) throws SQLException {
                    statement.setInt(1, accountId);
                    statement.setLong(2, querySince.toEpochMilli());
                }
            }
        );

        logExplainPlan(
            "queryRecipeFlipGroups(displayName, since) event query",
            "SELECT e.id, e.timestamp, e.profit, e.cost, rf.recipe_key FROM events e " +
                "JOIN recipe_flips rf ON e.id = rf.event_id " +
                "WHERE e.account_id = ? AND e.timestamp > ? AND e.type = 'recipe' ORDER BY e.timestamp DESC",
            new StatementBinder() {
                @Override
                public void bind(PreparedStatement statement) throws SQLException {
                    statement.setInt(1, accountId);
                    statement.setLong(2, querySince.toEpochMilli());
                }
            }
        );

        logExplainPlan(
            "loadUniqueItems(displayName)",
            "SELECT item_id, MAX(timestamp) as latest_timestamp, SUM(qty) as total_qty, " +
                "SUM(CASE WHEN is_buy = 1 THEN qty ELSE 0 END) as total_buy_qty, " +
                "SUM(CASE WHEN is_buy = 0 THEN qty ELSE 0 END) as total_sell_qty FROM trades " +
                "WHERE account_id = ? AND qty > 0 GROUP BY item_id ORDER BY latest_timestamp DESC",
            new StatementBinder() {
                @Override
                public void bind(PreparedStatement statement) throws SQLException {
                    statement.setInt(1, accountId);
                }
            }
        );
    }

    private void populateFixture() throws Exception {
        storage.upsertAccount(DISPLAY_NAME, PLAYER_ID);

        Integer resolvedAccountId = storage.getAccountId(DISPLAY_NAME);
        assertNotNull("Account should be created before populating fixture", resolvedAccountId);
        accountId = resolvedAccountId;
        baseTimestamp = Instant.now().minus(Duration.ofDays(30)).toEpochMilli();
        querySince = Instant.ofEpochMilli(baseTimestamp + QUERY_WINDOW_MILLIS);

        insertTradesFixture();
        insertRecipeFixture();
    }

    private void insertTradesFixture() throws SQLException {
        Connection connection = storage.getConnection();
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        String sql = "INSERT INTO trades (account_id, item_id, timestamp, qty, price, is_buy) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < TOTAL_TRADES; i++) {
                int itemId = FIRST_ITEM_ID + (i % ITEM_COUNT);
                long timestamp = baseTimestamp + ((long) i * FIXTURE_WINDOW_MILLIS / TOTAL_TRADES);
                int qty = 1 + (i % 20);
                int cycle = i / ITEM_COUNT;
                boolean isBuy = ((cycle + (itemId - FIRST_ITEM_ID)) % 2) == 0;
                int basePrice = 10_000 + ((itemId - FIRST_ITEM_ID) * 35) + (cycle % 60);
                int price = isBuy ? basePrice : basePrice + 175 + (cycle % 15);

                statement.setInt(1, accountId);
                statement.setInt(2, itemId);
                statement.setLong(3, timestamp);
                statement.setInt(4, qty);
                statement.setInt(5, price);
                statement.setInt(6, isBuy ? 1 : 0);
                statement.addBatch();

                if ((i + 1) % 1_000 == 0) {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void insertRecipeFixture() throws SQLException {
        List<TradeRef> consumableTrades = loadConsumableTrades(RECIPE_EVENT_COUNT * RECIPE_INPUTS_PER_EVENT);
        assertTrue("Recipe fixture requires enough consumable trades", consumableTrades.size() >= RECIPE_EVENT_COUNT * RECIPE_INPUTS_PER_EVENT);

        Connection connection = storage.getConnection();
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        String eventSql = "INSERT INTO events (account_id, timestamp, type, cost, profit, note) VALUES (?, ?, 'recipe', ?, ?, ?)";
        String recipeFlipSql = "INSERT INTO recipe_flips (event_id, recipe_key, coin_cost) VALUES (?, ?, ?)";
        String inputSql = "INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
        String outputSql = "INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed) VALUES (?, ?, ?, ?)";
        String consumedSql = "INSERT INTO consumed_trade (trade_id, qty, event_id) VALUES (?, ?, ?)";

        try (
            PreparedStatement eventStatement = connection.prepareStatement(eventSql, Statement.RETURN_GENERATED_KEYS);
            PreparedStatement recipeFlipStatement = connection.prepareStatement(recipeFlipSql, Statement.RETURN_GENERATED_KEYS);
            PreparedStatement inputStatement = connection.prepareStatement(inputSql);
            PreparedStatement outputStatement = connection.prepareStatement(outputSql);
            PreparedStatement consumedStatement = connection.prepareStatement(consumedSql)
        ) {
            for (int i = 0; i < RECIPE_EVENT_COUNT; i++) {
                long timestamp = baseTimestamp + ((long) (i + 1) * FIXTURE_WINDOW_MILLIS / (RECIPE_EVENT_COUNT + 1));
                int cost = 25_000 + (i % 12) * 400;
                int profit = 1_500 + (i % 8) * 125;

                eventStatement.setInt(1, accountId);
                eventStatement.setLong(2, timestamp);
                eventStatement.setInt(3, cost);
                eventStatement.setInt(4, profit);
                eventStatement.setString(5, "perf recipe " + i);
                eventStatement.executeUpdate();

                long eventId = readGeneratedId(eventStatement, "recipe event");

                recipeFlipStatement.setLong(1, eventId);
                recipeFlipStatement.setString(2, "recipe-group-" + (i % RECIPE_GROUP_COUNT));
                recipeFlipStatement.setInt(3, cost - 500);
                recipeFlipStatement.executeUpdate();

                long recipeFlipId = readGeneratedId(recipeFlipStatement, "recipe flip");

                TradeRef firstInput = consumableTrades.get(i * RECIPE_INPUTS_PER_EVENT);
                TradeRef secondInput = consumableTrades.get(i * RECIPE_INPUTS_PER_EVENT + 1);

                bindRecipeComponent(inputStatement, recipeFlipId, firstInput.itemId, 1);
                bindRecipeComponent(inputStatement, recipeFlipId, secondInput.itemId, 1);
                bindRecipeComponent(outputStatement, recipeFlipId, FIRST_ITEM_ID + 80 + (i % 10), 1);

                bindConsumedTrade(consumedStatement, firstInput.tradeId, (int) eventId);
                bindConsumedTrade(consumedStatement, secondInput.tradeId, (int) eventId);
            }

            inputStatement.executeBatch();
            outputStatement.executeBatch();
            consumedStatement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private List<TradeRef> loadConsumableTrades(int limit) throws SQLException {
        String sql = "SELECT id, item_id FROM trades WHERE account_id = ? AND is_buy = 1 AND timestamp > ? ORDER BY timestamp LIMIT ?";
        List<TradeRef> trades = new ArrayList<TradeRef>(limit);
        try (PreparedStatement statement = storage.getConnection().prepareStatement(sql)) {
            statement.setInt(1, accountId);
            statement.setLong(2, querySince.toEpochMilli());
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    trades.add(new TradeRef(resultSet.getInt("id"), resultSet.getInt("item_id")));
                }
            }
        }
        return trades;
    }

    private void bindRecipeComponent(PreparedStatement statement, long recipeFlipId, int itemId, int amountConsumed) throws SQLException {
        statement.setLong(1, recipeFlipId);
        statement.setInt(2, itemId);
        statement.setNull(3, Types.VARCHAR);
        statement.setInt(4, amountConsumed);
        statement.addBatch();
    }

    private void bindConsumedTrade(PreparedStatement statement, int tradeId, int eventId) throws SQLException {
        statement.setInt(1, tradeId);
        statement.setInt(2, 1);
        statement.setInt(3, eventId);
        statement.addBatch();
    }

    private long readGeneratedId(PreparedStatement statement, String label) throws SQLException {
        try (ResultSet resultSet = statement.getGeneratedKeys()) {
            assertTrue("Expected generated id for " + label, resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private <T> T assertQueryUnderBudget(String label, QueryCall<T> queryCall) throws Exception {
        long startedAt = System.currentTimeMillis();
        T result = queryCall.run();
        long elapsedMillis = System.currentTimeMillis() - startedAt;

        System.out.println(label + " completed in " + elapsedMillis + "ms (" + describeResult(result) + ")");
        assertTrue(label + " should complete in under " + QUERY_BUDGET_MS + "ms but took " + elapsedMillis + "ms", elapsedMillis < QUERY_BUDGET_MS);
        return result;
    }

    private String describeResult(Object result) {
        if (result instanceof List) {
            return ((List<?>) result).size() + " rows";
        }
        if (result instanceof Map) {
            return ((Map<?, ?>) result).size() + " fields";
        }
        return result == null ? "null" : result.getClass().getSimpleName();
    }

    private void logExplainPlan(String label, String sql, StatementBinder binder) throws SQLException {
        String explainSql = "EXPLAIN QUERY PLAN " + sql;
        System.out.println("EXPLAIN QUERY PLAN: " + label);
        try (PreparedStatement statement = storage.getConnection().prepareStatement(explainSql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    System.out.println("  " + resultSet.getInt("id") + "|" + resultSet.getInt("parent") + "|" + resultSet.getString("detail"));
                }
            }
        }
    }

    private interface QueryCall<T> {
        T run() throws Exception;
    }

    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private static final class TradeRef {
        private final int tradeId;
        private final int itemId;

        private TradeRef(int tradeId, int itemId) {
            this.tradeId = tradeId;
            this.itemId = itemId;
        }
    }
}
