package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.model.HistoryManager;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import com.google.gson.Gson;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

/** Legacy account snapshots retain the same domain results after conversion to journal records. */
public class JsonMigrationParityTest {
    private static final String ACCOUNT = "Recipe account";
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");
    private final Gson gson = new Gson();
    private final JsonStorageCodec codec = new JsonStorageCodec(gson);

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void legacyEmbeddedRecipesSurviveWithoutRestoringDeletedHistory() throws Exception {
        AccountData source = legacyAccount();
        File directory = temporaryFolder.newFolder();
        File legacy = new File(directory, ACCOUNT + ".json");
        String original = gson.toJson(source);
        Files.writeString(legacy.toPath(), original);

        AccountData migrated = roundTrip(new TradePersister(gson, directory).loadAccount(ACCOUNT));

        assertEquals("Reading and converting must leave the legacy source available", original,
            Files.readString(legacy.toPath()));
        assertRecipePreserved(migrated);
    }

    @Test
    public void legacyRewriteAndRepeatedConversionPreserveRecipeSnapshots() throws Exception {
        AccountData source = legacyAccount();
        hydrateReferences(source);
        source.markMigrated();
        TradePersister persister = new TradePersister(gson, temporaryFolder.newFolder());
        LegacyJsonFixtures.write(persister, ACCOUNT, source);

        AccountData migrated = roundTrip(persister.loadAccount(ACCOUNT));
        assertRecipePreserved(migrated);
        assertRecipePreserved(roundTrip(migrated));
    }

    @Test
    public void uuidOnlyRecipeComponentsResolveFromPreservedHistory() {
        AccountData source = legacyAccount();
        for (PartialOffer component : source.getRecipeFlipGroups().get(0).getPartialOffers()) {
            OfferEvent offer = component.getOffer();
            if (offer.getItemId() != 4151) {
                FlippingItem item = new FlippingItem(offer.getItemId(), "Ingredient", 70, ACCOUNT);
                item.updateHistory(offer);
                source.getTrades().add(item);
            }
            component.setOfferUuid(offer.getUuid());
            component.setOffer(null);
        }

        AccountData migrated = roundTrip(source);
        hydrateReferences(migrated);
        RecipeFlip flip = migrated.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        assertEquals(530, flip.getProfit());
        assertOffer(flip.getOutputs().get(11802).get("detached-output"), 30, 1000, 1);
    }

    @Test
    public void danglingRecipeReferenceKeepsUnknownPriceAndConsumedQuantity() {
        AccountData source = legacyAccount();
        source.getTrades().clear();
        PartialOffer missing = source.getRecipeFlipGroups().get(0).getRecipeFlips().get(0)
            .getInputs().get(4151).get("normal");
        missing.setOfferUuid("normal");
        missing.setOffer(null);

        AccountData migrated = roundTrip(source);
        hydrateReferences(migrated);
        RecipeFlip flip = migrated.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        PartialOffer unresolved = flip.getInputs().get(4151).get("normal");
        assertNull("A missing price must not become zero", unresolved.getOffer());
        assertEquals("normal", unresolved.getOfferUuid());
        assertEquals(2, unresolved.getAmountConsumed());
        assertTrue(flip.hasMissingOffers());
        assertTrue(migrated.getTrades().isEmpty());
        assertOffer(flip.getInputs().get(4587).get("detached-input"), 20, 250, 1);
        assertOffer(flip.getOutputs().get(11802).get("detached-output"), 30, 1000, 1);
    }

    @Test
    public void syntheticRecipeRatiosRemainUnknownAfterMigration() throws Exception {
        AccountData source = legacyAccount();
        RecipeFlipGroup group = source.getRecipeFlipGroups().get(0);
        group.setRecipeKey(null);
        RecipeFlip flip = group.getRecipeFlips().get(0);
        OfferEvent secondOutput = complete(ACCOUNT, 11804, "second-output", TIME.toEpochMilli(), 15, 1000, false);
        Map<Integer, Map<String, PartialOffer>> outputs = new LinkedHashMap<>(flip.getOutputs());
        outputs.get(11802).get("detached-output").setAmountConsumed(6);
        outputs.put(11804, Collections.singletonMap("second-output", new PartialOffer(secondOutput, 15)));
        flip.setOutputs(outputs);
        group.synthesizeRecipe(null);
        String key = "4151:0,4587:0|11802:0,11804:0";
        assertEquals(key, group.getRecipeKey());
        assertFalse(flip.getKnownRecipeCountMade(group.getRecipe()).isPresent());
        long expectedProfit = flip.getProfit();
        TradePersister persister = new TradePersister(gson, temporaryFolder.newFolder());
        LegacyJsonFixtures.write(persister, ACCOUNT, source);

        RecipeFlipGroup restored = roundTrip(persister.loadAccount(ACCOUNT)).getRecipeFlipGroups().get(0);
        assertEquals(key, restored.getRecipeKey());
        assertFalse(restored.getKnownRecipeCountMade(restored.getRecipeFlips()).isPresent());
        RecipeFlip restoredFlip = restored.getRecipeFlips().get(0);
        assertEquals(6, restoredFlip.getOutputs().get(11802).get("detached-output").getAmountConsumed());
        assertEquals(15, restoredFlip.getOutputs().get(11804).get("second-output").getAmountConsumed());
        assertEquals(expectedProfit, restoredFlip.getProfit());
    }

