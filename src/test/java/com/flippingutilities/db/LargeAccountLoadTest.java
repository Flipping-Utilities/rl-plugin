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
import java.time.Duration;
import java.time.Instant;

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
    private static final int RECIPE_FLIP_COUNT = 200;
    private static final int RECIPE_GROUP_COUNT = 10;
    private static final int RECIPE_INPUTS_PER_FLIP = 2;
    private static final long FIXTURE_WINDOW_MILLIS = Duration.ofDays(30).toMillis();

    private File testDbFile;
    private SqliteStorage storage;
    private int accountId;
    private long baseTimestamp;

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
        assertEquals(RECIPE_FLIP_COUNT, account.getRecipeFlipGroups().stream()
            .mapToInt(group -> group.getRecipeFlips().size()).sum());
    }

    private void populateFixture() throws Exception {
        storage.upsertAccount(DISPLAY_NAME, PLAYER_ID);

        Integer resolvedAccountId = storage.getAccountId(DISPLAY_NAME);
        assertNotNull("Account should be created before populating fixture", resolvedAccountId);
        accountId = resolvedAccountId;
        baseTimestamp = Instant.now().minus(Duration.ofDays(30)).toEpochMilli();

        insertTradesFixture();
        insertRecipeFixture();
    }

    private void insertTradesFixture() throws SQLException {
        Connection connection = storage.getConnection();
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        String sql = "INSERT INTO trades (account_id, item_id, timestamp, qty, price, is_buy, uuid, offer_json) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
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
                statement.setString(8, SqliteStorage.serializeOffer(offer));
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
        Connection connection = storage.getConnection();
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        String recipeFlipSql = "INSERT INTO recipe_flips (account_id, timestamp, recipe_key, coin_cost, natural_key) VALUES (?, ?, ?, ?, ?)";
        String inputSql = "INSERT INTO recipe_flip_inputs (recipe_flip_id, item_id, offer_uuid, amount_consumed, offer_json) VALUES (?, ?, ?, ?, ?)";
        String outputSql = "INSERT INTO recipe_flip_outputs (recipe_flip_id, item_id, offer_uuid, amount_consumed, offer_json) VALUES (?, ?, ?, ?, ?)";
        try (
            PreparedStatement recipeFlipStatement = connection.prepareStatement(recipeFlipSql, Statement.RETURN_GENERATED_KEYS);
            PreparedStatement inputStatement = connection.prepareStatement(inputSql);
            PreparedStatement outputStatement = connection.prepareStatement(outputSql)
        ) {
            for (int i = 0; i < RECIPE_FLIP_COUNT; i++) {
                long timestamp = baseTimestamp + ((long) (i + 1) * FIXTURE_WINDOW_MILLIS / (RECIPE_FLIP_COUNT + 1));
                recipeFlipStatement.setInt(1, accountId);
                recipeFlipStatement.setLong(2, timestamp);
                recipeFlipStatement.setString(3, "recipe-group-" + (i % RECIPE_GROUP_COUNT));
                recipeFlipStatement.setInt(4, 24_500 + (i % 12) * 400);
                recipeFlipStatement.setString(5, "perf-recipe-" + i);
                recipeFlipStatement.executeUpdate();

                long recipeFlipId = readGeneratedId(recipeFlipStatement, "recipe flip");
                for (int input = 0; input < RECIPE_INPUTS_PER_FLIP; input++) {
                    int itemId = FIRST_ITEM_ID + ((i * RECIPE_INPUTS_PER_FLIP + input) % ITEM_COUNT);
                    bindRecipeComponent(inputStatement, recipeFlipId, itemId, true);
                }
                bindRecipeComponent(outputStatement, recipeFlipId, FIRST_ITEM_ID + 80 + (i % 10), false);
            }

            inputStatement.executeBatch();
            outputStatement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void bindRecipeComponent(PreparedStatement statement, long recipeFlipId, int itemId, boolean isBuy) throws SQLException {
        OfferEvent offer = complete(DISPLAY_NAME, itemId,
            "recipe-" + recipeFlipId + "-" + itemId + "-" + isBuy, baseTimestamp, 1, 500, isBuy);
        statement.setLong(1, recipeFlipId);
        statement.setInt(2, itemId);
        statement.setString(3, offer.getUuid());
        statement.setInt(4, 1);
        statement.setString(5, SqliteStorage.serializeOffer(offer));
        statement.addBatch();
    }

    private long readGeneratedId(PreparedStatement statement, String label) throws SQLException {
        try (ResultSet resultSet = statement.getGeneratedKeys()) {
            assertTrue("Expected generated id for " + label, resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
