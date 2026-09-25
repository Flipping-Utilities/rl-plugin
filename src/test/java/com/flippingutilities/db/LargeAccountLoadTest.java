package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.OfferEvent;
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

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LargeAccountLoadTest {
    private static final String DISPLAY_NAME = "PerfPlayer";
    private static final String PLAYER_ID = "perf-player-id";
    private static final int TOTAL_TRADES = 50_000;
    private static final int ITEM_COUNT = 100;
    private static final int FIRST_ITEM_ID = 10_000;
    private static final int RECIPE_EVENT_COUNT = 200;
    private static final int RECIPE_GROUP_COUNT = 10;
    private static final int RECIPE_INPUTS_PER_EVENT = 2;
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
    public void largeAccountRestoresAllTradesAndRecipes() {
        AccountData account = storage.loadAccount(DISPLAY_NAME);
        assertNotNull(account);
        assertEquals(ITEM_COUNT, account.getTrades().size());
        assertEquals(TOTAL_TRADES, account.getTrades().stream()
            .mapToInt(item -> item.getHistory().getCompressedOfferEvents().size()).sum());
        assertEquals(RECIPE_GROUP_COUNT, account.getRecipeFlipGroups().size());
        assertEquals(RECIPE_EVENT_COUNT, account.getRecipeFlipGroups().stream()
            .mapToInt(group -> group.getRecipeFlips().size()).sum());
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

        String sql = "INSERT INTO trades (account_id, item_id, timestamp, qty, price, is_buy, uuid, tax, offer_json) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < TOTAL_TRADES; i++) {
                int itemId = FIRST_ITEM_ID + (i % ITEM_COUNT);
                long timestamp = baseTimestamp + ((long) i * FIXTURE_WINDOW_MILLIS / TOTAL_TRADES);
                int qty = 1 + (i % 20);
                int cycle = i / ITEM_COUNT;
                boolean isBuy = ((cycle + (itemId - FIRST_ITEM_ID)) % 2) == 0;
                int basePrice = 10_000 + ((itemId - FIRST_ITEM_ID) * 35) + (cycle % 60);
                int price = isBuy ? basePrice : basePrice + 175 + (cycle % 15);

                OfferEvent offer = complete(DISPLAY_NAME, itemId, "perf-" + i, timestamp, qty, price, isBuy);

                statement.setInt(1, accountId);
                statement.setInt(2, itemId);
                statement.setLong(3, timestamp);
                statement.setInt(4, qty);
                statement.setInt(5, price);
                statement.setInt(6, isBuy ? 1 : 0);
                statement.setString(7, offer.getUuid());
                statement.setLong(8, offer.getTaxPaid());
                statement.setString(9, SqliteStorage.serializeOffer(offer));
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
        String inputSql = "INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed, offer_json) VALUES (?, ?, ?, ?, ?)";
        String outputSql = "INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed, offer_json) VALUES (?, ?, ?, ?, ?)";
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
        statement.setString(5, SqliteStorage.serializeOffer(complete(DISPLAY_NAME, itemId,
            "recipe-" + recipeFlipId + "-" + itemId, baseTimestamp, amountConsumed, 500, true)));
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

    private static final class TradeRef {
        private final int tradeId;
        private final int itemId;

        private TradeRef(int tradeId, int itemId) {
            this.tradeId = tradeId;
            this.itemId = itemId;
        }
    }
}
