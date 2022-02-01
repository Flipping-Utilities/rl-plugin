package com.flippingutilities.utilities;

import com.flippingutilities.model.PartialOffer;
import com.google.gson.annotations.SerializedName;
import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;

@Data
public class Recipe {
    List<RecipeItem> inputs;
    List<RecipeItem> outputs;
    String name;

    /**
     * Gets the item ids for all the items in the outputs and inputs of the recipe
     */
    public Set<Integer> getIds() {
        Set<Integer> ids = new HashSet<>();
        inputs.forEach(r -> ids.add(r.id));
        outputs.forEach(r -> ids.add(r.id));
        return ids;
    }

    public boolean isInRecipe(int itemId) {
        return getIds().contains(itemId);
    }

    /**
     * Gets a mapping of item id in the recipe to desired quantity of that item
     */
    public Map<Integer, Integer> getItemIdToQuantity() {
        Map<Integer, Integer> itemIdToQuantity = new HashMap<>();
        inputs.forEach(r -> itemIdToQuantity.put(r.id, r.quantity));
        outputs.forEach(r -> itemIdToQuantity.put(r.id, r.quantity));
        return itemIdToQuantity;
    }

    /**
     * Checks whether the given item is an input for this recipe
     */
    public boolean isInput(int itemId) {
        return inputs.stream().anyMatch(ri -> ri.id == itemId);
    }

    public Set<Integer> getInputIds() {
        return inputs.stream().map(ri -> ri.id).collect(Collectors.toSet());
    }

    public Set<Integer> getOutputIds() {
        return outputs.stream().map(ri -> ri.id).collect(Collectors.toSet());
    }
}