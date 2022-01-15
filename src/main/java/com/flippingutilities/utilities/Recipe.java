package com.flippingutilities.utilities;

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
}

enum RelationshipType {
    @SerializedName("bidirectional")
    BIDIRECTIONAL,
    @SerializedName("buyParent")
    BUY_PARENT,
    @SerializedName("sellParent")
    SELL_PARENT
}