package com.flippingutilities.model;

import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This is the structure that represents a flip made out of various different items, either because
 * those items were combined to make some other one, or were the result of breaking some other item.
 * Ex: guthan set bought then broken up into pieces and sold.
 * The "parent" in these CombinationFlips is not necessarily the output item (of combining or breaking stuff), it is
 * the item that I have arbitrarily chosen to own the profits of the combination flips. This is because profits cannot
 * be divided over all the items in the combination flip. What decides how much profit each item gets? For that reason
 * I've decided that each recipe has a parent item (the parent item that will end up in the CombinationFlip) and that
 * parent item shall own the profits, revenue, expense, etc for the combination flip.
 */
@Data
@AllArgsConstructor
public class RecipeFlip {
    Instant timeOfCreation;
    Map<Integer, Map<String, PartialOffer>> outputs;
    //item id to a map of offer id to offer
    Map<Integer, Map<String, PartialOffer>> inputs;

    public RecipeFlip(Recipe recipe, Map<Integer, Map<String, PartialOffer>> allPartialOffers) {
        Set<Integer> recipeInputIds = recipe.getInputIds();
        Set<Integer> recipeOutputIds = recipe.getOutputIds();

        this.timeOfCreation = Instant.now();
        this.inputs = allPartialOffers.entrySet().stream().filter(e -> recipeInputIds.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        this.outputs = allPartialOffers.entrySet().stream().filter(e -> recipeOutputIds.contains(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public RecipeFlip clone() {
        Instant clonedInstant = Instant.ofEpochSecond(timeOfCreation.getEpochSecond());
        Map<Integer, Map<String, PartialOffer>> clonedOutputs = cloneComponents(outputs);
        Map<Integer, Map<String, PartialOffer>> clonedInputs = cloneComponents(inputs);
        return new RecipeFlip(clonedInstant, clonedOutputs, clonedInputs);
    }

    private Map<Integer, Map<String, PartialOffer>> cloneComponents(Map<Integer, Map<String, PartialOffer>> component) {
        return component.entrySet().stream()
            .map(e -> Map.entry(
                e.getKey(),
                e.getValue().entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue().clone()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public long getProfit() {
        return getRevenue() - getExpense();
    }

    private long getExpenseOrRevenue(boolean getExpense) {
        return getPartialOffers().stream()
            .filter(po -> po.offer.isBuy() == getExpense)
            .mapToLong(po -> po.amountConsumed * po.getOffer().getPrice())
            .sum();
    }

    public long getExpense() {
        return getExpenseOrRevenue(true);
    }

    public long getRevenue() {
        return getExpenseOrRevenue(false);
    }

    public long getTaxPaid() {
        return getOutputs().values().stream()
            .mapToLong(
                offerIdToPartialOfferMap -> offerIdToPartialOfferMap.values().stream().mapToInt(po -> po.getOffer().getTaxPaid()).sum())
            .sum();
    }

    public static long calculateProfit(Map<Integer, Map<String, PartialOffer>> allPartialOffers) {
        List<PartialOffer> offers = allPartialOffers.values().stream()
            .flatMap(offerIdToPartialOfferMap -> offerIdToPartialOfferMap.values().stream())
            .collect(Collectors.toList());

        long revenue = offers.stream()
            .filter(po -> !po.getOffer().isBuy())
            .mapToLong(po -> po.amountConsumed * po.getOffer().getPrice())
            .sum();
        long expense = offers.stream()
            .filter(po -> po.getOffer().isBuy())
            .mapToLong(po -> po.amountConsumed * po.getOffer().getPrice())
            .sum();
        return revenue - expense;
    }

    /**
     * Gets all the partial offers contained in this combination flip.
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

    public int getRecipeCountMade(Recipe recipe) {
        RecipeItem randomItemInRecipe = recipe.getOutputs().get(0);
        int quantityOfRandomItemInRecipe = randomItemInRecipe.getQuantity();
        int randomItemIdInRecipe = randomItemInRecipe.getId();
        int amountConsumed = getPartialOffers(randomItemIdInRecipe).stream().mapToInt(po -> po.amountConsumed).sum();
        return amountConsumed/quantityOfRandomItemInRecipe;
    }
}
