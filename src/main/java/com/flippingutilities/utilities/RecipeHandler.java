package com.flippingutilities.utilities;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
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
    private final Optional<Map<Integer, List<Recipe>>> parentIdToRecipes;
    private Set<Integer> itemIdsInRecipes;
    private OkHttpClient httpClient;

    public RecipeHandler(Gson gson, OkHttpClient httpClient) {
        this.gson = gson;
        this.httpClient = httpClient;
        this.parentIdToRecipes = getParentIdToRecipes(loadRecipes());
        this.itemIdsInRecipes = getAllItemIdsInRecipes(parentIdToRecipes);
        if (parentIdToRecipes.isPresent()) {
            log.info("Successfully loaded recipes");
        }
    }

    /**
     * Gets all item ids in the combinations, both parents and children.
     */
    private Set<Integer> getAllItemIdsInRecipes(Optional<Map<Integer, List<Recipe>>> combinationMetadata) {
        if (combinationMetadata.isEmpty()) {
            return new HashSet<>();
        }
        Map<Integer, List<Recipe>> parentIdToCombinationMetadatas = combinationMetadata.get();
        return parentIdToCombinationMetadatas.values().stream().
                flatMap(cmList -> cmList.stream().flatMap(cm -> cm.getIds().stream())).
                collect(Collectors.toSet());
    }

    public boolean isInRecipe(int itemId) {
        return this.itemIdsInRecipes.contains(itemId);
    }

    public boolean isRecipeParent(int itemId) {
        if (parentIdToRecipes.isPresent()) {
            return this.parentIdToRecipes.get().containsKey(itemId);
        }
        return false;
    }

    public Map<Integer, Optional<FlippingItem>> getRecipeChildItems(int parentId, boolean isBuy, List<FlippingItem> items) {
        Optional<Recipe> recipe = getApplicableRecipe(parentId, isBuy);
        if (recipe.isEmpty()) {
            return new HashMap<>();
        }
        Set<Integer> childrenIds = recipe.get().children.stream().map(recipeItem -> recipeItem.id).collect(Collectors.toSet());

        Map<Integer, Optional<FlippingItem>> itemIdToChildren = new HashMap<>();
        for (FlippingItem item : items) {
            if (childrenIds.contains(item.getItemId())) {
                itemIdToChildren.put(item.getItemId(), Optional.of(item));
            }
        }

        childrenIds.forEach(id -> {
            if (!itemIdToChildren.containsKey(id)) {
                itemIdToChildren.put(id, Optional.empty());
            }
        });

        return itemIdToChildren;
    }

    public Optional<Recipe> getApplicableRecipe(int parentId, boolean isBuy) {
        if (!isRecipeParent(parentId)) {
            return Optional.empty();
        }
        List<Recipe> combinationComponentsList = parentIdToRecipes.get().get(parentId);
        RelationshipType relationshipType = isBuy? RelationshipType.BUY_PARENT: RelationshipType.SELL_PARENT;
        return combinationComponentsList.stream().filter(cm ->
                cm.relationshipType == RelationshipType.BIDIRECTIONAL || cm.relationshipType == relationshipType).findFirst();
    }

    /**
     * Converts the flat CombinationMetadata list into a map from parent id to a list of CombinationMetadata.
     * This is because a parent can have multiple CombinationMetadata associated with it, each defining a
     * relationship with different child items.
     */
    private Optional<Map<Integer, List<Recipe>>> getParentIdToRecipes
            (Optional<List<Recipe>> combinationMetadataOptional) {
        if (combinationMetadataOptional.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, List<Recipe>> parentToCombinationMetadata= new HashMap<>();
        List<Recipe> combinationMetadata = combinationMetadataOptional.get();
        combinationMetadata.forEach(cm -> {
            if (parentToCombinationMetadata.containsKey(cm.parent.id)) {
                parentToCombinationMetadata.get(cm.parent.id).add(cm);
            }
            else {
                parentToCombinationMetadata.put(cm.parent.id, new ArrayList<>(List.of(cm)));
            }
        });

        return Optional.of(parentToCombinationMetadata);
    }

    private Optional<List<Recipe>> loadRecipes() {
        Request request = new Request.Builder()
                .url("https://raw.githubusercontent.com/Flipping-Utilities/rl-plugin/master/data/recipes.json")
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