package com.flippingutilities.model;

import com.flippingutilities.utilities.Constants;
import net.runelite.api.*;
import net.runelite.api.ItemID;

import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;


public class Inventory extends ArrayList<RSItem> {
    boolean hasSufficientGp(Suggestion suggestion) {
        return !suggestion.getType().equals("buy")
            || getTotalGp() >= (long) suggestion.getPrice() * suggestion.getQuantity();
    }

    boolean hasSufficientItems(Suggestion suggestion) {
        return !suggestion.getType().equals("sell")
            || getTotalAmount(suggestion.getItemId()) >= suggestion.getQuantity();
    }

    public long getTotalGp() {
        return getTotalAmount(ItemID.COINS_995) + Constants.PLATINUM_TOKEN_VALUE * getTotalAmount(ItemID.PLATINUM_TOKEN);
    }

    long getTotalAmount(long itemId) {
        long amount = 0;
        for (RSItem item : this) {
            if (item.getId() == itemId) {
                amount += item.getAmount();
            }
        }
        return amount;
    }


    static Inventory fromRunelite(ItemContainer inventory, Client client) {
        Inventory unnotedItems = new Inventory();
        Item[] items = inventory.getItems();
        for (Item item : items) {
            if (item.getId() == -1) {
                continue;
            }
            unnotedItems.add(RSItem.getUnnoted(item, client));
        }
        return unnotedItems;
    }

    public Map<Integer, Long> getItemAmounts() {
        return stream().collect(Collectors.groupingBy(RSItem::getId,
            Collectors.summingLong(RSItem::getAmount)));
    }

    static Inventory fromItemAmounts(Map<Integer, Long> itemAmounts) {
        Inventory inventory = new Inventory();
        for (Map.Entry<Integer, Long> entry : itemAmounts.entrySet()) {
            inventory.add(new RSItem(entry.getKey(), entry.getValue()));
        }
        return inventory;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder("Inventory{\n");
        for (RSItem item : this) {
            sb.append("  ").append(item.toString()).append(",\n");
        }
        if (!this.isEmpty()) {
            sb.setLength(sb.length() - 2); // Remove the last comma and newline if list is not empty
        }
        sb.append("\n}");
        return sb.toString();
    }

}