    @Test
    public void migrationRetainsSessionFavoritesVisibilityAndLimitsForAllItems() {
        AccountData source = legacyAccount();
        source.setSessionStartTime(TIME);
        source.setAccumulatedSessionTimeMillis(3_600_000);
        source.setLastSessionTimeUpdate(TIME.plusSeconds(3600));
        source.setLastStoredAt(TIME.plusSeconds(3601));
        source.setLastModifiedAt(TIME.plusSeconds(3602));
        FlippingItem traded = source.getTrades().get(0);
        traded.setValidFlippingPanelItem(false);
        traded.setFavorite(true);
        traded.setFavoriteCode("whip");
        traded.getHistory().setItemsBoughtThisLimitWindow(110);
        traded.getHistory().setItemsBoughtThroughCompleteOffers(100);
        traded.getHistory().setNextGeLimitRefresh(TIME.plusSeconds(14_400));
        FlippingItem searched = new FlippingItem(11804, "Searched item", 70, ACCOUNT);
        searched.setFavoriteCode("old-search-code");
        searched.setFavorite(false);
        source.getTrades().add(searched);

        AccountData migrated = roundTrip(source);
        assertEquals(TIME, migrated.getSessionStartTime());
        assertEquals(3_600_000, migrated.getAccumulatedSessionTimeMillis());
        assertEquals(source.getLastSessionTimeUpdate(), migrated.getLastSessionTimeUpdate());
        assertEquals(source.getLastStoredAt(), migrated.getLastStoredAt());
        assertEquals(source.getLastModifiedAt(), migrated.getLastModifiedAt());
        assertEquals(2, migrated.getTrades().size());
        FlippingItem restored = migrated.getTrades().get(0);
        assertEquals(Boolean.FALSE, restored.getValidFlippingPanelItem());
        assertTrue(restored.isFavorite());
        assertEquals("whip", restored.getFavoriteCode());
        assertEquals(110, restored.getHistory().getItemsBoughtThisLimitWindow());
        assertEquals(100, restored.getHistory().getItemsBoughtThroughCompleteOffers());
        assertEquals(TIME.plusSeconds(14_400), restored.getHistory().getNextGeLimitRefresh());
        FlippingItem restoredSearch = migrated.getTrades().get(1);
        assertTrue(restoredSearch.getHistory().getCompressedOfferEvents().isEmpty());
        assertFalse(restoredSearch.isFavorite());
        assertEquals("old-search-code", restoredSearch.getFavoriteCode());
        restoredSearch.setFavorite(true);
        assertEquals("old-search-code", roundTrip(migrated).getTrades().get(1).getFavoriteCode());
    }

    @Test
    public void missingOfferTimestampRemainsMissingWithoutDroppingOtherHistory() {
        AccountData source = legacyAccount();
        source.getRecipeFlipGroups().get(0).getRecipeFlips().get(0)
            .getOutputs().get(11802).get("detached-output").getOffer().setTime(null);

        AccountData restored = roundTrip(source);
        assertEquals(1, restored.getTrades().get(0).getHistory().getCompressedOfferEvents().size());
        PartialOffer output = restored.getRecipeFlipGroups().get(0).getRecipeFlips().get(0)
            .getOutputs().get(11802).get("detached-output");
        assertNull(output.getOffer().getTime());
        assertEquals(1000, output.getOffer().getPreTaxPrice());
    }

    @Test
    public void convertedHistoryKeepsMarginChecksPairedWithEachOther() {
        AccountData source = new AccountData();
        FlippingItem item = new FlippingItem(4151, "Whip", 70, ACCOUNT);
        source.getTrades().add(item);
        List<OfferEvent> history = item.getHistory().getCompressedOfferEvents();
        history.add(timedOffer("ordinary-buy", GrandExchangeOfferState.BOUGHT, 1, 90, 100, 0));
        history.add(timedOffer("margin-buy", GrandExchangeOfferState.BOUGHT, 1, 110, 2, 60));
        history.add(timedOffer("margin-sell", GrandExchangeOfferState.SOLD, 1, 100, 2, 70));

        List<OfferEvent> migrated = roundTrip(source).getTrades().get(0).getHistory().getCompressedOfferEvents();
        assertEquals(history, migrated);
        assertFalse(migrated.get(0).isMarginCheck());
        assertTrue(migrated.get(1).isMarginCheck());
        assertTrue(migrated.get(2).isMarginCheck());
        assertEquals(110, HistoryManager.getFlips(migrated).get(0).getBuyPrice());
        assertEquals(history.get(2).getPrice(), HistoryManager.getFlips(migrated).get(0).getSellPrice());
    }

