package com.flippingutilities.model;

import com.flippingutilities.controller.RecipeHandler;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.Searchable;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Contains all the recipe flips for a recipe.
 * Stores only a recipeKey (derived from inputs/outputs) instead of the full Recipe object
 * to reduce JSON file size. The full Recipe is resolved on load via RecipeHandler.
 */
@Data
@Slf4j
@NoArgsConstructor
public class RecipeFlipGroup implements Searchable {
    private String recipeKey;
    
    @SerializedName("recipe")
    @Expose(serialize = false, deserialize = true)
    private Recipe recipe;
    
    private List<RecipeFlip> recipeFlips = new ArrayList<>();

    public RecipeFlipGroup(Recipe recipe) {
        this.recipe = recipe;
        this.recipeKey = RecipeHandler.createRecipeKey(recipe);
    }
    
    public RecipeFlipGroup(String recipeKey) {
        this.recipeKey = recipeKey;
    }
    
    public RecipeFlipGroup(Recipe recipe, List<RecipeFlip> recipeFlips) {
        this.recipe = recipe;
        this.recipeKey = recipe != null ? RecipeHandler.createRecipeKey(recipe) : null;
        this.recipeFlips = recipeFlips;
    }
    
    public void hydrateRecipe(RecipeHandler recipeHandler) {
        if (recipeKey != null && recipe == null) {
            recipe = recipeHandler.findRecipeByKey(recipeKey)
                .orElseGet(() -> RecipeHandler.createRecipeFromKey(recipeKey));
        } else if (recipe != null && recipeKey == null) {
            recipeKey = RecipeHandler.createRecipeKey(recipe);
        }
    }
    
    /**
     * Gets the recipe, creating a synthetic one from recipeKey if needed.
     */
    public Recipe getRecipe() {
        if (recipe == null && recipeKey != null) {
            recipe = RecipeHandler.createRecipeFromKey(recipeKey);
        }
        return recipe;
    }
    
    public void setRecipe(Recipe recipe) {
        this.recipe = recipe;
        if (recipe != null) {
            this.recipeKey = RecipeHandler.createRecipeKey(recipe);
        }
    }

    public RecipeFlipGroup clone() {
        RecipeFlipGroup cloned = new RecipeFlipGroup(recipeKey);
        cloned.recipe = recipe;
        cloned.recipeFlips = recipeFlips.stream().map(RecipeFlip::clone).collect(Collectors.toList());
        return cloned;
    }

    public RecipeFlipGroup merge(RecipeFlipGroup otherRecipeFlipGroup) {
        List<RecipeFlip> allRecipeFlips = recipeFlips;
        allRecipeFlips.addAll(otherRecipeFlipGroup.getRecipeFlips());
        allRecipeFlips.sort(Comparator.comparing(RecipeFlip::getTimeOfCreation));
        RecipeFlipGroup merged = new RecipeFlipGroup(recipeKey);
        merged.recipe = recipe;
        merged.recipeFlips = allRecipeFlips;
        return merged;
    }

    public Instant getLatestActivityTime() {
        if (recipeFlips.isEmpty()) {
            return Instant.EPOCH;
        }
        return recipeFlips.get(recipeFlips.size()-1).timeOfCreation;
    }

    public boolean isInGroup(int itemId) {
        Recipe r = getRecipe();
        if (r == null) {
            // Check if itemId is in inputs/outputs of any RecipeFlip
            return recipeFlips.stream()
                .anyMatch(rf -> rf.getInputs().containsKey(itemId) || rf.getOutputs().containsKey(itemId));
        }
        return r.isInRecipe(itemId);
    }

    /**
     * Gets a map of offer id to partial offer. Since an offer can be referenced by multiple partial offers (each
     * consuming part of the offer), we need to sum up the amount consumed by each of the partial offers referencing
     * that one offer to get the final partial offer corresponding to that offer id.
     */
    public Map<String, PartialOffer> getOfferIdToPartialOffer(int itemId) {
        Map<String, PartialOffer>  offerIdToPartialOffer = new HashMap<>();
        for (RecipeFlip recipeFlip : recipeFlips) {
            List<PartialOffer> partialOffers = recipeFlip.getPartialOffers(itemId);
            partialOffers.forEach(po -> {
                String offerId = po.getOfferUuid();
                if (offerIdToPartialOffer.containsKey(offerId)) {
                    PartialOffer otherPartialOffer = offerIdToPartialOffer.get(offerId);
                    PartialOffer clonedPartialOffer = po.clone();
                    clonedPartialOffer.amountConsumed += otherPartialOffer.amountConsumed;
                    offerIdToPartialOffer.put(offerId, clonedPartialOffer);
                }
                else {
                    offerIdToPartialOffer.put(offerId, po);
                }
            });
        }
        return offerIdToPartialOffer;
    }

    public List<PartialOffer> getPartialOffers() {
        return recipeFlips.stream().flatMap(rf -> rf.getPartialOffers().stream()).collect(Collectors.toList());
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
        recipeFlips.removeIf(rf -> rf.getPartialOffers().stream().anyMatch(po -> offerIds.contains(po.getOfferUuid())));
    }

    @Override
    public boolean isInInterval(Instant intervalStart) {
        return recipeFlips.stream().anyMatch(recipeFlip -> recipeFlip.getTimeOfCreation().isAfter(intervalStart));
    }

    @Override
    public String getNameForSearch() {
        Recipe r = getRecipe();
        if (r == null) {
            return recipeKey != null ? "Recipe:" + recipeKey.substring(0, Math.min(20, recipeKey.length())) : "Unknown Recipe";
        }
        return r.getName();
    }
    
    /**
     * Cleans up all RecipeFlips by removing PartialOffers with amountConsumed == 0.
     */
    public void cleanup() {
        recipeFlips.forEach(RecipeFlip::cleanup);
    }
}
