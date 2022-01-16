package com.flippingutilities.utilities;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This class is responsible for loading all the various combinations data such as sets
 * and allowing other components to query it for information on combinations.
 */
@Slf4j
public class RecipeHandler {

    private Gson gson;
    private final Optional<Map<Integer, List<Recipe>>> parentIdToRecipes;
    private Set<Integer> itemIdsInRecipes;

    public RecipeHandler(Gson gson) {
        this.gson = gson;
        this.parentIdToRecipes = getParentIdToRecipes(loadRecipes("/data/recipes.json"));
        this.itemIdsInRecipes = getAllItemIdsInRecipes(parentIdToRecipes);
        if (parentIdToRecipes.isPresent()) {
            log.info("Successfully loaded combinations");
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

    public Map<Integer, Optional<FlippingItem>> getChildRecipeItems(int parentId, boolean isBuy, List<FlippingItem> items) {
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

    private Optional<List<Recipe>> loadRecipes(String path) {
        InputStream inputStream = FlippingPlugin.class.getResourceAsStream(path);

        if (inputStream == null) {
            log.warn("could not find resource json at path {}", path);
            return Optional.empty();
        }
        Reader reader = new InputStreamReader(inputStream);
        Type type = new TypeToken<List<Recipe>>() {}.getType();
        return Optional.of(gson.fromJson(reader, type));
    }
}