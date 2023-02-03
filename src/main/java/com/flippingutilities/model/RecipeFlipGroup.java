package com.flippingutilities.model;

import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.Searchable;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Contains all the recipe flips for a recipe
 */
@Data
@AllArgsConstructor
public class RecipeFlipGroup implements Searchable {
    private Recipe recipe;
    private List<RecipeFlip> recipeFlips = new ArrayList<>();

    public RecipeFlipGroup(Recipe recipe) {
        this.recipe = recipe;
    }

    public RecipeFlipGroup clone() {
        return new RecipeFlipGroup(recipe, recipeFlips.stream().map(RecipeFlip::clone).collect(Collectors.toList()));
    }

    public RecipeFlipGroup merge(RecipeFlipGroup otherRecipeFlipGroup) {
        List<RecipeFlip> allRecipeFlips = recipeFlips;
        allRecipeFlips.addAll(otherRecipeFlipGroup.getRecipeFlips());
        allRecipeFlips.sort(Comparator.comparing(RecipeFlip::getTimeOfCreation));
        return new RecipeFlipGroup(recipe, allRecipeFlips);
    }

    public Instant getLatestActivityTime() {
        if (recipeFlips.isEmpty()) {
            return Instant.EPOCH;
        }
        return recipeFlips.get(recipeFlips.size()-1).timeOfCreation;
    }

    public boolean isInGroup(int itemId) {
        return recipe.isInRecipe(itemId);
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
                String offerId = po.offer.getUuid();
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
