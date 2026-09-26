package com.flippingutilities.model;

import com.google.gson.annotations.Expose;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This is the structure that represents a flip made out of various different items, either because
 * those items were combined to make some other one, or were the result of breaking some other item.
 * Ex: guthan set bought then broken up into pieces and sold.
 * The "parent" in these recipeFlips is not necessarily the output item (of combining or breaking stuff), it is
 * the item that I have arbitrarily chosen to own the profits of the recipe flips. This is because profits cannot
 * be divided over all the items in the recipe flip. What decides how much profit each item gets? For that reason
 * I've decided that each recipe has a parent item (the parent item that will end up in the recipeFlip) and that
 * parent item shall own the profits, revenue, expense, etc for the recipe flip.
 */
@Data
@AllArgsConstructor
public class RecipeFlip {
    Instant timeOfCreation;
    Map<Integer, Map<String, PartialOffer>> outputs;
    //item id to a map of offer id to offer
    Map<Integer, Map<String, PartialOffer>> inputs;
    long coinCost;

    public RecipeFlip(Recipe recipe, Map<Integer, Map<String, PartialOffer>> allPartialOffers, long coinsCost) {
        Set<Integer> recipeInputIds = recipe.getInputIds();
        Set<Integer> recipeOutputIds = recipe.getOutputIds();
        this.coinCost = coinsCost;
        this.timeOfCreation = Instant.now();
        this.inputs = allPartialOffers.entrySet().stream().filter(e -> recipeInputIds.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        this.outputs = allPartialOffers.entrySet().stream().filter(e -> recipeOutputIds.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public RecipeFlip clone() {
        Map<Integer, Map<String, PartialOffer>> clonedOutputs = cloneComponents(outputs);
        Map<Integer, Map<String, PartialOffer>> clonedInputs = cloneComponents(inputs);
        return new RecipeFlip(timeOfCreation, clonedOutputs, clonedInputs, coinCost);
    }

    private Map<Integer, Map<String, PartialOffer>> cloneComponents(Map<Integer, Map<String, PartialOffer>> component) {
        return component.entrySet().stream()
            .map(e -> new AbstractMap.SimpleEntry<>(
                e.getKey(),
                e.getValue().entrySet().stream()
                    .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().clone()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public boolean hasMissingOffers() {
        return getPartialOffers().stream().anyMatch(po -> po.amountConsumed > 0 && po.getOffer() == null);
    }

    public long getProfit() {
        return getRevenue() - getExpense();
    }

    private long getIngredientsValue(boolean isBuyOffer) {
        return getPartialOffers().stream()
            .filter(po -> po.getOffer() != null && po.getOffer().isBuy() == isBuyOffer)
            .mapToLong(po -> (long) po.amountConsumed * po.getOffer().getPrice())
            .sum();
    }

    public long getExpense() {
        return getIngredientsValue(true) + coinCost;
    }

    public long getRevenue() {
        return getIngredientsValue(false);
    }

    public long getTaxPaid() {
        return getOutputs().values().stream()
            .mapToLong(
                offerIdToPartialOfferMap -> offerIdToPartialOfferMap.values().stream()
                    .filter(po -> po.getOffer() != null)
                    .mapToLong(po -> (long) po.getOffer().getTaxPaidPerItem() * po.amountConsumed).sum())
            .sum();
    }

    /**
     * This is a utility function used to calculate profit for a given set of partial offers. This doesn't calculate
     * the profit for an instance of a RecipeFlip. Just writing this here as it confused me why we had this and getProfit()
     * @param allPartialOffers
     * @return
     */
    public static long calculateProfit(Map<Integer, Map<String, PartialOffer>> allPartialOffers) {
        List<PartialOffer> offers = allPartialOffers.values().stream()
            .flatMap(offerIdToPartialOfferMap -> offerIdToPartialOfferMap.values().stream())
            .collect(Collectors.toList());

        long revenue = offers.stream()
            .filter(po -> !po.getOffer().isBuy())
            .mapToLong(po -> (long) po.amountConsumed * po.getOffer().getPrice())
            .sum();
        long expense = offers.stream()
            .filter(po -> po.getOffer().isBuy())
            .mapToLong(po -> (long) po.amountConsumed * po.getOffer().getPrice())
            .sum();
        return revenue - expense;
    }

    /**
     * Gets all the partial offers contained in this recipe flip.
     */
    public List<PartialOffer> getPartialOffers() {
        List<PartialOffer> offers = new ArrayList<>();
        inputs.values().forEach(map -> offers.addAll(map.values()));
        outputs.values().forEach(map -> offers.addAll(map.values()));
        return offers;
    }

    public List<PartialOffer> getPartialOffers(int itemId) {
        List<PartialOffer> partialOffers = new ArrayList<>();
        if (outputs.containsKey(itemId)) {
            partialOffers.addAll(outputs.get(itemId).values());
        }
        if (inputs.containsKey(itemId)) {
            partialOffers.addAll(inputs.get(itemId).values());
        }
        return partialOffers;
    }

    /**
     * Calculates recipe executions only when an output's per-execution quantity is known.
     * Consumed output units alone cannot determine how many recipes were made.
     */
    public OptionalLong getKnownRecipeCountMade(Recipe recipe) {
        if (outputs.isEmpty()) {
            return OptionalLong.of(0);
        }
        
        if (recipe != null) {
            for (RecipeItem output : recipe.getOutputs()) {
                Map<String, PartialOffer> offers = outputs.get(output.getId());
                if (offers != null && output.getQuantity() > 0) {
                    return OptionalLong.of(offers.values().stream().mapToLong(po -> po.amountConsumed).sum()
                        / output.getQuantity());
                }
            }
        }
        return OptionalLong.empty();
    }
    
    /**
     * Removes PartialOffers with amountConsumed == 0 and empty maps.
     * Should be called before serialization to reduce file size.
     */
    public void cleanup() {
        inputs = cleanupComponent(inputs);
        outputs = cleanupComponent(outputs);
    }
    
    private Map<Integer, Map<String, PartialOffer>> cleanupComponent(Map<Integer, Map<String, PartialOffer>> component) {
        Map<Integer, Map<String, PartialOffer>> cleaned = new HashMap<>();
        for (Map.Entry<Integer, Map<String, PartialOffer>> entry : component.entrySet()) {
            Map<String, PartialOffer> cleanedInner = new HashMap<>();
            for (Map.Entry<String, PartialOffer> innerEntry : entry.getValue().entrySet()) {
                if (innerEntry.getValue().amountConsumed > 0) {
                    cleanedInner.put(innerEntry.getKey(), innerEntry.getValue());
                }
            }
            if (!cleanedInner.isEmpty()) {
                cleaned.put(entry.getKey(), cleanedInner);
            }
        }
        return cleaned;
    }
}
