package com.flippingutilities.db;

import com.flippingutilities.model.*;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

/** Tests migration through the account loader used by the application. */
public class TestMigrationParityTest {
    private static final String ACCOUNT_NAME = "Test";
    private static final long BASE_TIME = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private SqliteStorage storage;
    private AccountData jsonAccountData;

    @Before
    public void setUp() throws Exception {
        jsonAccountData = createTestAccountData();
        storage = new SqliteStorage(temporaryFolder.newFile("migration.db"));
        storage.initializeSchema();
        MigrationService migration = new MigrationService(storage, new TradePersister(new Gson()));
        Connection connection = storage.getConnection();
        connection.setAutoCommit(false);
        try {
            migration.migrateAccountBatched(connection, ACCOUNT_NAME, jsonAccountData);
            migration.migrateFavoritesForAccount(ACCOUNT_NAME, jsonAccountData);
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @After
    public void tearDown() {
        storage.close();
    }

    private AccountData createTestAccountData() {
        AccountData data = new AccountData();
        FlippingItem whip = new FlippingItem(4151, "Abyssal whip", 4, ACCOUNT_NAME);
        whip.setFavorite(true);
        whip.setFavoriteCode("w");
        whip.updateHistory(complete(ACCOUNT_NAME, 4151, "whip-buy", BASE_TIME, 10, 50000, true));
        whip.updateHistory(complete(ACCOUNT_NAME, 4151, "whip-sell", BASE_TIME + 60000, 10, 55000, false));
        whip.getHistory().setNextGeLimitRefresh(Instant.ofEpochMilli(BASE_TIME + 14400000));
        whip.getHistory().setItemsBoughtThisLimitWindow(110);
        whip.getHistory().setItemsBoughtThroughCompleteOffers(100);

        FlippingItem scimitar = new FlippingItem(4587, "Dragon scimitar", 4, ACCOUNT_NAME);
        scimitar.updateHistory(complete(ACCOUNT_NAME, 4587, "scim-buy", BASE_TIME + 30000, 5, 60000, true));
        scimitar.updateHistory(complete(ACCOUNT_NAME, 4587, "scim-sell", BASE_TIME + 90000, 5, 65000, false));
        data.setTrades(new ArrayList<>(Arrays.asList(whip, scimitar)));
        data.setAccumulatedSessionTimeMillis(3600000L);
        data.setSessionStartTime(Instant.ofEpochMilli(BASE_TIME));
        return data;
    }

    @Test
    public void migratedOffersPreserveQuantitiesPricesAndTax() {
        AccountData loaded = storage.loadAccount(ACCOUNT_NAME);
        Map<Integer, FlippingItem> actualItems = loaded.getTrades().stream()
            .collect(Collectors.toMap(FlippingItem::getItemId, item -> item));
        assertEquals(jsonAccountData.getTrades().size(), actualItems.size());
        for (FlippingItem expectedItem : jsonAccountData.getTrades()) {
            FlippingItem actualItem = actualItems.get(expectedItem.getItemId());
            assertNotNull(actualItem);
            Map<String, OfferEvent> actualOffers = actualItem.getHistory().getCompressedOfferEvents().stream()
                .collect(Collectors.toMap(OfferEvent::getUuid, offer -> offer));
            assertEquals(expectedItem.getHistory().getCompressedOfferEvents().size(), actualOffers.size());
            for (OfferEvent expected : expectedItem.getHistory().getCompressedOfferEvents()) {
                OfferEvent actual = actualOffers.get(expected.getUuid());
                assertNotNull(actual);
                assertEquals(expected.getCurrentQuantityInTrade(), actual.getCurrentQuantityInTrade());
                assertEquals(expected.getPreTaxPrice(), actual.getPreTaxPrice());
                assertEquals(expected.getTaxPaid(), actual.getTaxPaid());
                assertEquals(expected.getTime(), actual.getTime());
                assertEquals(expected.isBuy(), actual.isBuy());
            }
            List<OfferEvent> expectedHistory = expectedItem.getHistory().getCompressedOfferEvents();
            List<OfferEvent> actualHistory = actualItem.getHistory().getCompressedOfferEvents();
            assertEquals(FlippingItem.getProfit(expectedHistory), FlippingItem.getProfit(actualHistory));
            assertEquals(FlippingItem.getFlips(expectedHistory).size(), FlippingItem.getFlips(actualHistory).size());
        }
    }

    @Test
    public void migratedAccountPreservesSessionFavoritesAndGeLimits() {
        AccountData loaded = storage.loadAccount(ACCOUNT_NAME);
        assertEquals(jsonAccountData.getAccumulatedSessionTimeMillis(), loaded.getAccumulatedSessionTimeMillis());
        assertEquals(jsonAccountData.getSessionStartTime(), loaded.getSessionStartTime());
        FlippingItem whip = loaded.getTrades().stream().filter(item -> item.getItemId() == 4151).findFirst().get();
        assertTrue(whip.isFavorite());
        assertEquals("w", whip.getFavoriteCode());
        assertEquals(110, whip.getHistory().getItemsBoughtThisLimitWindow());
        assertEquals(100, whip.getHistory().getItemsBoughtThroughCompleteOffers());
        assertEquals(Instant.ofEpochMilli(BASE_TIME + 14400000), whip.getHistory().getNextGeLimitRefresh());
    }

    @Test
    public void testPerRecipeProfitParity() throws Exception {
        AccountData recipeData = createTestAccountData();
        OfferEvent input = recipeData.getTrades().get(0).getHistory().getCompressedOfferEvents().stream()
            .filter(OfferEvent::isBuy).findFirst().get();
        OfferEvent output = recipeData.getTrades().get(1).getHistory().getCompressedOfferEvents().stream()
            .filter(offer -> !offer.isBuy()).findFirst().get();
        RecipeFlipGroup group = new RecipeFlipGroup("4151:1|4587:1");
        for (int quantity = 1; quantity <= 2; quantity++) {
            group.getRecipeFlips().add(new RecipeFlip(
                Instant.now().minusSeconds(quantity),
                Collections.singletonMap(output.getItemId(),
                    Collections.singletonMap(output.getUuid(), new PartialOffer(output, quantity))),
                Collections.singletonMap(input.getItemId(),
                    Collections.singletonMap(input.getUuid(), new PartialOffer(input, quantity))),
                quantity * 100L));
        }
        recipeData.setRecipeFlipGroups(Collections.singletonList(group));

        String recipeAccount = "Recipe Test";
        MigrationService migration = new MigrationService(storage, new TradePersister(new Gson()));
        int[] counts = migration.migrateAccountBatched(storage.getConnection(), recipeAccount, recipeData);
        assertEquals("Both recipe flips should migrate", 2, counts[1]);

        AccountData loaded = storage.loadAccount(recipeAccount);
        assertEquals(1, loaded.getRecipeFlipGroups().size());
        RecipeFlipGroup loadedGroup = loaded.getRecipeFlipGroups().get(0);
        assertEquals(group.getRecipeKey(), loadedGroup.getRecipeKey());
        assertEquals(2, loadedGroup.getRecipeFlips().size());
        long expectedProfit = group.getRecipeFlips().stream().mapToLong(RecipeFlip::getProfit).sum();
        assertEquals("The fixture should include consumed quantities, sell tax, and coin costs", 40_800L, expectedProfit);
        assertEquals("Loaded recipe components should produce the same displayed profit",
            expectedProfit, loadedGroup.getRecipeFlips().stream().mapToLong(RecipeFlip::getProfit).sum());
    }

    @Test
    public void testRecipeSnapshotCanBeDeletedAtOriginalCreationTime() {
        OfferEvent input = jsonAccountData.getTrades().get(0).getHistory().getCompressedOfferEvents().stream()
            .filter(OfferEvent::isBuy).findFirst().get();
        OfferEvent output = jsonAccountData.getTrades().get(1).getHistory().getCompressedOfferEvents().stream()
            .filter(offer -> !offer.isBuy()).findFirst().get();
        RecipeFlip original = new RecipeFlip(
            Instant.parse("2026-09-25T12:34:56.789Z"),
            Collections.singletonMap(output.getItemId(),
                Collections.singletonMap(output.getUuid(), new PartialOffer(output, 1))),
            Collections.singletonMap(input.getItemId(),
                Collections.singletonMap(input.getUuid(), new PartialOffer(input, 1))),
            100L);
        String recipeKey = "4151:1|4587:1";

        // Queued writes persist a snapshot, while a later UI delete uses the original flip.
        storage.insertRecipeFlip(ACCOUNT_NAME, recipeKey, original.clone());
        assertEquals(1, storage.loadAccount(ACCOUNT_NAME).getRecipeFlipGroups().size());
        storage.deleteRecipeFlip(ACCOUNT_NAME, recipeKey, original.getTimeOfCreation());
        assertTrue("Deleting by the original timestamp should remove the persisted snapshot",
            storage.loadAccount(ACCOUNT_NAME).getRecipeFlipGroups().isEmpty());
    }

    @Test
    public void testFavoriteBeforeFirstTradeCreatesAnAccount() {
        storage.upsertFavorite("New Account", 4151, true, "w");

        AccountData loaded = storage.loadAccount("New Account");
        assertNotNull("A favorite should persist before the account has completed any trades", loaded);
        assertEquals(1, loaded.getTrades().size());
        FlippingItem item = loaded.getTrades().get(0);
        assertEquals(4151, item.getItemId());
        assertTrue(item.isFavorite());
        assertEquals("w", item.getFavoriteCode());
    }

}
