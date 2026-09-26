package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.Section;
import com.flippingutilities.ui.uiutilities.BrowserGson;
import com.flippingutilities.ui.widgets.SlotActivityTimer;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

import static org.junit.Assert.*;

public class BrowserGsonTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Gson nativeGson = new Gson();
    private final Gson browserGson = BrowserGson.create();

    @Test public void strictAccountReaderPreservesTimersRecipesAndLegacyInstants() throws Exception {
        File directory = temporary.newFolder();
        String offer = "{\"uuid\":\"synthetic-offer\",\"b\":true,\"id\":4151,\"cQIT\":3,"
            + "\"p\":42,\"t\":1700000000000,\"s\":5,\"st\":\"BUYING\",\"tQIT\":10}";
        write(directory, "Synthetic.json", "{"
            + "\"sessionStartTime\":1700000000000,\"lastModifiedAt\":1700000000000,"
            + "\"slotTimers\":[{\"slotIndex\":5,\"lastUpdate\":{\"seconds\":1700000000,\"nanos\":123},"
            + "\"tradeStartTime\":\"2023-11-14T22:13:20Z\",\"currentOffer\":" + offer + ","
            + "\"offerOccurredAtUnknownTime\":true,\"futureTimerField\":123}],"
            + "\"recipeFlipGroups\":[{\"recipeKey\":\"synthetic\",\"recipeFlips\":[{"
            + "\"timeOfCreation\":1700000000000,\"coinCost\":9007199254740993,"
            + "\"inputs\":{\"4151\":{\"synthetic-offer\":{\"offerUuid\":\"synthetic-offer\","
            + "\"amountConsumed\":2,\"offer\":" + offer + "}}},\"outputs\":{},\"futureRecipeField\":true}]}]}" );

        AccountData expected = new TradePersister(nativeGson, directory).loadAllAccountsForMigration().get("Synthetic");
        AccountData actual = new TradePersister(browserGson, directory).loadAllAccountsForMigration().get("Synthetic");
        assertEquals(nativeGson.toJsonTree(expected), nativeGson.toJsonTree(actual));
        SlotActivityTimer timer = actual.getSlotTimers().get(0);
        assertEquals(5, timer.getSlotIndex());
        assertEquals(Instant.ofEpochMilli(1700000000000L), timer.tradeStartTime);
        assertEquals("synthetic-offer", timer.currentOffer.getUuid());
        assertTrue(timer.offerOccurredAtUnknownTime);
        RecipeFlip recipe = actual.getRecipeFlipGroups().get(0).getRecipeFlips().get(0);
        assertEquals(9007199254740993L, recipe.getCoinCost());
        assertEquals(2, recipe.getInputs().get(4151).get("synthetic-offer").getAmountConsumed());
        assertTrue(recipe.getOutputs().isEmpty());
    }

    @Test public void accountWideReaderPreservesCustomRecipesAndSectionLabels() throws Exception {
        File directory = temporary.newFolder();
        write(directory, "accountwide.json", "{\"sections\":[{\"name\":\"Custom\","
            + "\"labels\":{\"profit each\":true,\"custom label\":false},\"defaultExpanded\":true}],"
            + "\"localRecipes\":[{\"name\":\"Synthetic recipe\",\"inputs\":[{\"id\":4151,\"quantity\":3,"
            + "\"futureItemField\":true}],\"outputs\":[{\"id\":11802,\"quantity\":1}]}],"
            + "\"enhancedSlots\":false,\"futureAccountWideField\":{\"value\":1}}" );
        AccountWideData expected = new TradePersister(nativeGson, directory).loadAccountWideData();
        AccountWideData actual = new TradePersister(browserGson, directory).loadAccountWideData();
        assertEquals(nativeGson.toJsonTree(expected), nativeGson.toJsonTree(actual));
        assertEquals(2, actual.getSections().get(0).getLabels().size());
        assertTrue(actual.getSections().get(0).isDefaultExpanded());
        assertEquals("Synthetic recipe", actual.getLocalRecipes().get(0).getName());
        assertEquals(4151, actual.getLocalRecipes().get(0).getInputs().get(0).getId());
        assertEquals(3, actual.getLocalRecipes().get(0).getInputs().get(0).getQuantity());
        assertEquals(11802, actual.getLocalRecipes().get(0).getOutputs().get(0).getId());
        assertFalse(actual.isEnhancedSlots());
    }

    @Test public void omittedAndNullFieldsRetainNativeUnsafeDefaults() {
        Class<?>[] types = {SlotActivityTimer.class, RecipeFlip.class, Recipe.class, RecipeItem.class, Section.class};
        for (Class<?> type : types) {
            for (String json : new String[]{"{}", "{\"unknown\":true,\"name\":null,\"inputs\":null,\"labels\":null}"}) {
                assertEquals(type.getName(), nativeGson.toJsonTree(nativeGson.fromJson(json, type)),
                    nativeGson.toJsonTree(browserGson.fromJson(json, type)));
            }
        }
        assertNull(browserGson.fromJson("{}", Section.class).getLabels());
        assertNull(browserGson.fromJson("{}", RecipeFlip.class).getTimeOfCreation());
        assertFalse(browserGson.fromJson("{}", SlotActivityTimer.class).offerOccurredAtUnknownTime);
    }

    private static void write(File directory, String name, String json) throws Exception {
        Files.write(new File(directory, name).toPath(), json.getBytes(StandardCharsets.UTF_8));
    }
}