    @Test
    public void completedCancelledAndOfflineSlotTimersSurviveConversion() {
        AccountData source = new AccountData();
        OfferEvent completed = timedOffer("complete", GrandExchangeOfferState.BOUGHT, 5, 100, 100, 125);
        source.getLastOffers().put(0, completed);
        OfferEvent cancelled = timedOffer("cancelled-empty", GrandExchangeOfferState.CANCELLED_BUY, 0, 100, 100, 180);
        cancelled.setSlot(1);
        source.getLastOffers().put(1, cancelled);
        OfferEvent offline = timedOffer("offline", GrandExchangeOfferState.SOLD, 5, 100, 100, 200);
        offline.setSlot(2);
        offline.setBeforeLogin(true);
        source.getLastOffers().put(2, offline);

        AccountData restored = roundTrip(source);
        assertTrue("Slot snapshots must not resurrect removed history", restored.getTrades().isEmpty());
        restored.prepareForUse(new FlippingPlugin());
        List<SlotActivityTimer> timers = restored.getSlotTimers();
        assertEquals("00:02:05", timers.get(0).createFormattedTimeString());
        assertEquals("00:03:00", timers.get(1).createFormattedTimeString());
        assertTrue(timers.get(2).offerOccurredAtUnknownTime);
        assertNull(timers.get(2).createFormattedTimeString());
    }

    private OfferEvent timedOffer(String uuid, GrandExchangeOfferState state, int quantity, int price,
                                  int ticks, int seconds) {
        return new OfferEvent(uuid, OfferEvent.isBuy(state), 4151, quantity, price,
            TIME.plusSeconds(seconds), 0, state, 123, ticks, quantity, TIME, false,
            ACCOUNT, "Whip", price, quantity * price);
    }

    private AccountData roundTrip(AccountData source) {
        return codec.decodeAccount(ACCOUNT, codec.encodeAccount(ACCOUNT, source));
    }

    private AccountData legacyAccount() {
        AccountData account = new AccountData();
        OfferEvent normal = complete(ACCOUNT, 4151, "normal", TIME.toEpochMilli(), 10, 100, true);
        OfferEvent input = complete(ACCOUNT, 4587, "detached-input", TIME.toEpochMilli(), 20, 250, true);
        OfferEvent output = complete(ACCOUNT, 11802, "detached-output", TIME.toEpochMilli(), 30, 1000, false);
        FlippingItem item = new FlippingItem(4151, "Abyssal whip", 70, ACCOUNT);
        item.updateHistory(normal);
        account.getTrades().add(item);
        Map<Integer, Map<String, PartialOffer>> inputs = new LinkedHashMap<>();
        inputs.put(4151, Collections.singletonMap("normal", new PartialOffer(normal, 2)));
        inputs.put(4587, Collections.singletonMap("detached-input", new PartialOffer(input, 1)));
        RecipeFlip flip = new RecipeFlip(TIME,
            Collections.singletonMap(11802, Collections.singletonMap("detached-output", new PartialOffer(output, 1))),
            inputs, 0);
        flip.getPartialOffers().forEach(component -> component.setOfferUuid(null));
        RecipeFlipGroup group = new RecipeFlipGroup("4151:2,4587:1|11802:1");
        group.addRecipeFlip(flip);
        account.getRecipeFlipGroups().add(group);
        return account;
    }

    private void hydrateReferences(AccountData account) {
        Map<String, OfferEvent> history = new HashMap<>();
        account.getTrades().forEach(item -> item.getHistory().getCompressedOfferEvents()
            .forEach(offer -> history.put(offer.getUuid(), offer)));
        account.getRecipeFlipGroups().forEach(group -> group.getPartialOffers()
            .forEach(component -> component.hydrateOffer(history)));
    }

    private void assertRecipePreserved(AccountData account) {
        assertEquals("Recipe-only snapshots must not reappear in trade history", 1, account.getTrades().size());
        assertEquals(1, account.getTrades().get(0).getHistory().getCompressedOfferEvents().size());
        RecipeFlip flip = account.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        assertEquals(450, flip.getExpense());
        assertEquals(980, flip.getRevenue());
        assertEquals(530, flip.getProfit());
        assertEquals(20, flip.getTaxPaid());
        assertOffer(flip.getInputs().get(4151).get("normal"), 10, 100, 2);
        assertOffer(flip.getInputs().get(4587).get("detached-input"), 20, 250, 1);
        assertOffer(flip.getOutputs().get(11802).get("detached-output"), 30, 1000, 1);
    }

    private void assertOffer(PartialOffer component, int quantity, int price, int consumed) {
        assertNotNull(component.getOffer());
        assertEquals(quantity, component.getOffer().getCurrentQuantityInTrade());
        assertEquals(price, component.getOffer().getPreTaxPrice());
        assertEquals(consumed, component.getAmountConsumed());
        assertEquals(TIME, component.getOffer().getTime());
    }
}
