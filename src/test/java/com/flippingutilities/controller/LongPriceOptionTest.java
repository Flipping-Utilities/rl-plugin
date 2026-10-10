package com.flippingutilities.controller;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.Option;
import com.flippingutilities.utilities.InvalidOptionException;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class LongPriceOptionTest {
    @Test
    public void priceHotkeysKeepSingleCoinPrecisionAboveIntRange() throws Exception {
        assertEquals(3_000_000_002L, calculate(3_000_000_001L, "+1", false));
        assertEquals(3_000_000_000L, calculate(3_000_000_001L, "-1", false));
        assertEquals(3_150_000_001L, calculate(3_000_000_001L, "*1.05", false));
    }

    @Test(expected = InvalidOptionException.class)
    public void quantityHotkeysRejectAmountsAboveTheStackLimit() throws Exception {
        calculate(3_000_000_001L, "+0", true);
    }

    @Test(expected = InvalidOptionException.class)
    public void priceModifierOverflowIsReportedInsteadOfClamped() throws Exception {
        calculate(Long.MAX_VALUE, "+1", false);
    }

    @Test
    public void cashStackQuantityRoundsDownUsingTheSetupPrice() throws Exception {
        assertEquals(3L, cashStackQuantity(1_000, 300L));
    }

    @Test
    public void cashStackQuantitySupportsSetupPricesAboveIntRange() throws Exception {
        assertEquals(0L, cashStackQuantity(Integer.MAX_VALUE, 3_000_000_001L));
    }

    @Test(expected = InvalidOptionException.class)
    public void cashStackQuantityRejectsAnUnavailableSetupPrice() throws Exception {
        cashStackQuantity(1_000, 0L);
    }

    private long cashStackQuantity(int cash, long price) throws Exception {
        ItemContainer inventory = (ItemContainer) Proxy.newProxyInstance(
            ItemContainer.class.getClassLoader(), new Class<?>[]{ItemContainer.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getItems")) {
                    return new Item[]{new Item(ItemID.COINS, cash)};
                }
                throw new UnsupportedOperationException(method.getName());
            });
        Client client = (Client) Proxy.newProxyInstance(
            Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getItemContainer":
                        assertEquals(InventoryID.INV, args[0]);
                        return inventory;
                    case "getVarpLongValue":
                        assertEquals(5753, args[0]);
                        return price;
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
        FlippingPlugin plugin = new FlippingPlugin() {
            @Override
            public Client getClient() {
                return client;
            }
        };
        return new OptionHandler(plugin).calculateOptionValue(
            new Option("", Option.CASHSTACK, "+0", true), Optional.empty(), 4151);
    }

    private long calculate(long price, String modifier, boolean quantity) throws Exception {
        FlippingItem item = new FlippingItem(4151, "Whip", 70, "Account");
        OfferEvent lastSale = new OfferEvent();
        lastSale.setPrice(price);
        item.setLatestSell(Optional.of(lastSale));
        return new OptionHandler(null).calculateOptionValue(
            new Option("", Option.LAST_SELL, modifier, quantity), Optional.of(item), item.getItemId());
    }
}
