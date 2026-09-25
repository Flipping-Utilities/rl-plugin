package com.flippingutilities.db;

import com.flippingutilities.model.*;
import com.flippingutilities.utilities.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Builds account fixtures, migrates them to SQLite via MigrationService,
 * and verifies that both backends report identical data for:
 *   - trade counts per item
 *   - total expense / revenue
 *   - recipe flip groups
 *   - favorites
 *   - session time
 *   - GE limit state
 */
public class TestMigrationParityTest {

    private static final String ACCOUNT_NAME = "Test";

    private Path tempDir;
    private File dbFile;
    private SqliteStorage storage;
    private AccountData jsonAccountData;

    @Before
    public void setUp() throws Exception {
        // 1. Build test AccountData programmatically (the original Test.json fixture was
        //    never committed to the repo). This constructs a realistic dataset with two
        //    items, a buy+sell pair per item (forming flips), and a favorite.
        jsonAccountData = createTestAccountData();
        assertNotNull("Programmatic AccountData should build", jsonAccountData);

        // 2. Create temp SQLite storage
        tempDir = Files.createTempDirectory("test_parity_");
        dbFile = new File(tempDir.toFile(), "test.db");
        storage = new SqliteStorage(dbFile);
        storage.initializeSchema();

        // 3. Migrate via MigrationService.migrateAccountBatched
        Gson gson = new GsonBuilder().create();
        storage.upsertAccount(ACCOUNT_NAME, null);
        Integer accountId = storage.getAccountId(ACCOUNT_NAME);
        assertNotNull("Account should exist after upsert", accountId);

        // Create a minimal TradePersister (not used for loading, just for constructor)
        TradePersister tradePersister = new TradePersister(gson);
        MigrationService migrationService = new MigrationService(storage, tradePersister);

        Connection conn = storage.getConnection();
        boolean wasAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int[] counts = migrationService.migrateAccountBatched(conn, ACCOUNT_NAME, jsonAccountData);
            conn.commit();
            System.out.println("[Test] Migrated: " + counts[0] + " trades, " +
                counts[1] + " flips, " + counts[2] + " recipe flips");
        } finally {
            conn.setAutoCommit(wasAutoCommit);
        }

