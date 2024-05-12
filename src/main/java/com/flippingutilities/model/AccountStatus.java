package com.flippingutilities.model;

import com.flippingutilities.utilities.Constants;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemID;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;

import java.util.List;
import java.util.Map;

@Slf4j
@Getter
public class AccountStatus {
    private OfferList offers = new OfferList();
    private Inventory inventory = new Inventory();
    @Setter private boolean sellOnlyMode = false;
    @Setter private boolean isMember = false;
    @Setter private int skipSuggestion = -1;
    @Setter private String displayName;

    public AccountStatus() {
        offers = new OfferList();
        inventory = new Inventory();
    }

    public Transaction updateOffers(GrandExchangeOfferChanged event) {
        Transaction t = offers.update(event);
        log.info("updateOffers. new AccountStatus below");
        log.info(toString());
        return t;
    }

    public void setOffers(GrandExchangeOffer[] runeliteOffers) {
        offers = OfferList.fromRunelite(runeliteOffers);
        log.info("setOffers. new AccountStatus below");
        log.info(toString());
    }

    public void handleInventoryChanged(ItemContainerChanged event, Client client) {
        if (event.getContainerId() == InventoryID.INVENTORY.getId()) {
            inventory = Inventory.fromRunelite(event.getItemContainer(), client);
            log.info("handleInventoryChanged. new AccountStatus below");
            log.info(toString());
        }
    }

    public void moveAllCollectablesToInventory() {
        Map<Integer, Long> uncollectedItemAmounts = offers.getUncollectedItemAmounts();
        List<RSItem> uncollectedItems = Inventory.fromItemAmounts(uncollectedItemAmounts);
        inventory.addAll(uncollectedItems);
        removeCollectables();
    }

    public void removeCollectables() {
        offers.removeCollectables();
        log.info("removeCollectables. new AccountStatus below");
        log.info(toString());
    }

    public void moveCollectedItemToInventory(int slot, int itemId) {
        RSItem collectedItem = offers.get(slot).removeCollectedItem(itemId);
        inventory.add(collectedItem);
        log.info("moveCollectedItemToInventory. new AccountStatus below");
        log.info(toString());
    }

    public void removeCollectedItem(int slot, int itemId) {
        offers.get(slot).removeCollectedItem(itemId);
        log.info("removeCollectedItem. new AccountStatus below");
        log.info(toString());
    }

    public void resetSkipSuggestion() {
        skipSuggestion = -1;
    }

    public boolean isSuggestionSkipped() {
        return skipSuggestion != -1;
    }

    public boolean isCollectNeeded(Suggestion suggestion) {
        return offers.isEmptySlotNeeded(suggestion)
            || !inventory.hasSufficientGp(suggestion)
            || !inventory.hasSufficientItems(suggestion)
            || offers.missingUncollectedItems();
    }

    public JsonObject toJson(Gson gson) {
        JsonObject statusJson = new JsonObject();
        statusJson.addProperty("display_name", displayName);
        statusJson.addProperty("sell_only", sellOnlyMode);
        statusJson.addProperty("is_member", isMember);
        statusJson.addProperty("skip_suggestion", skipSuggestion);
        JsonArray offersJsonArray = offers.toJson(gson);
        JsonArray itemsJsonArray = getItemsJson();
        statusJson.add("offers", offersJsonArray);
        statusJson.add("items", itemsJsonArray);
        return statusJson;
    }

    JsonArray getItemsJson() {
        Map<Integer, Long> itemsAmount = getItemAmounts();
        JsonArray itemsJsonArray = new JsonArray();
        for(Map.Entry<Integer, Long> entry : itemsAmount.entrySet()) {
            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("item_id", entry.getKey());
            itemJson.addProperty("amount", entry.getValue());
            itemsJsonArray.add(itemJson);
        }
        return itemsJsonArray;
    }

    Map<Integer, Long> getItemAmounts() {
        Map<Integer, Long> itemsAmount = inventory.getItemAmounts();
        Map<Integer, Long> uncollectedItemAmounts = offers.getUncollectedItemAmounts();
        uncollectedItemAmounts.forEach((key, value) -> itemsAmount.merge(key, value, Long::sum));
        itemsAmount.entrySet().removeIf(entry -> entry.getValue() == 0);
        return itemsAmount;
    }

    public boolean moreGpNeeded() {
        return offers.emptySlotExists() && getTotalGp() < Constants.MIN_GP_NEEDED_TO_FLIP;
    }

    public long getTotalGp() {
        return inventory.getTotalGp() + offers.getTotalGpToCollect();
    }

    public String toString() {
        return "AccountStatus{" +
            "offers=" + offers +
            ", inventory=" + inventory +
            ", sellOnlyMode=" + sellOnlyMode +
            ", isMember=" + isMember +
            ", skipSuggestion=" + skipSuggestion +
            ", displayName='" + displayName + '\'' +
            '}';
    }
}
