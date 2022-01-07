package com.flippingutilities.utilities;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.statistics.OfferPanel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.*;

/**
 * This class is responsible for loading all the various combinations data such as sets
 * and allowing other components to query it for information on combinations.
 */
@Slf4j
public class CombinationFlipFinder {

    private Gson gson;
    private final Optional<Map<String, List<Integer>>> sets;
    private Set<Integer> itemIdsInCombinations;

    public CombinationFlipFinder(Gson gson) {
        this.gson = gson;
        this.sets = this.loadClassFromResourceJson("/data/raw_sets.json", new TypeToken<Map<String, List<Integer>>>() {
        });
        this.itemIdsInCombinations = getAllItemIdsInCombinations(sets);
        if (sets.isPresent()) {
            log.info("Successfully loaded combinations - sets count: {}", this.sets.get().size());
        }
    }

    private Set<Integer> getAllItemIdsInCombinations(Optional<Map<String, List<Integer>>> sets) {
        Set<Integer> itemIdsInCombination = new HashSet<>();
        if (sets.isEmpty()) {
            return itemIdsInCombination;
        }
        sets.get().values().forEach(itemIdsInCombination::addAll);
        return itemIdsInCombination;
    }

    public boolean isInCombination(int itemId) {
        return this.itemIdsInCombinations.contains(itemId);
    }

    public boolean isCombinationSource(int itemId) {
        if (sets.isPresent()) {
            return this.sets.get().containsKey(String.valueOf(itemId));
        }
        return false;
    }

    public Map<Integer, Optional<FlippingItem>> getItemsInCombination(int itemId, List<FlippingItem> items) {
        if (!isCombinationSource(itemId)) {
            return new HashMap<>();
        }

        Map<Integer, Optional<FlippingItem>> itemsInCombination = new HashMap<>();
        Set<Integer> relatedIds = new HashSet<>(sets.get().get(String.valueOf(itemId)));
        for (FlippingItem item : items) {
            if (relatedIds.contains(item.getItemId())) {
                itemsInCombination.put(item.getItemId(), Optional.of(item));
            }
        }

        relatedIds.forEach(id -> {
            if (!itemsInCombination.containsKey(id)) {
                itemsInCombination.put(id, Optional.empty());
            }
        });

        return itemsInCombination;
    }

    private <T> Optional<T> loadClassFromResourceJson(String path, TypeToken<T> typeToken) {
        InputStream inputStream = FlippingPlugin.class.getResourceAsStream(path);

        if (inputStream == null) {
            log.warn("could not find resource json at path {}", path);
            return Optional.empty();
        }

        Reader reader = new InputStreamReader(inputStream);
        return Optional.of(gson.fromJson(reader, typeToken.getType()));
    }
}