        // Migrate favorites for the account
        int favCount = migrationService.migrateFavoritesForAccount(ACCOUNT_NAME, jsonAccountData);
        System.out.println("[Test] Migrated " + favCount + " favorites");
    }

    /**
     * Programmatically constructs an AccountData with known trades. Replaces the missing
     * Test.json fixture. Two items (whip and dragon scimitar), each with a buy+sell pair.
     */
    private AccountData createTestAccountData() {
        AccountData data = new AccountData();
        List<FlippingItem> trades = new ArrayList<>();

        // Item 1: Abyssal whip (4151) — buy 10 @ 50000, sell 10 @ 55000
        FlippingItem whip = new FlippingItem(4151, "Abyssal whip", 4, ACCOUNT_NAME);
        whip.setValidFlippingPanelItem(true);
        whip.setFavorite(true);

        OfferEvent whipBuy = new OfferEvent();
        whipBuy.setUuid(UUID.randomUUID().toString());
        whipBuy.setState(GrandExchangeOfferState.BOUGHT);
        whipBuy.setItemId(4151);
        whipBuy.setBuy(true);
        whipBuy.setCurrentQuantityInTrade(10);
        whipBuy.setPrice(50000);
        whipBuy.setTime(Instant.now().minusSeconds(7200));
        whipBuy.setMadeBy(ACCOUNT_NAME);
        whip.updateHistory(whipBuy);

        OfferEvent whipSell = new OfferEvent();
        whipSell.setUuid(UUID.randomUUID().toString());
        whipSell.setState(GrandExchangeOfferState.SOLD);
        whipSell.setItemId(4151);
        whipSell.setBuy(false);
        whipSell.setCurrentQuantityInTrade(10);
        whipSell.setPrice(55000);
        whipSell.setTime(Instant.now().minusSeconds(3600));
        whipSell.setMadeBy(ACCOUNT_NAME);
        whip.updateHistory(whipSell);
        trades.add(whip);

        // Item 2: Dragon scimitar (4587) — buy 5 @ 60000, sell 5 @ 65000
        FlippingItem dscim = new FlippingItem(4587, "Dragon scimitar", 4, ACCOUNT_NAME);
        dscim.setValidFlippingPanelItem(true);

        OfferEvent dscimBuy = new OfferEvent();
        dscimBuy.setUuid(UUID.randomUUID().toString());
        dscimBuy.setState(GrandExchangeOfferState.BOUGHT);
        dscimBuy.setItemId(4587);
        dscimBuy.setBuy(true);
        dscimBuy.setCurrentQuantityInTrade(5);
        dscimBuy.setPrice(60000);
        dscimBuy.setTime(Instant.now().minusSeconds(5400));
        dscimBuy.setMadeBy(ACCOUNT_NAME);
        dscim.updateHistory(dscimBuy);

        OfferEvent dscimSell = new OfferEvent();
        dscimSell.setUuid(UUID.randomUUID().toString());
        dscimSell.setState(GrandExchangeOfferState.SOLD);
        dscimSell.setItemId(4587);
        dscimSell.setBuy(false);
        dscimSell.setCurrentQuantityInTrade(5);
        dscimSell.setPrice(65000);
        dscimSell.setTime(Instant.now().minusSeconds(1800));
        dscimSell.setMadeBy(ACCOUNT_NAME);
        dscim.updateHistory(dscimSell);
        trades.add(dscim);

        data.setTrades(trades);
        data.setAccumulatedSessionTimeMillis(3600000L); // 1 hour
        return data;
    }

    @After
    public void tearDown() {
        if (storage != null) {
            storage.close();
        }
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {
            }
        }
    }

    // --- Trade count parity ---

    @Test
    public void testTradeCountParity() {
        int jsonTradeCount = countCompleteTrades(jsonAccountData);
        List<Map<String, Object>> sqliteTrades = storage.loadTrades(ACCOUNT_NAME, Instant.EPOCH);
        assertEquals("Total trade count should match", jsonTradeCount, sqliteTrades.size());
    }

    // --- Unique item count parity ---

    @Test
    public void testUniqueItemCountParity() {
        // JSON may have multiple FlippingItem entries with the same itemId.
        // SQLite deduplicates by item_id, so we use a Set to match.
        // Must also skip items whose trades are fully consumed by recipe flips.
        Map<String, Integer> consumption = buildRecipeConsumptionByUuid();
        Set<Integer> jsonItemsWithTrades = new HashSet<>();
        for (FlippingItem item : jsonAccountData.getTrades()) {
            if (countCompleteTradesForItem(item, consumption) > 0) {
                jsonItemsWithTrades.add(item.getItemId());
            }
        }
        List<Map<String, Object>> sqliteItems = storage.loadUniqueItems(ACCOUNT_NAME);
        assertEquals("Unique item count should match", jsonItemsWithTrades.size(), sqliteItems.size());
    }

    // --- Expense / Revenue parity ---

    @Test
    public void testExpenseAndRevenueParity() {
        long jsonExpense = computeTotalExpense(jsonAccountData);
        long jsonRevenue = computeTotalRevenue(jsonAccountData);

        List<Map<String, Object>> sqliteTrades = storage.loadTrades(ACCOUNT_NAME, Instant.EPOCH);
        long sqliteExpense = 0;
        long sqliteRevenue = 0;
        for (Map<String, Object> trade : sqliteTrades) {
            int qty = (Integer) trade.get("qty");
            int price = (Integer) trade.get("price");
            boolean isBuy = (Integer) trade.get("isBuy") == 1;
            if (isBuy) {
                sqliteExpense += (long) qty * price;
            } else {
                sqliteRevenue += (long) qty * price;
            }
        }

        assertEquals("Total expense should match", jsonExpense, sqliteExpense);
        assertEquals("Total revenue should match", jsonRevenue, sqliteRevenue);
    }

    // --- Per-item trade count parity ---

    @Test
    public void testPerItemTradeCountsParity() {
        // Build expected counts from JSON (adjusted for recipe consumption)
        Map<String, Integer> consumption = buildRecipeConsumptionByUuid();
        Map<Integer, Integer> jsonCounts = new HashMap<>();
        for (FlippingItem item : jsonAccountData.getTrades()) {
            int count = countCompleteTradesForItem(item, consumption);
            if (count > 0) {
                jsonCounts.put(item.getItemId(), count);
            }
        }

        // Build actual counts from SQLite
        Map<Integer, Integer> sqliteCounts = new HashMap<>();
        List<Map<String, Object>> sqliteTrades = storage.loadTrades(ACCOUNT_NAME, Instant.EPOCH);
        for (Map<String, Object> trade : sqliteTrades) {
            int itemId = (Integer) trade.get("itemId");
            sqliteCounts.merge(itemId, 1, Integer::sum);
        }

        assertEquals("Per-item trade count keys should match", jsonCounts.keySet(), sqliteCounts.keySet());
        for (int itemId : jsonCounts.keySet()) {
            assertEquals("Trade count for item " + itemId + " should match",
                jsonCounts.get(itemId), sqliteCounts.get(itemId));
        }
    }

    // --- Recipe flip group count parity ---

    @Test
    public void testRecipeFlipGroupCountParity() {
        int jsonRecipeGroups = jsonAccountData.getRecipeFlipGroups().size();
        List<Map<String, Object>> sqliteRecipeGroups = storage.queryRecipeFlipGroups(ACCOUNT_NAME, Instant.EPOCH);
        assertEquals("Recipe flip group count should match", jsonRecipeGroups, sqliteRecipeGroups.size());
    }

    // --- Recipe flip count per group parity ---

    @Test
    public void testRecipeFlipCountPerGroupParity() {
        Map<String, Integer> jsonCounts = new HashMap<>();
        for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
            jsonCounts.put(group.getRecipeKey(), group.getRecipeFlips().size());
        }

        List<Map<String, Object>> sqliteGroups = storage.queryRecipeFlipGroups(ACCOUNT_NAME, Instant.EPOCH);
        for (Map<String, Object> group : sqliteGroups) {
            String key = (String) group.get("recipeKey");
            int sqliteCount = (Integer) group.get("totalCount");
            assertTrue("Recipe key " + key + " should exist in JSON", jsonCounts.containsKey(key));
            assertEquals("Recipe flip count for " + key + " should match",
                (int) jsonCounts.get(key), sqliteCount);
        }
    }

    // --- Favorites parity ---

    @Test
    public void testFavoritesParity() {
        // Match the migration filter: isFavorite OR favoriteCode != "1"
        Map<Integer, Map<String, Object>> jsonFavorites = new HashMap<>();
        for (FlippingItem item : jsonAccountData.getTrades()) {
            if (item.isFavorite() || !"1".equals(item.getFavoriteCode())) {
                Map<String, Object> favData = new HashMap<>();
                favData.put("isFavorite", item.isFavorite());
                favData.put("favoriteCode", item.getFavoriteCode());
                jsonFavorites.put(item.getItemId(), favData);
            }
        }

        Map<Integer, Map<String, Object>> sqliteFavorites = storage.loadAllFavorites(ACCOUNT_NAME);
        assertEquals("Favorite count should match", jsonFavorites.size(), sqliteFavorites.size());
        for (int itemId : jsonFavorites.keySet()) {
            assertTrue("Item " + itemId + " should be in SQLite favorites", sqliteFavorites.containsKey(itemId));
        }
    }

    // --- Account existence ---

    @Test
    public void testAccountExistsInSqlite() {
        List<String> accounts = storage.listAccounts();
        assertTrue("SQLite should contain " + ACCOUNT_NAME, accounts.contains(ACCOUNT_NAME));
    }

    // --- Session time parity ---

    @Test
    public void testSessionTimeParity() {
        AccountData sqliteData = storage.loadAccount(ACCOUNT_NAME);
        assertNotNull("SQLite AccountData should load", sqliteData);

        assertEquals("Accumulated session time should match",
            jsonAccountData.getAccumulatedSessionTimeMillis(),
            sqliteData.getAccumulatedSessionTimeMillis());
    }

    // --- GE limit state parity ---

    @Test
    public void testGeLimitStateParity() {
        for (FlippingItem item : jsonAccountData.getTrades()) {
            Instant resetTime = item.getGeLimitResetTime();
            int itemsBought = item.getItemsBoughtThisLimitWindow();

            if (resetTime != null && resetTime != Instant.EPOCH && itemsBought > 0) {
                Map<String, Object> sqliteState = storage.loadGeLimitState(ACCOUNT_NAME, item.getItemId());
                if (sqliteState != null) {
                    int sqliteItemsBought = (Integer) sqliteState.get("itemsBought");
                    assertEquals("Items bought for item " + item.getItemId() + " should match",
                        itemsBought, sqliteItemsBought);
                }
            }
        }
    }

    // --- Flip count parity ---

    @Test
    public void testFlipCountParity() {
        int jsonFlipCount = computeTotalFlipCount(jsonAccountData);
        int sqliteFlipCount = countEventsOfType("flip");
        System.out.println("[Test] Flip count: JSON=" + jsonFlipCount + ", SQLite=" + sqliteFlipCount);
        assertEquals("Total flip count should match", jsonFlipCount, sqliteFlipCount);
    }

    // --- Flip profit parity ---

    @Test
    public void testFlipProfitParity() {
        // Both sides now use proportional matching (FlippingItem.getProfit) via scaling
        long jsonFlipProfit = computeTotalFlipProfit(jsonAccountData);
        long sqliteFlipProfit = sumEventProfit("flip");
        System.out.println("[Test] Flip profit: JSON=" + jsonFlipProfit + ", SQLite=" + sqliteFlipProfit);
        assertEquals("Total flip profit should match", jsonFlipProfit, sqliteFlipProfit);
    }

    // --- Recipe flip count parity ---

    @Test
    public void testRecipeFlipCountParity() {
        int jsonRecipeFlipCount = 0;
        for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
            jsonRecipeFlipCount += group.getRecipeFlips().size();
        }
        int sqliteRecipeFlipCount = countEventsOfType("recipe");
        System.out.println("[Test] Recipe flip count: JSON=" + jsonRecipeFlipCount + ", SQLite=" + sqliteRecipeFlipCount);
        assertEquals("Recipe flip count should match", jsonRecipeFlipCount, sqliteRecipeFlipCount);
    }

    // --- Recipe flip profit parity ---

    @Test
    public void testRecipeFlipProfitParity() {
        // Hydrate PartialOffers first (same as migration does)
        Map<String, OfferEvent> offersByUuid = new HashMap<>();
        for (FlippingItem item : jsonAccountData.getTrades()) {
            if (item.getHistory() == null) continue;
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer != null) offersByUuid.put(offer.getUuid(), offer);
            }
        }
        for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
            for (RecipeFlip flip : group.getRecipeFlips()) {
                for (PartialOffer po : flip.getPartialOffers()) {
                    po.hydrateOffer(offersByUuid);
                }
            }
        }

        long jsonRecipeProfit = 0;
        long jsonRecipeExpense = 0;
        for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
            for (RecipeFlip flip : group.getRecipeFlips()) {
                jsonRecipeProfit += flip.getProfit();
                jsonRecipeExpense += flip.getExpense();
            }
        }

        long sqliteRecipeProfit = sumEventProfit("recipe");
        long sqliteRecipeCost = sumEventCost("recipe");
        System.out.println("[Test] Recipe profit: JSON=" + jsonRecipeProfit + ", SQLite=" + sqliteRecipeProfit);
        System.out.println("[Test] Recipe expense: JSON=" + jsonRecipeExpense + ", SQLite=" + sqliteRecipeCost);
        assertEquals("Recipe flip profit should match", jsonRecipeProfit, sqliteRecipeProfit);
        assertEquals("Recipe flip expense should match", jsonRecipeExpense, sqliteRecipeCost);
    }

    // --- Total event count (flips + recipe flips) ---

    @Test
    public void testTotalEventCountParity() {
        int jsonFlips = computeTotalFlipCount(jsonAccountData);
        int jsonRecipeFlips = 0;
        for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
            jsonRecipeFlips += group.getRecipeFlips().size();
        }
        int sqliteTotal = countEventsOfType(null); // all events
        System.out.println("[Test] Total events: JSON=" + (jsonFlips + jsonRecipeFlips) + ", SQLite=" + sqliteTotal);
        assertEquals("Total event count (flips + recipe) should match", jsonFlips + jsonRecipeFlips, sqliteTotal);
    }

    // --- Aggregate profit parity (same mechanism as each backend) ---

    /**
     * Computes profit using the EXACT same mechanism as JsonFlipRepository.getAggregateStats():
     * 1. Hydrate PartialOffers from recipe flips
     * 2. For each item, get partial offers and adjust offer quantities (reduce by recipe consumption)
     * 3. Compute FlippingItem.getProfit(adjustedOffers)
     * 4. Add RecipeFlip.getProfit()
     *
     * Compares against SQLite: SUM(e.profit) from ALL events.
     */
    @Test
    public void testAggregateProfitParity() {
        // === JSON side (replicates JsonFlipRepository.getAggregateStats) ===

        // 1. Build offer UUID -> OfferEvent map for hydration
        Map<String, OfferEvent> offersByUuid = new HashMap<>();
        for (FlippingItem item : jsonAccountData.getTrades()) {
            if (item.getHistory() == null) continue;
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer != null) {
                    offersByUuid.put(offer.getUuid(), offer);
                }
            }
        }

        // 2. Hydrate PartialOffers in recipe flips (set their 'offer' field)
        for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
            for (RecipeFlip flip : group.getRecipeFlips()) {
                for (PartialOffer po : flip.getPartialOffers()) {
                    po.hydrateOffer(offersByUuid);
                }
            }
        }

        // 3. Compute regular flip profit with partial offer adjustment
        long jsonRegularProfit = 0;
        for (FlippingItem item : jsonAccountData.getTrades()) {
            List<OfferEvent> offers = getCompleteOffers(item);
            if (offers.isEmpty()) continue;

            // Get partial offers for this item from recipe flip groups
            Map<String, PartialOffer> itemPartialOffers = new HashMap<>();
            for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
                if (group.isInGroup(item.getItemId())) {
                    group.getOfferIdToPartialOffer(item.getItemId()).forEach((id, po) -> {
                        itemPartialOffers.merge(id, po, (a, b) -> {
                            PartialOffer merged = a.clone();
                            merged.amountConsumed += b.amountConsumed;
                            return merged;
                        });
                    });
                }
            }

            // Adjust offers for recipe consumption (same logic as HistoryManager.getPartialOfferAdjustedView)
            List<OfferEvent> adjustedOffers = adjustOffersForPartialConsumption(offers, itemPartialOffers);

            jsonRegularProfit += FlippingItem.getProfit(adjustedOffers);
        }

        // 4. Add recipe flip profit
        long jsonRecipeProfit = 0;
        for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
            for (RecipeFlip flip : group.getRecipeFlips()) {
                jsonRecipeProfit += flip.getProfit();
            }
        }

        long jsonTotalProfit = jsonRegularProfit + jsonRecipeProfit;

        // === SQLite side (replicates SqliteFlipRepository.getAggregateStats) ===
        long sqliteTotalProfit = sumEventProfit(null); // sum ALL events (flip + recipe)

        System.out.println("[Test] Aggregate profit: JSON=" + jsonTotalProfit +
            " (regular=" + jsonRegularProfit + ", recipe=" + jsonRecipeProfit + ")" +
            ", SQLite=" + sqliteTotalProfit);
        assertEquals("Aggregate profit (same mechanism as each backend) should match",
            jsonTotalProfit, sqliteTotalProfit);
    }

    // --- Tax parity (computed from sell trades using Constants formula) ---

    @Test
    public void testTaxParityFromTrades() {
        // Compute tax from JSON sell trades using the same formula as SqliteFlipRepository
        long jsonTax = computeTotalTaxFromJson(jsonAccountData);

        // Compute tax from SQLite sell trades
        long sqliteTax = computeTaxFromSqliteTrades();

        System.out.println("[Test] Tax (from trades): JSON=" + jsonTax + ", SQLite=" + sqliteTax);
        assertEquals("Tax computed from trades should match", jsonTax, sqliteTax);
    }

    // --- Per-recipe profit parity ---

    /**
     * Recipe displays calculate profit from the loaded components, so verify those
     * components preserve prices, consumed quantities, and coin costs after migration.
     */
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
        assertEquals("Both recipe flips should migrate", 2, counts[2]);

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

    // --- Helper methods ---

    /**
     * Build a map of offer UUID -> total amount consumed by recipe flips.
     * Mirrors the logic in MigrationService.migrateAccountBatched.
     */
    private Map<String, Integer> buildRecipeConsumptionByUuid() {
        Map<String, Integer> map = new HashMap<>();
        for (RecipeFlipGroup group : jsonAccountData.getRecipeFlipGroups()) {
            for (RecipeFlip flip : group.getRecipeFlips()) {
                if (flip.getInputs() != null) {
                    for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getInputs().entrySet()) {
                        for (PartialOffer po : entry.getValue().values()) {
                            map.merge(po.getOfferUuid(), po.getAmountConsumed(), Integer::sum);
                        }
                    }
                }
                if (flip.getOutputs() != null) {
                    for (Map.Entry<Integer, Map<String, PartialOffer>> entry : flip.getOutputs().entrySet()) {
                        for (PartialOffer po : entry.getValue().values()) {
                            map.merge(po.getOfferUuid(), po.getAmountConsumed(), Integer::sum);
                        }
                    }
                }
            }
        }
        return map;
    }

    private int countCompleteTrades(AccountData data) {
        Map<String, Integer> consumption = buildRecipeConsumptionByUuid();
        int count = 0;
        for (FlippingItem item : data.getTrades()) {
            count += countCompleteTradesForItem(item, consumption);
        }
        return count;
    }

    private int countCompleteTradesForItem(FlippingItem item, Map<String, Integer> consumption) {
        int count = 0;
        for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
            if (offer != null && offer.isComplete() && !offer.isCausedByEmptySlot()) {
                int qty = offer.getCurrentQuantityInTrade();
                Integer consumed = consumption.get(offer.getUuid());
                if (consumed != null) qty -= consumed;
                if (qty <= 0) continue; // fully consumed by recipe flips
                count++;
            }
        }
        return count;
    }

    private long computeTotalExpense(AccountData data) {
        Map<String, Integer> consumption = buildRecipeConsumptionByUuid();
        long total = 0;
        for (FlippingItem item : data.getTrades()) {
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer != null && offer.isComplete() && !offer.isCausedByEmptySlot() && offer.isBuy()) {
                    int qty = offer.getCurrentQuantityInTrade();
                    Integer consumed = consumption.get(offer.getUuid());
                    if (consumed != null) qty -= consumed;
                    if (qty <= 0) continue;
                    total += (long) qty * offer.getPreTaxPrice();
                }
            }
        }
        return total;
    }

    private long computeTotalRevenue(AccountData data) {
        Map<String, Integer> consumption = buildRecipeConsumptionByUuid();
        long total = 0;
        for (FlippingItem item : data.getTrades()) {
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer != null && offer.isComplete() && !offer.isCausedByEmptySlot() && !offer.isBuy()) {
                    int qty = offer.getCurrentQuantityInTrade();
                    Integer consumed = consumption.get(offer.getUuid());
                    if (consumed != null) qty -= consumed;
                    if (qty <= 0) continue;
                    total += (long) qty * offer.getPreTaxPrice();
                }
            }
        }
        return total;
    }

    /**
     * Compute total flip count from JSON by running the same logic as the migration:
     * adjust offers for recipe consumption, clone, set madeBy("migrated"), call HistoryManager.getFlips().
     */
    private int computeTotalFlipCount(AccountData data) {
        Map<String, Integer> consumption = buildRecipeConsumptionByUuid();
        int total = 0;
        for (FlippingItem item : data.getTrades()) {
            List<OfferEvent> adjustedOffers = getAdjustedOffers(item, consumption);
            if (adjustedOffers.isEmpty()) continue;

            List<OfferEvent> clonedOffers = adjustedOffers.stream()
                .map(OfferEvent::clone)
                .collect(Collectors.toList());
            clonedOffers.forEach(o -> o.setMadeBy("migrated"));

            List<Flip> flips = HistoryManager.getFlips(clonedOffers);
            total += (flips != null ? flips.size() : 0);
        }
        return total;
    }

    /**
     * Compute total flip profit from JSON using FlippingItem.getProfit() (proportional matching)
     * with recipe-consumption-adjusted offers, same as migration.
     */
    private long computeTotalFlipProfit(AccountData data) {
        Map<String, Integer> consumption = buildRecipeConsumptionByUuid();
        long total = 0;
        for (FlippingItem item : data.getTrades()) {
            List<OfferEvent> adjustedOffers = getAdjustedOffers(item, consumption);
            if (adjustedOffers.isEmpty()) continue;

            List<OfferEvent> clonedOffers = adjustedOffers.stream()
                .map(OfferEvent::clone)
                .collect(Collectors.toList());
            clonedOffers.forEach(o -> o.setMadeBy("migrated"));

            total += FlippingItem.getProfit(clonedOffers);
        }
        return total;
    }

    /**
     * Get complete, non-empty-slot offers adjusted for recipe consumption.
     * Fully consumed offers are excluded; partially consumed have reduced qty.
     */
    private List<OfferEvent> getAdjustedOffers(FlippingItem item, Map<String, Integer> consumption) {
        if (item.getHistory() == null) return Collections.emptyList();
        List<OfferEvent> offers = item.getHistory().getCompressedOfferEvents();
        if (offers == null) return Collections.emptyList();

        List<OfferEvent> result = new ArrayList<>();
        for (OfferEvent offer : offers) {
            if (offer == null || !offer.isComplete() || offer.isCausedByEmptySlot()) continue;
            int qty = offer.getCurrentQuantityInTrade();
            Integer consumed = consumption.get(offer.getUuid());
            if (consumed != null) qty -= consumed;
            if (qty <= 0) continue;
            if (consumed != null && consumed > 0) {
                OfferEvent adjusted = offer.clone();
                adjusted.setCurrentQuantityInTrade(qty);
                result.add(adjusted);
            } else {
                result.add(offer);
            }
        }
        return result;
    }

    /**
     * Count events in SQLite by type. If type is null, count all events.
     */
    private int countEventsOfType(String type) {
        String sql = type != null
            ? "SELECT COUNT(*) FROM events WHERE account_id = ? AND type = ?"
            : "SELECT COUNT(*) FROM events WHERE account_id = ?";
        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Integer accountId = storage.getAccountId(ACCOUNT_NAME);
            ps.setInt(1, accountId);
            if (type != null) {
                ps.setString(2, type);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            fail("Failed to count events: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Sum event profit in SQLite by type. If type is null, sum all events.
     */
    private long sumEventProfit(String type) {
        String sql = type != null
            ? "SELECT COALESCE(SUM(profit), 0) FROM events WHERE account_id = ? AND type = ?"
            : "SELECT COALESCE(SUM(profit), 0) FROM events WHERE account_id = ?";
        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Integer accountId = storage.getAccountId(ACCOUNT_NAME);
            ps.setInt(1, accountId);
            if (type != null) {
                ps.setString(2, type);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            fail("Failed to sum event profit: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Sum event cost in SQLite by type.
     */
    private long sumEventCost(String type) {
        String sql = "SELECT COALESCE(SUM(cost), 0) FROM events WHERE account_id = ? AND type = ?";
        try (Connection conn = storage.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Integer accountId = storage.getAccountId(ACCOUNT_NAME);
            ps.setInt(1, accountId);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (Exception e) {
            fail("Failed to sum event cost: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Compute tax from JSON sell trades using the same formula as SqliteFlipRepository,
     * with recipe-consumption-adjusted quantities.
     */
    private long computeTotalTaxFromJson(AccountData data) {
        Map<String, Integer> consumption = buildRecipeConsumptionByUuid();
        long totalTax = 0;
        for (FlippingItem item : data.getTrades()) {
            for (OfferEvent offer : item.getHistory().getCompressedOfferEvents()) {
                if (offer != null && offer.isComplete() && !offer.isCausedByEmptySlot() && !offer.isBuy()) {
                    int qty = offer.getCurrentQuantityInTrade();
                    Integer consumed = consumption.get(offer.getUuid());
                    if (consumed != null) qty -= consumed;
                    if (qty <= 0) continue;
                    totalTax += calculateTaxForTrade(
                        item.getItemId(),
                        offer.getTime().toEpochMilli(),
                        qty,
                        offer.getPreTaxPrice()
                    );
                }
            }
        }
        return totalTax;
    }

    /**
     * Compute tax from SQLite sell trades.
     */
    private long computeTaxFromSqliteTrades() {
        long totalTax = 0;
        List<Map<String, Object>> trades = storage.loadTrades(ACCOUNT_NAME, Instant.EPOCH);
        for (Map<String, Object> trade : trades) {
            boolean isBuy = (Integer) trade.get("isBuy") == 1;
            if (isBuy) continue;
            int itemId = (Integer) trade.get("itemId");
            long timestamp = (Long) trade.get("timestamp");
            int qty = (Integer) trade.get("qty");
            int price = (Integer) trade.get("price");
            totalTax += calculateTaxForTrade(itemId, timestamp, qty, price);
        }
        return totalTax;
    }

    /**
     * Tax calculation matching SqliteFlipRepository.calculateTaxForTrade().
     */
    private long calculateTaxForTrade(int itemId, long timestamp, int qty, int price) {
        long epochSeconds = timestamp / 1000;
        if (epochSeconds < Constants.GE_TAX_START) return 0;
        if (Constants.TAX_EXEMPT_ITEMS.contains(itemId)) return 0;
        if (epochSeconds >= Constants.GE_TAX_INCREASED && Constants.NEW_TAX_EXEMPT_ITEMS.contains(itemId)) return 0;

        long tradeValue = (long) qty * price;
        if (epochSeconds < Constants.GE_TAX_INCREASED) {
            if (tradeValue >= Constants.OLD_MAX_PRICE_FOR_GE_TAX) return Constants.GE_TAX_CAP;
            return (long) Math.floor(tradeValue * Constants.OLD_GE_TAX);
        } else {
            if (tradeValue >= Constants.MAX_PRICE_FOR_GE_TAX) return Constants.GE_TAX_CAP;
            return (long) Math.floor(tradeValue * Constants.GE_TAX);
        }
    }

    /**
     * Get complete, non-empty-slot offers for an item.
     */
    private List<OfferEvent> getCompleteOffers(FlippingItem item) {
        if (item.getHistory() == null) return Collections.emptyList();
        List<OfferEvent> offers = item.getHistory().getCompressedOfferEvents();
        if (offers == null) return Collections.emptyList();
        return offers.stream()
            .filter(o -> o != null && o.isComplete() && !o.isCausedByEmptySlot())
            .collect(Collectors.toList());
    }

    /**
     * Replicates HistoryManager.getPartialOfferAdjustedView: for each offer,
     * if it's partially consumed by a recipe flip, return an adjusted offer
     * with currentQuantityInTrade reduced by amountConsumed. If fully consumed,
     * filter it out.
     */
    private List<OfferEvent> adjustOffersForPartialConsumption(List<OfferEvent> offers, Map<String, PartialOffer> partialOffers) {
        List<OfferEvent> result = new ArrayList<>();
        for (OfferEvent offer : offers) {
            if (partialOffers.containsKey(offer.getUuid())) {
                PartialOffer po = partialOffers.get(offer.getUuid());
                if (po.getOffer() == null) {
                    // Not hydrated — can't adjust, include as-is
                    result.add(offer);
                    continue;
                }
                int remaining = offer.getCurrentQuantityInTrade() - po.getAmountConsumed();
                if (remaining > 0) {
                    OfferEvent adjusted = offer.clone();
                    adjusted.setCurrentQuantityInTrade(remaining);
                    result.add(adjusted);
                }
                // If remaining <= 0, the offer is fully consumed by recipe flips — skip it
            } else {
                result.add(offer);
            }
        }
        return result;
    }
}
