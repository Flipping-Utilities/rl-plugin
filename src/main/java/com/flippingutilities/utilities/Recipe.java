package com.flippingutilities.utilities;

import com.flippingutilities.model.PartialOffer;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;

@Data
public class Recipe {
    RelationshipType relationshipType;
    RecipeItem parent;
    List<RecipeItem> children;

    /**
     * Gets the ids of the parent and the children.
     */
    public List<Integer> getIds() {
        List<Integer> ids = new ArrayList<>();
        ids.add(parent.id);
        ids.addAll(children.stream().map(c -> c.id).collect(Collectors.toList()));
        return ids;
    }

    public Map<Integer, Integer> getItemIdToQuantity() {
        Map<Integer, Integer> itemIdToQuantity = new HashMap<>();
        itemIdToQuantity.put(parent.id, parent.quantity);
        children.forEach(c -> itemIdToQuantity.put(c.id, c.quantity));
        return itemIdToQuantity;
    }

    public Map<Integer, Integer> getTargetValues(int parentAmountConsumed) {
        Map<Integer, Integer> itemIdToQuantity = getItemIdToQuantity();
        int parentQuantityInRecipe = parent.quantity;
        int numRecipesThatCanBeMade = parentAmountConsumed / parentQuantityInRecipe;

        return itemIdToQuantity.entrySet().stream().map(e -> {
            int quantityInRecipe = e.getValue();
            return Map.entry(e.getKey(), quantityInRecipe * numRecipesThatCanBeMade);
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * This method computes the initial target values for each of the items when the panel
     * first shows up. This is so that the user doesn't have to manually input them (tho they can still
     * adjust them if they want). The target values are selected such that the max amount of
     * combinations/recipes can be made. Here is an example:
     *
     * Lets say there is a recipe where for every item C you need 3 of item A and 5 of item B. We
     * can shorten it by saying the recipe is 1C, 3A, and 5B. Now, lets say we have a quantity of 5 for item C,
     * quantity of 9 for item A and a quantity of 25 for item B. What is the max amount of this recipe you can make?
     * The max amount is only 3 because it is limited by that fact you only have 9 A items, even though the amount
     * of C and B items you have can support more recipes.
     *
     * Once we know the max amount of recipes the offers can support, we can multiply the max amount by the
     * amount the recipe needs of each item to know how much of each item we will need to make the max amount of
     * recipes. Continuing with the example above, our max recipes is 3. To get the amount of C items, we would do
     * 1 * 3. To get the amount of B items, we would do 5 * 3. To get the amount of A items, we would do 3 * 3.
     *
     * In short:
     * recipe = 3A, 5B, 1C
     * quantities = 9A 25B 5C
     * max # of this recipe supported per item: A=3, B=5, C=5
     * # of recipe supported in actuality: 3, cause A is constraining it.
     * target values: A = 9(3 * 3), B = 15(3 * 5), C = 3(3 * 1)
     *
     * @param itemIdToPartialOffers all the suitable partial offers for each item
     */
    public Map<Integer, Integer> getTargetValuesForMaxRecipeCount(
        Map<Integer, List<PartialOffer>> itemIdToPartialOffers) {
        Map<Integer, Integer> itemIdToQuantity = getItemIdToQuantity();

        Map<Integer, Integer> itemIdToMaxRecipesThatCanBeMade = getItemIdToMaxRecipesThatCanBeMade(itemIdToPartialOffers);

        int lowestRecipeCountThatCanBeMade = itemIdToMaxRecipesThatCanBeMade.values().stream().min(Comparator.comparingInt(i -> i)).get();

        return itemIdToQuantity.entrySet().stream().
            map(e -> Map.entry(e.getKey(), e.getValue() * lowestRecipeCountThatCanBeMade)).
            collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Gets a mapping of item id to the max amount of recipes that can be contributed to for each item in the given
     * partial offers
     */
    public Map<Integer, Integer> getItemIdToMaxRecipesThatCanBeMade(Map<Integer, List<PartialOffer>> itemIdToPartialOffers) {
        Map<Integer, Integer> itemIdToQuantity = getItemIdToQuantity();
        return itemIdToPartialOffers.entrySet().stream().map(e -> {
            int itemId = e.getKey();
            long totalQuantity = e.getValue().stream().
                mapToLong(po -> po.getOffer().getCurrentQuantityInTrade() - po.amountConsumed).sum();
            long quantityNeededForRecipe = itemIdToQuantity.get(itemId);
            int maxRecipesThatCanBeMade = (int) (totalQuantity / quantityNeededForRecipe);
            return Map.entry(itemId, maxRecipesThatCanBeMade);
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}

enum RelationshipType {
    @SerializedName("bidirectional")
    BIDIRECTIONAL,
    @SerializedName("buyParent")
    BUY_PARENT,
    @SerializedName("sellParent")
    SELL_PARENT
}