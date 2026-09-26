package com.flippingutilities.controller;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.Option;
import com.flippingutilities.utilities.InvalidOptionException;
import com.flippingutilities.utilities.WikiItemMargins;
import com.flippingutilities.utilities.WikiRequest;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.game.ItemStats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public class OptionHandler {
    FlippingPlugin plugin;

    public OptionHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    public long calculateOptionValue(Option option, Optional<FlippingItem> highlightedItem, int highlightedItemId) throws InvalidOptionException {
        long val = 0;
        String propertyString = option.getProperty();
        switch (propertyString) {
            case Option.GE_LIMIT:
                val = geLimitCalculation(highlightedItem, highlightedItemId);
                break;
            case Option.REMAINING_LIMIT:
                val = remainingGeLimitCalculation(highlightedItem, highlightedItemId);
                break;
            case Option.CASHSTACK:
                val = cashStackCalculation(highlightedItem, highlightedItemId);
                break;
            case Option.INSTA_SELL:
                val = instaSellCalculation(highlightedItem);
                break;
            case Option.INSTA_BUY:
                val = instaBuyCalculation(highlightedItem);
                break;
            case Option.LAST_BUY:
                val = latestBuyCalculation(highlightedItem);
                break;
            case Option.LAST_SELL:
                val = latestSellCalculation(highlightedItem);
                break;
            case Option.WIKI_BUY:
                val = wikiPriceCalculation(highlightedItemId, true);
                break;
            case Option.WIKI_SELL:
                val = wikiPriceCalculation(highlightedItemId, false);
                break;
        }

        long finalValue = applyModifier(option.getModifier(), val);
        if (finalValue < 0) {
            throw new InvalidOptionException("resulting value was negative");
        }
        if (option.isQuantityOption() && finalValue > Integer.MAX_VALUE) {
            throw new InvalidOptionException("resulting quantity exceeds the item stack limit");
        }
        return finalValue;
    }

    private long wikiPriceCalculation(int itemId, boolean getBuyPrice) throws InvalidOptionException {
        if (plugin.getLastWikiRequestWrapper() != null) {
            WikiRequest wr = plugin.getLastWikiRequestWrapper().getWikiRequest();
            WikiItemMargins wikiItemMargins = wr.getData().get(itemId);
            long wikiPrice = getBuyPrice ? wikiItemMargins.getHigh() : wikiItemMargins.getLow();
            if (wikiPrice == 0) {
                throw new InvalidOptionException(String.format("no insta %s data for this item", getBuyPrice ? "buy" : "sell"));
            }
            return wikiPrice;
        } else {
            throw new InvalidOptionException("wiki request has not been made yet");
        }
    }

    private int remainingGeLimitCalculation(Optional<FlippingItem> item, int itemId) throws InvalidOptionException {
        ItemStats itemStats = plugin.getItemManager().getItemStats(itemId);
        int geLimit = itemStats != null ? itemStats.getGeLimit() : 0;
        int totalGeLimit = item.map(FlippingItem::getTotalGELimit).orElse(geLimit);
        if (totalGeLimit <= 0) {
            throw new InvalidOptionException("Item does not have a known limit. Cannot calculate resulting value");
        }
        return item.map(FlippingItem::getRemainingGeLimit).orElse(geLimit);
    }

    private int geLimitCalculation(Optional<FlippingItem> item, int itemId) throws InvalidOptionException {
        ItemStats itemStats = plugin.getItemManager().getItemStats(itemId);
        int geLimit = itemStats != null ? itemStats.getGeLimit() : 0;
        int totalGeLimit = item.map(FlippingItem::getTotalGELimit).orElse(geLimit);
        if (totalGeLimit <= 0) {
            throw new InvalidOptionException("Item does not have a known limit. Cannot calculate resulting value");
        }
        return item.map(FlippingItem::getTotalGELimit).orElse(geLimit);
    }

    private int cashStackCalculation(Optional<FlippingItem> item, int itemId) throws InvalidOptionException {
        if (getCashStackInInv() == 0) {
            throw new InvalidOptionException("Player has no cash in inventory");
        }
        int offerPrice = plugin.getClient().getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
        if (offerPrice <= 0) {
            throw new InvalidOptionException("Item offer price missing");
        }

        return getCashStackInInv() / offerPrice;
    }

    private long instaBuyCalculation(Optional<FlippingItem> item) throws InvalidOptionException {
        if (!item.isPresent()) {
            throw new InvalidOptionException("item was not bought or sold");
        } else {
            if (item.get().getLatestInstaBuy().isPresent()) {
                return item.get().getLatestInstaBuy().get().getPrice();
            } else {
                throw new InvalidOptionException("item does not have an insta buy price");
            }
        }
    }

    private long instaSellCalculation(Optional<FlippingItem> item) throws InvalidOptionException {
        if (!item.isPresent()) {
            throw new InvalidOptionException("item was not bought or sold");
        } else {
            if (item.get().getLatestInstaSell().isPresent()) {
                return item.get().getLatestInstaSell().get().getPreTaxPrice();
            } else {
                throw new InvalidOptionException("item does not have an insta sell price");
            }
        }
    }

    private long latestSellCalculation(Optional<FlippingItem> item) throws InvalidOptionException {
        if (!item.isPresent()) {
            throw new InvalidOptionException("item was not bought or sold");
        } else {
            if (item.get().getLatestSell().isPresent()) {
                return item.get().getLatestSell().get().getPreTaxPrice();
            } else {
                throw new InvalidOptionException("item does not have a sell");
            }
        }
    }

    private long latestBuyCalculation(Optional<FlippingItem> item) throws InvalidOptionException {
        if (!item.isPresent()) {
            throw new InvalidOptionException("item was not bought or sold");
        } else {
            if (item.get().getLatestBuy().isPresent()) {
                return item.get().getLatestBuy().get().getPrice();
            } else {
                throw new InvalidOptionException("item does not have a buy");
            }
        }
    }

    private long applyModifier(String modifier, long value) throws InvalidOptionException {
        String invalidModifier = "Modifier has to be one of +,-,*, followed by a positive number. Example: +2, -5, *9";
        if (modifier.length() < 2) {
            throw new InvalidOptionException(invalidModifier);
        }
        try {
            BigDecimal amount = new BigDecimal(modifier.substring(1));
            if (amount.signum() < 0) {
                throw new InvalidOptionException(invalidModifier);
            }
            BigDecimal result = BigDecimal.valueOf(value);
            switch (modifier.charAt(0)) {
                case '-':
                    result = result.subtract(amount);
                    break;
                case '+':
                    result = result.add(amount);
                    break;
                case '*':
                    result = result.multiply(amount);
                    break;
                default:
                    throw new InvalidOptionException(invalidModifier);
            }
            // Match Math.round without first rounding a large monetary value to a float.
            return result.add(new BigDecimal("0.5")).setScale(0, RoundingMode.FLOOR).longValueExact();
        } catch (NumberFormatException e) {
            throw new InvalidOptionException(invalidModifier);
        } catch (ArithmeticException e) {
            throw new InvalidOptionException("resulting value is too large");
        }
    }

    private int getCashStackInInv() {
        ItemContainer inventory = plugin.getClient().getItemContainer(InventoryID.INV);
        if (inventory == null) {
            return 0;
        }
        Item[] inventoryItems = inventory.getItems();
        for (Item item : inventoryItems) {
            if (item.getId() == ItemID.COINS) {
                return item.getQuantity();
            }
        }
        return 0;
    }

}
