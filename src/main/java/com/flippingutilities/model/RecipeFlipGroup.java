package com.flippingutilities.model;

import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.Searchable;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class RecipeFlipGroup implements Searchable {
    private Recipe recipe;
    private List<RecipeFlip> recipeFlips = new ArrayList<>();

    public RecipeFlipGroup(Recipe recipe) {
        this.recipe = recipe;
    }

    public boolean isInGroup(int itemId) {
        return recipe.isInRecipe(itemId);
    }

    public List<PartialOffer> getPartialOffers(int itemId) {
        return recipeFlips.stream().flatMap(rf -> rf.getPartialOffers(itemId).stream()).collect(Collectors.toList());
    }
    public void addRecipeFlip(RecipeFlip recipeFlip) {
        recipeFlips.add(recipeFlip);
    }

    public boolean hasRecipeFlipsInInterval(Instant startOfInterval) {
        return recipeFlips.stream().anyMatch(rf -> rf.getTimeOfCreation().isAfter(startOfInterval));
    }

    @Override
    public boolean isInInterval(Instant intervalStart) {
        return recipeFlips.stream().anyMatch(recipeFlip -> recipeFlip.getTimeOfCreation().isAfter(intervalStart));
    }

    @Override
    public String getNameForSearch() {
        return recipe.getName();
    }
}
