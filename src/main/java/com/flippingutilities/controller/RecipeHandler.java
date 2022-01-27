package com.flippingutilities.controller;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.utilities.Recipe;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This class is responsible for loading all the recipes and providing an api
 * for other components to get info on recipes.
 */
@Slf4j
public class RecipeHandler {

    private Gson gson;
    private final Optional<Map<Integer, List<Recipe>>> idToRecipes;
    private OkHttpClient httpClient;

    public RecipeHandler(Gson gson, OkHttpClient httpClient) {
        this.gson = gson;
        this.httpClient = httpClient;
        this.idToRecipes = getItemIdToRecipes(loadRecipes());
        if (idToRecipes.isPresent()) {
            log.info("Successfully loaded recipes");
        }
    }

    public Optional<RecipeFlipGroup> findRecipeFlipGroup(List<RecipeFlipGroup> recipeFlipGroups, Recipe recipe) {
        return recipeFlipGroups.stream().filter(group -> group.getRecipe().equals(recipe)).findFirst();
    }

    public Map<String, PartialOffer> getOfferIdToPartialOffer(List<RecipeFlipGroup> recipeFlipGroups, int itemId) {
        return recipeFlipGroups.stream()
            .filter(group -> group.isInGroup(itemId))
            .flatMap(group -> group.getPartialOffers(itemId).stream())
            .collect(Collectors.toMap(po -> po.offer.getUuid(), po -> po));
    }

    /**
     * @return whether the given item id is in a recipe
     */
    public boolean isInRecipe(int itemId) {
        if (idToRecipes.isEmpty()) {
            return false;
        }
        return idToRecipes.get().containsKey(itemId);
    }

    /**
     * @return The items in the recipe
     */
    public Map<Integer, Optional<FlippingItem>> getItemsInRecipe(Recipe recipe, List<FlippingItem> items) {
        Set<Integer> ids = recipe.getIds();

        Map<Integer, Optional<FlippingItem>> itemIdToItems = new HashMap<>();
        for (FlippingItem item : items) {
            if (ids.contains(item.getItemId())) {
                itemIdToItems.put(item.getItemId(), Optional.of(item));
            }
        }

        ids.forEach(id -> {
            if (!itemIdToItems.containsKey(id)) {
                itemIdToItems.put(id, Optional.empty());
            }
        });

        return itemIdToItems;
    }

    /**
     * Gets the applicable recipe given an item id and whether you are buying/selling the item.
     * For example, if you are buying a guthan warspear, the applicable recipe is the one where the
     * warspear in the inputs and the guthan set is in the outputs. However, if you were selling the
     * warspear, the applicable recipe would be the one where the guthan set was in the inputs and the warspear
     * was in the outputs.
     */
    public Optional<Recipe> getApplicableRecipe(int itemId, boolean isBuy) {
        if (!isInRecipe(itemId)) {
            return Optional.empty();
        }
        List<Recipe> recipesWithTheItem = idToRecipes.get().get(itemId);
        for (Recipe recipe : recipesWithTheItem) {
            boolean isItemInInputs = recipe.isInput(itemId);
            if (isBuy && isItemInInputs) {
                return Optional.of(recipe);
            }
            if (!isBuy && !isItemInInputs) {
                return Optional.of(recipe);
            }
        }
        return Optional.empty();
    }

    /**
     * Gets a mapping of item id to the recipes it is in.
     */
    private Optional<Map<Integer, List<Recipe>>> getItemIdToRecipes(Optional<List<Recipe>> optionalRecipes) {
        if (optionalRecipes.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, List<Recipe>> idToRecipes= new HashMap<>();
        List<Recipe> recipes = optionalRecipes.get();
        recipes.forEach(r -> {
            r.getIds().forEach(id -> {
                if (idToRecipes.containsKey(id)) {
                    idToRecipes.get(id).add(r);
                }
                else {
                    idToRecipes.put(id, new ArrayList<>(List.of(r)));
                }
            });
        });

        return Optional.of(idToRecipes);
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
    public Map<Integer, Integer> getTargetValuesForMaxRecipeCount(Recipe recipe, Map<Integer, List<PartialOffer>> itemIdToPartialOffers) {
        Map<Integer, Integer> itemIdToQuantity = recipe.getItemIdToQuantity();
        Map<Integer, Integer> itemIdToMaxRecipesThatCanBeMade = getItemIdToMaxRecipesThatCanBeMade(recipe, itemIdToPartialOffers);
        int lowestRecipeCountThatCanBeMade = itemIdToMaxRecipesThatCanBeMade.values().stream().min(Comparator.comparingInt(i -> i)).get();
        return itemIdToQuantity.entrySet().stream().
            map(e -> Map.entry(e.getKey(), e.getValue() * lowestRecipeCountThatCanBeMade)).
            collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Gets a mapping of item id to the max amount of recipes that can be contributed to for each item in the given
     * partial offers
     */
    public Map<Integer, Integer> getItemIdToMaxRecipesThatCanBeMade(Recipe recipe, Map<Integer, List<PartialOffer>> itemIdToPartialOffers) {
        Map<Integer, Integer> itemIdToQuantity = recipe.getItemIdToQuantity();
        return itemIdToPartialOffers.entrySet().stream().map(e -> {
            int itemId = e.getKey();
            long totalQuantity = e.getValue().stream().
                mapToLong(po -> po.getOffer().getCurrentQuantityInTrade() - po.amountConsumed).sum();
            long quantityNeededForRecipe = itemIdToQuantity.get(itemId);
            int maxRecipesThatCanBeMade = (int) (totalQuantity / quantityNeededForRecipe);
            return Map.entry(itemId, maxRecipesThatCanBeMade);
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Optional<List<Recipe>> loadRecipes() {
        Request request = new Request.Builder()
                .url("https://raw.githubusercontent.com/Flipping-Utilities/osrs-datasets/master/recipes.json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Recipe fetch returned unsuccessful response: " + response);
                return Optional.empty();
            }
            if (response.body() == null) {
                log.error("Recipe response body was null: " + response);
                return Optional.empty();
            }

            Type type = new TypeToken<List<Recipe>>() {}.getType();
            return Optional.of(gson.fromJson(response.body().string(), type));
        } catch (IOException e) {
            log.warn("IOException when trying to fetch recipes: {}", ExceptionUtils.getStackTrace(e));
            return Optional.empty();
        }
    }
}