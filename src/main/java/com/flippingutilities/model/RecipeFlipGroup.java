package com.flippingutilities.model;

import com.flippingutilities.utilities.Recipe;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

@Data
public class RecipeFlipGroup {
    private Recipe recipe;
    private List<RecipeFlip> recipeFlips;

    public RecipeFlipGroup(Recipe recipe) {
        this.recipe = recipe;
    }

    public boolean isInGroup(int itemId) {
        return recipe.isInRecipe(itemId);
    }

    public List<PartialOffer> getPartialOffers(int itemId) {
        return recipeFlips.stream().flatMap(rf -> rf.getPartialOffers(itemId).stream()).collect(Collectors.toList());
    }
}
