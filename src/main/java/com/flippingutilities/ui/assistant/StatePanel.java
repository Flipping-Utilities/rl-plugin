package com.flippingutilities.ui.assistant;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.AccountStatus;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemID;
import net.runelite.client.ui.DynamicGridLayout;

import javax.swing.*;
import java.util.Map;
import java.util.StringJoiner;

@Slf4j
public class StatePanel extends JPanel {
    FlippingPlugin plugin;
    JLabel budgetVal = new JLabel("Unknown");
    JLabel coinsInventoryVal = new JLabel("Unknown");
    JLabel uncollectedCoinsVal = new JLabel("Unknown");
    JLabel uncollectedItemsVal = new JLabel("Unknown");
    JLabel itemsInventoryVal = new JLabel("Unknown");

    public StatePanel(FlippingPlugin plugin) {
        this.plugin = plugin;
        JLabel budgetText = new JLabel("Total Budget:");
        JLabel coinsInventoryText = new JLabel("Coins in inventory:");
        JLabel uncollectedCoinsText = new JLabel("Uncollected coins:");

        JLabel uncollectedItemsText = new JLabel("Uncollected Items:");
        JLabel itemsInventoryText = new JLabel("Items in inventory");

        add(budgetText);
        add(budgetVal);

        add(coinsInventoryText);
        add(coinsInventoryVal);

        add(uncollectedCoinsText);
        add(uncollectedCoinsVal);

        add(uncollectedItemsText);
        add(uncollectedItemsVal);

        add(itemsInventoryText);
        add(itemsInventoryVal);

        setLayout(new DynamicGridLayout(5, 2, 10, 2));
    }

    public void showState(AccountStatus state) {
        budgetVal.setText(String.valueOf(state.getTotalGp()));
        coinsInventoryVal.setText(String.valueOf(state.getInventory().getTotalGp()));
        uncollectedCoinsVal.setText(String.valueOf(state.getOffers().getTotalGpToCollect()));

        Map<Integer, Long> uncollectedItems =state.getOffers().getUncollectedTradeablesAmounts();
        uncollectedItems.entrySet().removeIf(entry -> entry.getValue() == 0);
        uncollectedItemsVal.setText(getItemMapStr(uncollectedItems));

        Map<Integer, Long> itemsInInventory = state.getInventory().getTradeableItemAmounts();
        itemsInventoryVal.setText(getItemMapStr(itemsInInventory));
    }

    String getItemMapStr(Map<Integer, Long> itemAmounts) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (Map.Entry<Integer, Long> entry : itemAmounts.entrySet()) {
            if (entry.getKey() == 0) {
                continue;
            }
            log.info("item id is {}", entry.getKey());
            String keyRepresentation = plugin.getItemManager().getItemComposition(entry.getKey()).getName();
            Long value = entry.getValue();
            joiner.add(keyRepresentation + "=" + value);
        }
        return joiner.toString();
    }
}
