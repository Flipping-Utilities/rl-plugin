package com.flippingutilities.model;

import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.Searchable;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    public Instant getLatestFlipTime() {
        if (recipeFlips.isEmpty()) {
            return Instant.EPOCH;
        }
        return recipeFlips.get(recipeFlips.size()-1).timeOfCreation;
    }

    public List<RecipeFlip> getFlipsInInterval(Instant startOfInterval) {
        return recipeFlips.stream()
            .filter(recipeFlip -> recipeFlip.getTimeOfCreation().isAfter(startOfInterval))
            .collect(Collectors.toList());
    }

    public void deleteFlips(Instant startOfInterval) {
        recipeFlips.removeIf(rf -> rf.getTimeOfCreation().isAfter(startOfInterval));
    }

    public void deleteFlip(RecipeFlip recipeFlip) {
        recipeFlips.removeIf(rf -> rf.equals(recipeFlip));
    }

    public void deleteFlipsWithDeletedOffers(List<OfferEvent> offers) {
        Set<String> offerIds = offers.stream().map(OfferEvent::getUuid).collect(Collectors.toSet());
        recipeFlips.removeIf(rf -> rf.getPartialOffers().stream().anyMatch(po -> offerIds.contains(po.offer.getUuid())));
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
