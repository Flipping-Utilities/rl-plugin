package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class JsonStorageCodecTest {
    private static final String ACCOUNT = "Player / 日本語";
    private static final Instant TIME = Instant.parse("2026-09-28T10:20:30.123456789Z");
    private final JsonStorageCodec codec = new JsonStorageCodec(new Gson());

    @Test
    public void roundTripPreservesOrderedHistoryMetadataAndExactNumbers() {
        AccountData source = account();
        source.setSessionStartTime(TIME.minusSeconds(10));
        source.setAccumulatedSessionTimeMillis(9_007_199_254_740_993L);
        source.setLastSessionTimeUpdate(TIME);
        source.setLastStoredAt(TIME.plusNanos(1));
        source.setLastModifiedAt(TIME.plusNanos(2));
        FlippingItem item = source.getTrades().get(0);
        item.setValidFlippingPanelItem(false);
        item.setFavorite(true);
        item.setFavoriteCode("custom");
        item.getHistory().setNextGeLimitRefresh(TIME.plusSeconds(100));
        item.getHistory().setItemsBoughtThisLimitWindow(123);
        item.getHistory().setItemsBoughtThroughCompleteOffers(99);
        OfferEvent first = offer("first", TIME.plusSeconds(1));
        OfferEvent second = offer("second", TIME);
        item.getHistory().getCompressedOfferEvents().addAll(Arrays.asList(first, second));
        FlippingItem empty = new FlippingItem(4587, "Favorite without trades", 42, ACCOUNT);
        empty.setFavorite(true);
        empty.setValidFlippingPanelItem(false);
        source.getTrades().add(empty);

        Map<String, JsonElement> records = codec.encodeAccount(ACCOUNT, source);
        AccountData restored = codec.decodeAccount(ACCOUNT, reversed(records));

        assertEquals(source.getSessionStartTime(), restored.getSessionStartTime());
        assertEquals(source.getAccumulatedSessionTimeMillis(), restored.getAccumulatedSessionTimeMillis());
        assertEquals(source.getLastSessionTimeUpdate(), restored.getLastSessionTimeUpdate());
        assertEquals(source.getLastStoredAt(), restored.getLastStoredAt());
        assertEquals(source.getLastModifiedAt(), restored.getLastModifiedAt());
        assertEquals(2, restored.getTrades().size());
        FlippingItem actual = restored.getTrades().get(0);
        assertEquals(item.getItemName(), actual.getItemName());
        assertEquals(ACCOUNT, actual.getFlippedBy());
        assertEquals(Boolean.FALSE, actual.getValidFlippingPanelItem());
        assertTrue(actual.isFavorite());
        assertEquals("custom", actual.getFavoriteCode());
        assertEquals(item.getHistory().getNextGeLimitRefresh(), actual.getHistory().getNextGeLimitRefresh());
        assertEquals(123, actual.getHistory().getItemsBoughtThisLimitWindow());
        assertEquals(99, actual.getHistory().getItemsBoughtThroughCompleteOffers());
        assertOffer(first, actual.getHistory().getCompressedOfferEvents().get(0));
        assertOffer(second, actual.getHistory().getCompressedOfferEvents().get(1));
        assertTrue(restored.getTrades().get(1).isFavorite());
        assertTrue(restored.getTrades().get(1).getHistory().getCompressedOfferEvents().isEmpty());
        assertNull(restored.getSlotTimers());
        assertFalse(records.toString().contains("slotTimers"));
        assertFalse(records.toString().contains("totalGELimit"));
        assertEquals(records, codec.encodeAccount(ACCOUNT, restored));
    }

    @Test
    public void appendingHistoryChangesOnlyTheNewOfferRecord() {
        AccountData source = account();
        source.getTrades().get(0).getHistory().getCompressedOfferEvents().add(offer("one", TIME));
        Map<String, JsonElement> before = codec.encodeAccount(ACCOUNT, source);
        source.getTrades().get(0).getHistory().getCompressedOfferEvents().add(offer("two", TIME.plusNanos(1)));
        Map<String, JsonElement> after = codec.encodeAccount(ACCOUNT, source);
        assertEquals(before.size() + 1, after.size());
        for (String key : before.keySet()) assertEquals(key, before.get(key), after.get(key));
    }

    @Test
    public void sessionMetadataUpdateDoesNotTraverseOrNormalizeHistory() {
        AccountData source = account();
        JsonElement initial = codec.encodeAccountMetadata(ACCOUNT, source);
        assertEquals(codec.encodeAccount(ACCOUNT, source).get(JsonStorageCodec.accountPrefix(ACCOUNT) + "account"), initial);
        // Deliberately inaccessible history proves a session tick has no dependency on it.
        source.setTrades(new java.util.AbstractList<FlippingItem>() {
            @Override public FlippingItem get(int index) { throw new AssertionError("History traversed"); }
            @Override public int size() { throw new AssertionError("History traversed"); }
        });
        source.setAccumulatedSessionTimeMillis(1234);
        source.setLastSessionTimeUpdate(TIME);
        JsonElement changed = codec.encodeAccountMetadata(ACCOUNT, source);
        assertEquals(1234L, changed.getAsJsonObject().get("accumulatedSessionTimeMillis").getAsLong());
        assertEquals(TIME.toString(), changed.getAsJsonObject().get("lastSessionTimeUpdate").getAsString());
        assertEquals(0L, initial.getAsJsonObject().get("accumulatedSessionTimeMillis").getAsLong());
        source.setVersion(AccountData.CURRENT_VERSION + 1);
        invalid(() -> codec.encodeAccountMetadata(ACCOUNT, source));
    }

    @Test
    public void slotsAndRecipesKeepIndependentSnapshotsAndUnresolvedReferences() {
        AccountData source = account();
        OfferEvent history = offer("shared", TIME);
        source.getTrades().get(0).getHistory().getCompressedOfferEvents().add(history);
        OfferEvent slot = history.clone();
        slot.setCurrentQuantityInTrade(55);
        slot.setState(GrandExchangeOfferState.BUYING);
        source.getLastOffers().put(2, slot);
        OfferEvent offHistory = offer("off-history", TIME.minusSeconds(10));
        source.getLastOffers().put(4, offHistory);
        OfferEvent recipeSnapshot = history.clone();
        recipeSnapshot.setPrice(7L);
        PartialOffer consumed = new PartialOffer(recipeSnapshot, 3);
        PartialOffer detached = new PartialOffer(offHistory.clone(), 4);
        PartialOffer unresolved = new PartialOffer("missing", 5);
        Map<String, PartialOffer> byId = new LinkedHashMap<>();
        byId.put("shared", consumed);
        byId.put("off-history", detached);
        byId.put("missing", unresolved);
        Map<Integer, Map<String, PartialOffer>> inputs = new LinkedHashMap<>();
        inputs.put(4151, byId);
        inputs.put(4587, Collections.emptyMap());
        RecipeFlipGroup group = new RecipeFlipGroup("custom/key");
        group.getRecipeFlips().add(new RecipeFlip(TIME, Collections.emptyMap(), inputs, 9_007_199_254_740_993L));
        group.getRecipeFlips().add(new RecipeFlip(TIME.plusNanos(1), Collections.emptyMap(), Collections.emptyMap(), 2));
        source.getRecipeFlipGroups().add(group);

        AccountData restored = codec.decodeAccount(ACCOUNT, codec.encodeAccount(ACCOUNT, source));
        assertEquals(1, restored.getTrades().get(0).getHistory().getCompressedOfferEvents().size());
        assertOffer(slot, restored.getLastOffers().get(2));
        assertOffer(offHistory, restored.getLastOffers().get(4));
        RecipeFlip flip = restored.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        assertEquals(TIME, flip.getTimeOfCreation());
        assertEquals(9_007_199_254_740_993L, flip.getCoinCost());
        assertEquals(TIME.plusNanos(1), restored.getRecipeFlipGroups().get(0).getRecipeFlips().get(1).getTimeOfCreation());
        Map<String, PartialOffer> components = flip.getInputs().get(4151);
        assertOffer(recipeSnapshot, components.get("shared").getOffer());
        assertEquals(3, components.get("shared").getAmountConsumed());
        assertOffer(offHistory, components.get("off-history").getOffer());
        assertEquals("missing", components.get("missing").getOfferUuid());
        assertNull(components.get("missing").getOffer());
        assertEquals(5, components.get("missing").getAmountConsumed());
        assertTrue(flip.getInputs().containsKey(4587));
        assertTrue(flip.getInputs().get(4587).isEmpty());
        assertEquals(history.getPreTaxPrice(), restored.getTrades().get(0).getHistory().getCompressedOfferEvents().get(0).getPreTaxPrice());
    }

    @Test
    public void legacyIdNormalizationIsStableAndPreservesSlotHistoryIdentity() {
        AccountData source = account();
        OfferEvent history = offer(null, TIME);
        source.getTrades().get(0).getHistory().getCompressedOfferEvents().add(history);
        source.getLastOffers().put(2, history.clone());
        Map<String, JsonElement> first = codec.encodeAccount(ACCOUNT, source);
        assertNotNull(history.getUuid());
        assertEquals(history.getUuid(), source.getLastOffers().get(2).getUuid());
        assertEquals(first, codec.encodeAccount(ACCOUNT, source));
    }

    @Test
    public void discoversDistinctNamesAndRejectsOrphanedAccounts() {
        Map<String, JsonElement> records = codec.encodeAccount(ACCOUNT, account());
        records.putAll(codec.encodeAccount("Other account", account()));
        assertEquals(2, codec.accountNames(records).size());
        assertTrue(codec.accountNames(records).contains(ACCOUNT));
        assertFalse(JsonStorageCodec.accountPrefix(ACCOUNT).contains("日本語"));
        records.remove(JsonStorageCodec.accountPrefix(ACCOUNT) + "account");
        invalid(() -> codec.accountNames(records));
    }

    @Test
    public void rejectsDuplicateIdentitiesAndUnsupportedOrIncompleteRecords() {
        AccountData source = account();
        source.getTrades().get(0).getHistory().getCompressedOfferEvents().add(offer("duplicate", TIME));
        source.getTrades().get(0).getHistory().getCompressedOfferEvents().add(offer("duplicate", TIME.plusSeconds(1)));
        invalid(() -> codec.encodeAccount(ACCOUNT, source));

        Map<String, JsonElement> future = codec.encodeAccount(ACCOUNT, account());
        future.get(JsonStorageCodec.accountPrefix(ACCOUNT) + "account").getAsJsonObject().addProperty("formatVersion", 2);
        invalid(() -> codec.decodeAccount(ACCOUNT, future));

        Map<String, JsonElement> unknown = codec.encodeAccount(ACCOUNT, account());
        unknown.get(JsonStorageCodec.accountPrefix(ACCOUNT) + "items/4151").getAsJsonObject().addProperty("futureField", true);
        invalid(() -> codec.decodeAccount(ACCOUNT, unknown));

        Map<String, JsonElement> fractional = codec.encodeAccount(ACCOUNT, account());
        fractional.get(JsonStorageCodec.accountPrefix(ACCOUNT) + "items/4151").getAsJsonObject().addProperty("position", 0.5);
        invalid(() -> codec.decodeAccount(ACCOUNT, fractional));

        AccountData oneOffer = account();
        oneOffer.getTrades().get(0).getHistory().getCompressedOfferEvents().add(offer("one", TIME));
        Map<String, JsonElement> nested = codec.encodeAccount(ACCOUNT, oneOffer);
        nested.values().stream().filter(value -> value.getAsJsonObject().has("offer")).findFirst().get()
            .getAsJsonObject().getAsJsonObject("offer").remove("price");
        invalid(() -> codec.decodeAccount(ACCOUNT, nested));

        Map<String, JsonElement> missingItem = codec.encodeAccount(ACCOUNT, oneOffer);
        missingItem.remove(JsonStorageCodec.accountPrefix(ACCOUNT) + "items/4151");
        invalid(() -> codec.decodeAccount(ACCOUNT, missingItem));
    }

    @Test
    public void accountWideRoundTripRetainsCustomRecipesAndSettings() {
        AccountWideData data = new AccountWideData();
        data.setDefaults();
        data.setEnhancedSlots(false);
        data.setJwt("test-token");
        data.getLocalRecipes().add(new Recipe(Collections.singletonList(new RecipeItem(4151, 2)),
            Collections.singletonList(new RecipeItem(4587, 1)), "Custom recipe"));
        JsonElement encoded = codec.encodeAccountWide(data);
        AccountWideData restored = codec.decodeAccountWide(encoded);
        assertEquals(encoded, codec.encodeAccountWide(restored));
        assertEquals("Custom recipe", restored.getLocalRecipes().get(0).getName());
        assertFalse(restored.isEnhancedSlots());
        assertEquals("test-token", restored.getJwt());
        encoded.getAsJsonObject().addProperty("formatVersion", 3);
        invalid(() -> codec.decodeAccountWide(encoded));
    }

    private AccountData account() {
        AccountData data = new AccountData();
        data.getTrades().add(new FlippingItem(4151, "Abyssal whip", 70, ACCOUNT));
        return data;
    }

    private OfferEvent offer(String id, Instant time) {
        return new OfferEvent(id, false, 4151, 17, 9_007_199_254_740_993L,
            time, 2, GrandExchangeOfferState.CANCELLED_SELL, 99, 41, 20,
            time.minusNanos(123), true, null, null, 0L, 0L);
    }

    private void assertOffer(OfferEvent expected, OfferEvent actual) {
        assertEquals(expected.getUuid(), actual.getUuid());
        assertEquals(expected.isBuy(), actual.isBuy());
        assertEquals(expected.getItemId(), actual.getItemId());
        assertEquals(expected.getCurrentQuantityInTrade(), actual.getCurrentQuantityInTrade());
        assertEquals(expected.getTotalQuantityInTrade(), actual.getTotalQuantityInTrade());
        assertEquals(expected.getPreTaxPrice(), actual.getPreTaxPrice());
        assertEquals(expected.getTime(), actual.getTime());
        assertEquals(expected.getSlot(), actual.getSlot());
        assertEquals(expected.getState(), actual.getState());
        assertEquals(expected.getTickArrivedAt(), actual.getTickArrivedAt());
        assertEquals(expected.getTicksSinceFirstOffer(), actual.getTicksSinceFirstOffer());
        assertEquals(expected.getTradeStartedAt(), actual.getTradeStartedAt());
        assertEquals(expected.isBeforeLogin(), actual.isBeforeLogin());
    }

    private Map<String, JsonElement> reversed(Map<String, JsonElement> records) {
        List<String> keys = new ArrayList<>(records.keySet());
        Collections.reverse(keys);
        Map<String, JsonElement> result = new LinkedHashMap<>();
        keys.forEach(key -> result.put(key, records.get(key)));
        return result;
    }

    private void invalid(Runnable action) {
        try {
            action.run();
            fail("Expected invalid data to be rejected");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
