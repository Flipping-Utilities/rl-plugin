package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.model.PartialOffer;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Regression tests for the interaction between live trade recording and migration, and for
 * the SQLite maintenance/delete paths.
 *
 * 1. Migrating an account whose trades were already recorded live must not duplicate trades,
 *    or change the restored history.
 * 2. Account deletion, interval deletion, uuid deletion, favorite round-trip, and live recipe
 *    flip persistence must all round-trip.
 * 3. A partially-failed migration keeps successful accounts committed, leaves
 *    migration_completed unset, and does not flag the failed account.
 */
public class MigrationLiveTradeInteractionTest {

    private static final String ACCOUNT = "LivePlayer";
    private static final int WHIP = 4151;
    private static final int DSCIM = 4587;
    // Pre-tax-era timestamp (before GE_TAX_START) so expected profit is clean.
    private static final long BASE_TS = 1600000000000L;

    private Path tempDir;
    private SqliteStorage storage;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("live_migration_test_");
        File dbFile = new File(tempDir.toFile(), "test.db");
        storage = new SqliteStorage(dbFile);
        storage.initializeSchema();
        storage.upsertAccount(ACCOUNT, null);
    }

    @After
    public void tearDown() {
        if (storage != null) storage.close();
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    private long count(String sql) throws Exception {
        try (Statement st = storage.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static OfferEvent completeOffer(String uuid, boolean buy, int qty, int price, long ts) {
        OfferEvent offer = new OfferEvent();
        offer.setUuid(uuid);
        offer.setBuy(buy);
        offer.setItemId(WHIP);
        offer.setCurrentQuantityInTrade(qty);
        offer.setPrice(price);
        offer.setTime(Instant.ofEpochMilli(ts));
        offer.setMadeBy(ACCOUNT);
        offer.setState(buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD);
        offer.setTotalQuantityInTrade(qty);
        return offer;
    }

    private void recordTrade(int itemId, String uuid, long time, int quantity, int price, boolean buy) {
        OfferEvent offer = completeOffer(uuid, buy, quantity, price, time);
        offer.setItemId(itemId);
        storage.recordTrade(ACCOUNT, offer);
    }

    private long loadedProfit() {
        return storage.loadAccount(ACCOUNT).getTrades().stream()
            .mapToLong(item -> FlippingItem.getProfit(item.getHistory().getCompressedOfferEvents()))
            .sum();
    }

    private static AccountData accountDataWithOffers(OfferEvent... offers) {
        AccountData data = new AccountData();
        FlippingItem item = new FlippingItem(WHIP, "Abyssal whip", 70, ACCOUNT);
        for (OfferEvent offer : offers) {
            item.getHistory().getCompressedOfferEvents().add(offer);
        }
        List<FlippingItem> trades = new ArrayList<>();
        trades.add(item);
        data.setTrades(trades);
        return data;
    }

    /** Live-recorded offers and the same JSON migration must share their UUID identity. */
    @Test
    public void testMigrationAfterLiveWritesDoesNotDuplicateTrades() throws Exception {
        recordTrade(WHIP, "u-buy", BASE_TS, 10, 50000, true);
        recordTrade(WHIP, "u-sell", BASE_TS + 60000, 10, 55000, false);

        // Same offers as JSON data; run the (per-account) migration over them.
        AccountData data = accountDataWithOffers(
            completeOffer("u-buy", true, 10, 50000, BASE_TS),
            completeOffer("u-sell", false, 10, 55000, BASE_TS + 60000));
        MigrationService service = new MigrationService(storage, new TradePersister(new GsonBuilder().create()));
        Connection conn = storage.getConnection();
        boolean wasAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            service.migrateAccountBatched(conn, ACCOUNT, data);
            conn.commit();
        } finally {
            conn.setAutoCommit(wasAutoCommit);
        }

        assertEquals("Trades must dedupe on (account_id, uuid)", 2L,
            count("SELECT COUNT(*) FROM trades"));
        assertEquals("Profit must stay 10 * 5000", 50000L,
            loadedProfit());
    }

    @Test
    public void testDeleteAccountDataRemovesEverythingAndAllowsReCreation() throws Exception {
        recordTrade(WHIP, "d-buy", BASE_TS, 10, 50000, true);
        storage.upsertFavorite(ACCOUNT, WHIP, true, "7");
        storage.setSetting("migrated_" + ACCOUNT, Instant.now().toString());

        storage.deleteAccountData(ACCOUNT);

        assertEquals(0L, count("SELECT COUNT(*) FROM accounts"));
        assertEquals(0L, count("SELECT COUNT(*) FROM trades"));
        assertEquals(0L, count("SELECT COUNT(*) FROM item_favorites"));
        assertNull(storage.getSetting("migrated_" + ACCOUNT));
        assertTrue(storage.listAccounts().isEmpty());

        // Account can be re-created and trades recorded again (account-id cache was invalidated).
        recordTrade(WHIP, "d-buy2", BASE_TS, 5, 50000, true);
        assertEquals(1L, count("SELECT COUNT(*) FROM trades"));
    }

    @Test
    public void testDeletingSelectedOffersPreservesEarlierHistory() throws Exception {
        // Flip 1: old (kept). Flip 2: in the window (deleted).
        recordTrade(WHIP, "k-buy", BASE_TS, 10, 50000, true);
        recordTrade(WHIP, "k-sell", BASE_TS + 60000, 10, 55000, false);
        recordTrade(WHIP, "w-buy", BASE_TS + 600000, 5, 51000, true);
        recordTrade(WHIP, "w-sell", BASE_TS + 660000, 5, 56000, false);

        storage.deleteTradesByUuid(ACCOUNT, Arrays.asList("w-buy", "w-sell"));

        assertEquals("Only the old trades remain", 2L, count("SELECT COUNT(*) FROM trades"));
        assertEquals("Old flip profit intact", 50000L,
            loadedProfit());
    }

    @Test
    public void testDeleteTradesByUuidPreservesOtherItemHistory() throws Exception {
        recordTrade(WHIP, "keep-buy", BASE_TS, 10, 50000, true);
        recordTrade(WHIP, "keep-sell", BASE_TS + 60000, 10, 55000, false);
        recordTrade(DSCIM, "drop-buy", BASE_TS + 120000, 4, 60000, true);
        recordTrade(DSCIM, "drop-sell", BASE_TS + 180000, 4, 65000, false);

        storage.deleteTradesByUuid(ACCOUNT, Arrays.asList("drop-buy", "drop-sell"));

        assertEquals(2L, count("SELECT COUNT(*) FROM trades"));
        assertEquals(0L, count("SELECT COUNT(*) FROM trades WHERE item_id = " + DSCIM));
        assertEquals("Whip flip profit intact", 50000L,
            loadedProfit());
    }

    @Test
    public void testFavoritesRoundTripOnLoadAccount() throws Exception {
        recordTrade(WHIP, "f-buy", BASE_TS, 10, 50000, true);
        storage.upsertFavorite(ACCOUNT, WHIP, true, "77");

        AccountData data = storage.loadAccount(ACCOUNT);
        assertNotNull(data);
        FlippingItem whip = data.getTrades().stream()
            .filter(i -> i.getItemId() == WHIP)
            .findFirst()
            .orElse(null);
        assertNotNull(whip);
        assertTrue("Favorite must be restored from item_favorites", whip.isFavorite());
        assertEquals("77", whip.getFavoriteCode());
    }

    /**
     * Favorite-only items (favorited from search, never traded) must survive a SQLite reload:
     * the JSON backend round-trips them through the trades list, so loadAccount recreates them.
     */
    @Test
    public void testFavoriteOnlyItemSurvivesReload() throws Exception {
        storage.upsertFavorite(ACCOUNT, 1337, true, "q");

        AccountData data = storage.loadAccount(ACCOUNT);
        assertNotNull(data);
        FlippingItem favoriteOnly = data.getTrades().stream()
            .filter(i -> i.getItemId() == 1337)
            .findFirst()
            .orElse(null);
        assertNotNull("Favorite-only item must be recreated on load", favoriteOnly);
        assertTrue(favoriteOnly.isFavorite());
        assertEquals("q", favoriteOnly.getFavoriteCode());
    }

    /**
     * Legacy recipe data can reference more consumption than the offer holds; the remaining
     * quantity must clamp at 0 instead of going negative ("-666 flipped (ongoing)" display).
     */
    @Test
    public void testPartialOfferRemainingClampsAtZero() {
        OfferEvent offer = completeOffer("po-1", true, 100, 50, BASE_TS);
        PartialOffer overConsumed = new PartialOffer(offer, 766);
        assertEquals(0, overConsumed.toRemainingOfferEvent().getCurrentQuantityInTrade());

        PartialOffer partial = new PartialOffer(offer, 40);
        assertEquals(60, partial.toRemainingOfferEvent().getCurrentQuantityInTrade());
    }

    @Test
    public void testInsertRecipeFlipPersistsAndIsIdempotent() throws Exception {
        // Underlying trade for the input component.
        recordTrade(WHIP, "r-in", BASE_TS, 10, 50000, true);

        OfferEvent buyOffer = completeOffer("r-in", true, 10, 50000, BASE_TS);
        OfferEvent sellOffer = completeOffer("r-out", false, 6, 90000, BASE_TS + 60000);
        sellOffer.setItemId(DSCIM);

        Map<Integer, Map<String, PartialOffer>> inputs = new HashMap<>();
        inputs.put(WHIP, new HashMap<>(Map.of("r-in", new PartialOffer(buyOffer, 10))));
        Map<Integer, Map<String, PartialOffer>> outputs = new HashMap<>();
        outputs.put(DSCIM, new HashMap<>(Map.of("r-out", new PartialOffer(sellOffer, 6))));

        RecipeFlip flip = new RecipeFlip(Instant.ofEpochMilli(BASE_TS + 120000), outputs, inputs, 0L);

        storage.insertRecipeFlip(ACCOUNT, "whip:crush", flip);
        storage.insertRecipeFlip(ACCOUNT, "whip:crush", flip); // natural key dedupes

        assertEquals("Exactly one recipe event", 1L,
            count("SELECT COUNT(*) FROM events WHERE type = 'recipe'"));
        assertEquals("Exactly one recipe_flip row", 1L,
            count("SELECT COUNT(*) FROM recipe_flips"));
        assertEquals("Input + output component rows", 2L,
            count("SELECT COUNT(*) FROM recipe_flip_inputs") + count("SELECT COUNT(*) FROM recipe_flip_outputs"));
        assertEquals("Input trade consumed by the recipe event", 1L,
            count("SELECT COUNT(*) FROM consumed_trade ct JOIN events e ON e.id = ct.event_id WHERE e.type = 'recipe'"));

        // And it round-trips through loadAccount.
        AccountData data = storage.loadAccount(ACCOUNT);
        assertEquals(1, data.getRecipeFlipGroups().size());
        assertEquals(1, data.getRecipeFlipGroups().get(0).getRecipeFlips().size());
    }

    /**
     * A partially-failed migration must keep successfully-migrated accounts committed (their
     * migrated_ flags durable) while leaving migration_completed unset so the next startup
     * retries the failed account.
     */
    @Test
    public void testPartialMigrationFailureKeepsFlagsConsistent() throws Exception {
        final String goodAccount = "GoodAcc";
        final String badAccount = "BadAcc";

        AccountData goodData = new AccountData();
        FlippingItem item = new FlippingItem(WHIP, "Abyssal whip", 70, goodAccount);
        item.getHistory().getCompressedOfferEvents().add(
            completeOffer("g-buy", true, 10, 50000, BASE_TS));
        goodData.setTrades(new ArrayList<>(Arrays.asList(item)));

        AccountData badData = new AccountData();
        // A null FlippingItem makes migrateAccountBatched throw mid-account.
        badData.setTrades(Arrays.asList((FlippingItem) null));

        final Map<String, AccountData> accounts = new HashMap<>();
        accounts.put(goodAccount, goodData);
        accounts.put(badAccount, badData);

        TradePersister stub = new TradePersister(new Gson()) {
            @Override
            public Map<String, AccountData> loadAllAccounts() {
                return accounts;
            }
        };

        MigrationService service = new MigrationService(storage, stub);
        service.migrate();

        assertNotNull("Good account must be committed with its flag",
            storage.getSetting("migrated_" + goodAccount));
        assertNull("Failed account must not be flagged",
            storage.getSetting("migrated_" + badAccount));
        assertFalse("migration_completed must not be set while an account failed",
            "true".equalsIgnoreCase(storage.getSetting("migration_completed")));
        assertEquals("Good account's trades are durable", 1L,
            count("SELECT COUNT(*) FROM trades WHERE uuid = 'g-buy'"));
    }

    @Test
    public void rolledBackAccountIdCannotRouteLaterWritesToAnotherAccount() throws Exception {
        String failedAccount = "Failed import";
        AccountData source = accountDataWithOffers(completeOffer("reject-import", true, 5, 100, BASE_TS));
        try (Statement statement = storage.getConnection().createStatement()) {
            statement.execute("CREATE TRIGGER reject_import BEFORE INSERT ON trades " +
                "WHEN NEW.uuid = 'reject-import' BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        }
        TradePersister persister = new TradePersister(new Gson()) {
            @Override
            public Map<String, AccountData> loadAllAccounts() {
                return Collections.singletonMap(failedAccount, source);
            }

            @Override
            public AccountWideData loadAccountWideData() {
                return new AccountWideData();
            }
        };
        assertEquals(0, new MigrationService(storage, persister).migrate());
        assertFalse(storage.listAccounts().contains(failedAccount));

        // SQLite can reuse the rolled-back row ID for the next account.
        storage.upsertAccount("Other account", null);
        storage.recordTrade(failedAccount, completeOffer("new-live-trade", true, 3, 200, BASE_TS + 1000));

        assertTrue(storage.listAccounts().contains(failedAccount));
        assertTrue(storage.loadAccount("Other account").getTrades().isEmpty());
        AccountData recovered = storage.loadAccount(failedAccount);
        assertEquals("new-live-trade", recovered.getTrades().get(0).getHistory()
            .getCompressedOfferEvents().get(0).getUuid());
    }

    @Test
    public void testMigrationPreservesFirstUnfilledOfferWithoutTradeHistory() {
        String newAccount = "FirstOfferPlayer";
        AccountData data = new AccountData();
        data.setSessionStartTime(Instant.ofEpochMilli(BASE_TS));
        data.setAccumulatedSessionTimeMillis(123456L);
        OfferEvent firstOffer = completeOffer("first-offer", true, 0, 50000, BASE_TS + 1000);
        firstOffer.setMadeBy(newAccount);
        firstOffer.setState(GrandExchangeOfferState.BUYING);
        firstOffer.setTotalQuantityInTrade(10);
        firstOffer.setSlot(2);
        firstOffer.setTradeStartedAt(firstOffer.getTime());
        data.getLastOffers().put(2, firstOffer);

        AccountData active = accountDataWithOffers(completeOffer("active-buy", true, 1, 50000, BASE_TS));
        assertEquals(2, migrateAccounts(Map.of(newAccount, data, ACCOUNT, active)));

        AccountData reloaded = storage.loadAccount(newAccount);
        assertNotNull("Accounts with only a first unfilled offer must survive migration", reloaded);
        assertEquals(data.getSessionStartTime(), reloaded.getSessionStartTime());
        assertEquals(data.getAccumulatedSessionTimeMillis(), reloaded.getAccumulatedSessionTimeMillis());
        assertTrue(reloaded.getTrades().isEmpty());
        OfferEvent loadedOffer = reloaded.getLastOffers().get(2);
        assertNotNull("The active GE slot must survive migration", loadedOffer);
        assertEquals(firstOffer.getUuid(), loadedOffer.getUuid());
        assertEquals(GrandExchangeOfferState.BUYING, loadedOffer.getState());
        assertEquals(0, loadedOffer.getCurrentQuantityInTrade());
        assertEquals(10, loadedOffer.getTotalQuantityInTrade());
        assertEquals(firstOffer.getTradeStartedAt(), loadedOffer.getTradeStartedAt());
        assertNotNull(storage.getSetting("migrated_" + newAccount));
        assertEquals("true", storage.getSetting("migration_completed"));
        assertEquals(1, storage.loadAccount(ACCOUNT).getTrades().size());
    }

    @Test
    public void testMigrationPreservesRecipeOnlyAccountSessionAndMarker() {
        String recipeAccount = "RecipeOnlyPlayer";
        AccountData data = new AccountData();
        data.setTrades(null);
        data.setSessionStartTime(Instant.ofEpochMilli(BASE_TS));
        data.setAccumulatedSessionTimeMillis(654321L);
        OfferEvent input = completeOffer("recipe-only-input", true, 10, 100, BASE_TS);
        RecipeFlip flip = new RecipeFlip(Instant.ofEpochMilli(BASE_TS + 1000), new HashMap<>(),
            Map.of(WHIP, Map.of(input.getUuid(), new PartialOffer(input, 4))), 25L);
        RecipeFlipGroup group = new RecipeFlipGroup("4151:4587");
        group.addRecipeFlip(flip);
        data.getRecipeFlipGroups().add(group);

        assertEquals(1, migrateAccounts(Map.of(recipeAccount, data)));

        AccountData reloaded = storage.loadAccount(recipeAccount);
        assertNotNull(reloaded);
        assertEquals(data.getSessionStartTime(), reloaded.getSessionStartTime());
        assertEquals(data.getAccumulatedSessionTimeMillis(), reloaded.getAccumulatedSessionTimeMillis());
        assertEquals(1, reloaded.getRecipeFlipGroups().size());
        RecipeFlip loadedFlip = reloaded.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        assertEquals(flip.getTimeOfCreation(), loadedFlip.getTimeOfCreation());
        assertEquals(4, loadedFlip.getInputs().get(WHIP).get(input.getUuid()).getAmountConsumed());
        assertNotNull(storage.getSetting("migrated_" + recipeAccount));
        assertEquals("true", storage.getSetting("migration_completed"));
    }

    private int migrateAccounts(Map<String, AccountData> accounts) {
        TradePersister persister = new TradePersister(new Gson()) {
            @Override public Map<String, AccountData> loadAllAccounts() { return accounts; }
            @Override public AccountWideData loadAccountWideData() { return new AccountWideData(); }
        };
        return new MigrationService(storage, persister).migrate();
    }

    /**
     * Finding 2 regression: trades must migrate with their ORIGINAL quantity; recipe
     * consumption lives only in consumed_trade. The old migration reduced the trade row by
     * the consumed amount AND subtracted consumed_trade at read time (double subtraction),
     * and the recipe loaders built backing offers with qty = amountConsumed, so a 100-qty
     * trade with 40 consumed displayed 0 remaining instead of 60 everywhere.
     */
    @Test
    public void testMigrationKeepsOriginalQtyWithRecipeConsumption() throws Exception {
        OfferEvent buyOffer = completeOffer("m-buy", true, 100, 50000, BASE_TS);
        AccountData data = accountDataWithOffers(buyOffer);

        // A recipe that consumed 40 of the 100 bought.
        Map<Integer, Map<String, PartialOffer>> inputs = new HashMap<>();
        inputs.put(WHIP, new HashMap<>(Map.of("m-buy", new PartialOffer(buyOffer, 40))));
        Map<Integer, Map<String, PartialOffer>> outputs = new HashMap<>();
        outputs.put(DSCIM, new HashMap<>(Map.of("m-out", new PartialOffer(
            completeOffer("m-out", false, 1, 90000, BASE_TS + 60000), 1))));
        RecipeFlipGroup group = new RecipeFlipGroup("whip:crush");
        RecipeFlip flip = new RecipeFlip(Instant.ofEpochMilli(BASE_TS + 120000), outputs, inputs, 0L);
        group.addRecipeFlip(flip);
        data.setRecipeFlipGroups(new ArrayList<>(Arrays.asList(group)));

        MigrationService service = new MigrationService(storage, new TradePersister(new GsonBuilder().create()));
        Connection conn = storage.getConnection();
        boolean wasAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            service.migrateAccountBatched(conn, ACCOUNT, data);
            conn.commit();
        } finally {
            conn.setAutoCommit(wasAutoCommit);
        }

        // The trade row keeps the ORIGINAL quantity...
        assertEquals("Trade must store the original 100 qty", 100L,
            count("SELECT qty FROM trades WHERE uuid = 'm-buy'"));
        // ...and consumption is recorded separately, once.
        assertEquals("Consumption recorded once with the consumed amount", 40L,
            count("SELECT qty FROM consumed_trade ct JOIN events e ON e.id = ct.event_id WHERE e.type = 'recipe'"));

        // The loaded recipe's backing offer carries the original qty, so remaining displays
        // as 100 - 40 = 60 rather than amountConsumed - amountConsumed = 0.
        AccountData loaded = storage.loadAccount(ACCOUNT);
        assertEquals(1, loaded.getRecipeFlipGroups().size());
        RecipeFlip loadedFlip = loaded.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        PartialOffer loadedInput = loadedFlip.getInputs().get(WHIP).get("m-buy");
        assertEquals("Backing offer must carry the original trade qty", 100,
            loadedInput.getOffer().getCurrentQuantityInTrade());
        assertEquals("Consumed amount must round-trip", 40, loadedInput.getAmountConsumed());
        assertEquals("Remaining must be 60", 60, loadedInput.toRemainingOfferEvent().getCurrentQuantityInTrade());
    }

    /**
     * Finding 1 regression: deleting a recipe flip from the UI must delete it from SQLite
     * too (event, components, consumption) and free the consumed units back into regular
     * flip computation.
     */
    @Test
    public void testDeleteRecipeFlipRemovesEventComponentsAndConsumption() throws Exception {
        recordTrade(WHIP, "r-in", BASE_TS, 10, 50000, true);

        OfferEvent buyOffer = completeOffer("r-in", true, 10, 50000, BASE_TS);
        OfferEvent sellOffer = completeOffer("r-out", false, 6, 90000, BASE_TS + 60000);
        sellOffer.setItemId(DSCIM);
        Map<Integer, Map<String, PartialOffer>> inputs = new HashMap<>();
        inputs.put(WHIP, new HashMap<>(Map.of("r-in", new PartialOffer(buyOffer, 10))));
        Map<Integer, Map<String, PartialOffer>> outputs = new HashMap<>();
        outputs.put(DSCIM, new HashMap<>(Map.of("r-out", new PartialOffer(sellOffer, 6))));
        RecipeFlip flip = new RecipeFlip(Instant.ofEpochMilli(BASE_TS + 120000), outputs, inputs, 0L);

        storage.insertRecipeFlip(ACCOUNT, "whip:crush", flip);
        assertEquals(1L, count("SELECT COUNT(*) FROM events WHERE type = 'recipe'"));

        storage.deleteRecipeFlip(ACCOUNT, "whip:crush", flip.getTimeOfCreation());

        assertEquals("Recipe event deleted", 0L, count("SELECT COUNT(*) FROM events WHERE type = 'recipe'"));
        assertEquals("Recipe flip row deleted", 0L, count("SELECT COUNT(*) FROM recipe_flips"));
        assertEquals("Component rows deleted", 0L,
            count("SELECT COUNT(*) FROM recipe_flip_inputs") + count("SELECT COUNT(*) FROM recipe_flip_outputs"));
        assertEquals("Consumption rows deleted", 0L, count("SELECT COUNT(*) FROM consumed_trade"));
        assertEquals("The backing trade itself survives", 1L,
            count("SELECT COUNT(*) FROM trades WHERE uuid = 'r-in'"));
    }

    /**
     * The interval-reset deletion on a recipe group must delete ONLY that group's flips in
     * the interval (scoped by recipe key) and only flips created strictly after the interval
     * start. A previous version deleted every group's recipe flips in the window: the other
     * groups stayed visible in memory, then vanished after a restart.
     */
    @Test
    public void testDeleteRecipeFlipsSinceIsScopedToGroupAndInterval() throws Exception {
        recordTrade(WHIP, "r-in-1", BASE_TS, 10, 50000, true);
        recordTrade(WHIP, "r-in-2", BASE_TS + 700000, 10, 50000, true);
        recordTrade(WHIP, "r-in-3", BASE_TS + 710000, 10, 50000, true);

        Map<String, Long> flipTimes = new HashMap<>();
        String[][] specs = {
            {"r-in-1", "whip:crush", String.valueOf(BASE_TS + 120000)},      // early, target group
            {"r-in-2", "whip:crush", String.valueOf(BASE_TS + 800000)},      // late, target group
            {"r-in-3", "whip:dismantle", String.valueOf(BASE_TS + 810000)},  // late, OTHER group
        };
        for (String[] spec : specs) {
            OfferEvent buyOffer = completeOffer(spec[0], true, 10, 50000, BASE_TS);
            Map<Integer, Map<String, PartialOffer>> inputs = new HashMap<>();
            inputs.put(WHIP, new HashMap<>(Map.of(spec[0], new PartialOffer(buyOffer, 10))));
            RecipeFlip flip = new RecipeFlip(Instant.ofEpochMilli(Long.parseLong(spec[2])),
                new HashMap<>(), inputs, 0L);
            storage.insertRecipeFlip(ACCOUNT, spec[1], flip);
            flipTimes.put(spec[1] + ":" + spec[0], Long.parseLong(spec[2]));
        }
        assertEquals(3L, count("SELECT COUNT(*) FROM events WHERE type = 'recipe'"));

        // Reset the "whip:crush" group to the interval starting between its two flips.
        storage.deleteRecipeFlipsSince(ACCOUNT, "whip:crush", Instant.ofEpochMilli(BASE_TS + 300000));

        assertEquals("Only the later flip of the target group is deleted", 0L,
            count("SELECT COUNT(*) FROM events WHERE type = 'recipe' AND timestamp > " + (BASE_TS + 300000) +
                " AND id IN (SELECT event_id FROM recipe_flips WHERE recipe_key = 'whip:crush')"));
        assertEquals("The early flip of the target group survives", 1L,
            count("SELECT COUNT(*) FROM events e JOIN recipe_flips rf ON rf.event_id = e.id " +
                "WHERE e.type = 'recipe' AND rf.recipe_key = 'whip:crush' AND e.timestamp <= " + (BASE_TS + 300000)));
        assertEquals("The other group's flip in the same interval is untouched", 1L,
            count("SELECT COUNT(*) FROM events e JOIN recipe_flips rf ON rf.event_id = e.id " +
                "WHERE e.type = 'recipe' AND rf.recipe_key = 'whip:dismantle'"));
    }

    /**
     * Finding 3 regression (mechanism level): switching back to SQLite wipes each account
     * and re-imports from the authoritative JSON, so trades deleted during the JSON session
     * stay deleted. Previously the resync was INSERT OR IGNORE only and resurrected them.
     */
    @Test
    public void testWipeAndReimportReconcilesDeletions() throws Exception {
        AccountData data = accountDataWithOffers(
            completeOffer("keep-uuid", true, 10, 50000, BASE_TS),
            completeOffer("del-uuid", true, 5, 1000, BASE_TS + 1000));

        MigrationService service = new MigrationService(storage, new TradePersister(new GsonBuilder().create()));
        Connection conn = storage.getConnection();
        boolean wasAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            service.migrateAccountBatched(conn, ACCOUNT, data);
            conn.commit();
        } finally {
            conn.setAutoCommit(wasAutoCommit);
        }
        assertEquals(1L, count("SELECT COUNT(*) FROM trades WHERE uuid = 'del-uuid'"));

        // Simulated JSON-mode session: the user deletes the del-uuid trade (JSON updated),
        // then switches back to SQLite: forceFullResync wipes the account and re-imports
        // from the now-authoritative JSON.
        data.getTrades().get(0).getHistory().getCompressedOfferEvents()
            .removeIf(o -> "del-uuid".equals(o.getUuid()));
        storage.deleteAccountData(ACCOUNT);
        storage.upsertAccount(ACCOUNT, null);

        conn.setAutoCommit(false);
        try {
            service.migrateAccountBatched(conn, ACCOUNT, data);
            conn.commit();
        } finally {
            conn.setAutoCommit(wasAutoCommit);
        }

        assertEquals("Deleted trade must stay deleted after the switch back", 0L,
            count("SELECT COUNT(*) FROM trades WHERE uuid = 'del-uuid'"));
        assertEquals("Kept trade must survive exactly once", 1L,
            count("SELECT COUNT(*) FROM trades WHERE uuid = 'keep-uuid'"));
    }

    /**
     * Finding 4 regression: both GE-limit counters must round-trip onto the FlippingItem's
     * history, so the next partial buy recalculates the window from the complete-offer base
     * (buy 100, restart, buy 10 -> 110, not 10).
     */
    @Test
    public void testGeLimitCountersRestoreOntoHistory() throws Exception {
        recordTrade(WHIP, "ge-uuid", BASE_TS, 10, 50000, true);
        storage.upsertGeLimitState(ACCOUNT, WHIP, Instant.ofEpochMilli(BASE_TS + 14400000), 110, 100);

        AccountData loaded = storage.loadAccount(ACCOUNT);
        FlippingItem whip = loaded.getTrades().stream()
            .filter(t -> t.getItemId() == WHIP).findFirst().orElse(null);
        assertNotNull(whip);
        assertEquals("Window counter restored", 110, whip.getHistory().getItemsBoughtThisLimitWindow());
        assertEquals("Complete-offer base restored",
            100, whip.getHistory().getItemsBoughtThroughCompleteOffers());
    }
}
