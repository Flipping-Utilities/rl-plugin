package com.flippingutilities.controller;

import com.flippingutilities.db.TradePersister;
import net.runelite.api.Client;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class AccountLoginRetryTest {
    @Test public void unavailableHashWaitsBeforeBindingHistory() throws Exception {
        FlippingPlugin plugin = pluginWithHash(-1L);
        assertFalse(plugin.handleLogin("Bob"));
        assertNull(plugin.getCurrentlyLoggedInAccount());
    }

    @Test public void busyStorageRetriesAndPassesUnsignedCharacterIdentity() throws Exception {
        FlippingPlugin plugin = pluginWithHash(-2L);
        AtomicReference<String> observedId = new AtomicReference<>();
        setField(plugin, "dataHandler", new DataHandler(plugin) {
            @Override public String bindLoggedInAccount(String accountId, String name) throws IOException {
                observedId.set(accountId);
                throw new TradePersister.StorageBusyException();
            }
        });

        assertFalse(plugin.handleLogin("Bob"));
        assertEquals("18446744073709551614", observedId.get());
        assertNull(plugin.getCurrentlyLoggedInAccount());
    }

    @Test public void permanentStorageFailureDoesNotRetryEveryTick() throws Exception {
        FlippingPlugin plugin = pluginWithHash(123L);
        setField(plugin, "dataHandler", new DataHandler(plugin) {
            @Override public String bindLoggedInAccount(String accountId, String name) throws IOException {
                throw new IOException("Conflicting history files");
            }
        });

        assertTrue(plugin.handleLogin("Bob"));
        assertNull(plugin.getCurrentlyLoggedInAccount());
    }

    private static FlippingPlugin pluginWithHash(long hash) throws Exception {
        FlippingPlugin plugin = new FlippingPlugin();
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getAccountHash")) return hash;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == boolean.class) return false;
                return null;
            });
        setField(plugin, "client", client);
        return plugin;
    }

    private static void setField(FlippingPlugin plugin, String name, Object value) throws Exception {
        Field field = FlippingPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
