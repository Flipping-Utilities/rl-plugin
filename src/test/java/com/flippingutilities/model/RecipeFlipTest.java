package com.flippingutilities.model;

import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

public class RecipeFlipTest {
    @Test public void countMatchesOutputRatioAndExcludesSameIdInputs() {
        Map<Integer, Map<String, PartialOffer>> outputs = new LinkedHashMap<>();
        outputs.put(20, Collections.singletonMap("b", new PartialOffer("b", 15)));
        outputs.put(10, Collections.singletonMap("a", new PartialOffer("a", 6)));
        Recipe recipe = new Recipe(Collections.singletonList(new RecipeItem(10, 7)),
            Arrays.asList(new RecipeItem(10, 2), new RecipeItem(20, 5)), "Test");
        RecipeFlip flip = new RecipeFlip(Instant.now(), outputs,
            Collections.singletonMap(10, Collections.singletonMap("in", new PartialOffer("in", 21))), 0);
        assertEquals(3, flip.getKnownRecipeCountMade(recipe).getAsLong());
        outputs.remove(10);
        assertEquals(3, flip.getKnownRecipeCountMade(recipe).getAsLong());
    }

    @Test public void syntheticMultiOutputRecipeKeepsUnknownRatiosThroughKeyHydration() {
        Map<Integer, Map<String, PartialOffer>> outputs = new LinkedHashMap<>();
        outputs.put(10, Collections.singletonMap("a", new PartialOffer("a", 6)));
        outputs.put(20, Collections.singletonMap("b", new PartialOffer("b", 15)));
        RecipeFlip flip = new RecipeFlip(Instant.now(), outputs,
            Collections.singletonMap(30, Collections.singletonMap("in", new PartialOffer("in", 21))), 0);
        RecipeFlipGroup group = new RecipeFlipGroup((String) null);
        group.addRecipeFlip(flip);
        group.synthesizeRecipe(null);

        assertEquals("30:0|10:0,20:0", group.getRecipeKey());
        assertTrue(group.getRecipe().getInputs().stream().allMatch(item -> item.getQuantity() == 0));
        assertTrue(group.getRecipe().getOutputs().stream().allMatch(item -> item.getQuantity() == 0));
        assertFalse(flip.getKnownRecipeCountMade(group.getRecipe()).isPresent());
        assertFalse(flip.getKnownRecipeCountMade(null).isPresent());

        RecipeFlipGroup restored = new RecipeFlipGroup(group.getRecipeKey());
        restored.addRecipeFlip(flip);
        restored.synthesizeRecipe(null);
        assertEquals(group.getRecipeKey(), restored.getRecipeKey());
        assertFalse(restored.getKnownRecipeCountMade(restored.getRecipeFlips()).isPresent());

        RecipeFlipGroup known = new RecipeFlipGroup("30:7|10:2,20:5");
        known.addRecipeFlip(flip);
        known.synthesizeRecipe(null);
        assertEquals("30:7|10:2,20:5", known.getRecipeKey());
        assertEquals(3, known.getKnownRecipeCountMade(known.getRecipeFlips()).getAsLong());
    }

    @Test public void missingOffersNeedPositiveConsumptionAndKnownZeroIsNotMissing() {
        PartialOffer partial = new PartialOffer("missing", 0);
        RecipeFlip flip = new RecipeFlip(Instant.now(), Collections.emptyMap(),
            Collections.singletonMap(10, Collections.singletonMap("missing", partial)), 0);
        assertFalse(flip.hasMissingOffers());
        partial.setAmountConsumed(1);
        assertTrue(flip.hasMissingOffers());
        partial.setOffer(offer(true, 0));
        assertFalse(flip.hasMissingOffers());
        assertEquals(0, flip.getProfit());
    }

    @Test public void moneyAndTaxMultiplyAsLongs() {
        OfferEvent buy = offer(true, 1_000_000_000);
        OfferEvent sell = offer(false, 1_000_000_000);
        Map<Integer, Map<String, PartialOffer>> inputs = Collections.singletonMap(10,
            Collections.singletonMap("buy", new PartialOffer(buy, 1000)));
        Map<Integer, Map<String, PartialOffer>> outputs = Collections.singletonMap(20,
            Collections.singletonMap("sell", new PartialOffer(sell, 1000)));
        RecipeFlip flip = new RecipeFlip(Instant.now(), outputs, inputs, 0);
        assertEquals(1_000_000_000_000L, flip.getExpense());
        assertEquals(995_000_000_000L, flip.getRevenue());
        assertEquals(5_000_000_000L, flip.getTaxPaid());
        Map<Integer, Map<String, PartialOffer>> all = new HashMap<>(inputs);
        all.putAll(outputs);
        assertEquals(-5_000_000_000L, RecipeFlip.calculateProfit(all));
    }

    private OfferEvent offer(boolean buy, int price) {
        OfferEvent offer = new OfferEvent();
        offer.setTime(Instant.parse("2026-01-01T00:00:00Z"));
        offer.setBuy(buy);
        offer.setItemId(4151);
        offer.setPrice(price);
        return offer;
    }
}
