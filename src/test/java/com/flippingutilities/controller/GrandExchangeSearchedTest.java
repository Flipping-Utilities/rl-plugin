package com.flippingutilities.controller;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import net.runelite.api.Client;
import net.runelite.api.events.GrandExchangeSearched;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class GrandExchangeSearchedTest {
    @Test
    public void untrackedAccountsKeepTheRegularSearch() throws Exception {
        FlippingPlugin plugin = plugin("1", new AtomicReference<>());
        // ironmen and seasonal worlds are not tracked, so no account is logged in
        set(plugin, "currentlyLoggedInAccount", null);
        GrandExchangeSearched event = new GrandExchangeSearched();
        plugin.onGrandExchangeSearched(event);
        assertFalse(event.isConsumed());
    }

    @Test
    public void accountWithoutDataKeepsTheRegularSearch() throws Exception {
        FlippingPlugin plugin = plugin("1", new AtomicReference<>());
        set(plugin, "currentlyLoggedInAccount", "Unknown account");
        GrandExchangeSearched event = new GrandExchangeSearched();
        plugin.onGrandExchangeSearched(event);
        assertFalse(event.isConsumed());
    }

    @Test
    public void quickSearchCodeListsTheFavorites() throws Exception {
        AtomicReference<short[]> results = new AtomicReference<>();
        FlippingPlugin plugin = plugin("1", results);
        set(plugin, "currentlyLoggedInAccount", "Main");
        GrandExchangeSearched event = new GrandExchangeSearched();
        plugin.onGrandExchangeSearched(event);
        assertTrue(event.isConsumed());
        assertArrayEquals(new short[]{4151}, results.get());
    }

    private static FlippingPlugin plugin(String input, AtomicReference<short[]> results) throws Exception {
        FlippingPlugin plugin = new FlippingPlugin();
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class[]{Client.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getVarcStrValue":
                        return input;
                    case "setGeSearchResultIds":
                        results.set((short[]) args[0]);
                        return null;
                    default:
                        return null;
                }
            });
        set(plugin, "client", client);
        DataHandler dataHandler = new DataHandler(plugin);
        AccountData main = new AccountData();
        FlippingItem whip = new FlippingItem(4151, "Abyssal whip", 70, "Main");
        whip.setFavorite(true);
        whip.setFavoriteCode("1");
        main.getTrades().add(whip);
        Map<String, AccountData> accounts = new HashMap<>();
        accounts.put("Main", main);
        set(dataHandler, "accountSpecificData", accounts);
        set(plugin, "dataHandler", dataHandler);
        return plugin;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
