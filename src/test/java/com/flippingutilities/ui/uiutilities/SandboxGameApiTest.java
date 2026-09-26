package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.FlippingItem;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SandboxGameApiTest {
    @Test
    public void clientReadsCurrentAccountTickAndOffersWithoutARealGameClient() throws Exception {
        AtomicBoolean loggedIn = new AtomicBoolean();
        AtomicInteger tick = new AtomicInteger(1);
        AtomicReference<GrandExchangeOffer[]> offers = new AtomicReference<>();
        Client client = SandboxGameApi.client(loggedIn::get, tick::get, offers::get, new HashMap<>());

        assertEquals(GameState.LOGIN_SCREEN, client.getGameState());
        assertNull(client.getGrandExchangeOffers());
        assertFalse(client.isClientThread());
        assertEquals(0, client.getVarbitValue(0));
        assertNull(client.getLocalPlayer());
        assertTrue(client.getWorldType().contains(WorldType.MEMBERS));
        assertEquals(client, client);
        assertNotEquals(client, new Object());

        loggedIn.set(true);
        tick.set(42);
        offers.set(new GrandExchangeOffer[]{SandboxGameApi.offer(4151, 10, 2, 100, 180,
            GrandExchangeOfferState.BUYING)});
        assertEquals(GameState.LOGGED_IN, client.getGameState());
        assertEquals(42, client.getTickCount());
        assertSame(offers.get(), client.getGrandExchangeOffers());
        SwingUtilities.invokeAndWait(() -> assertTrue(client.isClientThread()));
    }

    @Test
    public void itemManagerUsesCurrentSyntheticMetadataAndBothImageOverloads() throws Exception {
        Map<Integer, FlippingItem> items = new HashMap<>();
        items.put(4151, new FlippingItem(4151, "Saved name", 70, null));
        Client client = SandboxGameApi.client(() -> false, () -> 1, () -> null, items);
        ClientThread thread = new ClientThread();
        AsyncBufferedImage icon = new AsyncBufferedImage(thread, 36, 32, BufferedImage.TYPE_INT_ARGB);
        AtomicInteger requestedImage = new AtomicInteger();
        ItemManager manager = SandboxGameApi.itemManager(client, thread, items, id -> {
            requestedImage.set(id);
            return icon;
        });

        assertEquals("Saved name", manager.getItemComposition(4151).getName());
        assertEquals(70, manager.getItemStats(4151).getGeLimit());
        assertEquals(-1, manager.getItemComposition(4151).getNote());
        assertEquals(4151, manager.canonicalize(4151));
        assertSame(icon, manager.getImage(4151));
        assertEquals(4151, requestedImage.get());
        assertSame(icon, manager.getImage(4151, 20, true));

        // Wiki mapping arrives after the host is constructed; lookups must see replacements.
        items.put(4151, new FlippingItem(4151, "Abyssal whip", 100, null));
        assertEquals("Abyssal whip", manager.getItemComposition(4151).getName());
        assertEquals(100, manager.getItemStats(4151).getGeLimit());
        assertEquals("Item 123", manager.getItemComposition(123).getName());
        assertEquals(0, manager.getItemStats(123).getGeLimit());
        assertTrue(manager.search("unavailable prices").isEmpty());
    }

    @Test
    public void exchangeOffersKeepActualFillCostAndAdaptToRuneLiteMoneyType() throws Exception {
        GrandExchangeOffer offer = SandboxGameApi.offer(4151, 10, 2, 100, 180, GrandExchangeOfferState.BUYING);
        assertEquals(4151, offer.getItemId());
        assertEquals(10, offer.getTotalQuantity());
        assertEquals(2, offer.getQuantitySold());
        assertEquals(100, offer.getPrice());
        assertEquals(180, offer.getSpent());
        assertEquals(GrandExchangeOfferState.BUYING, offer.getState());

        long spent = (long) Integer.MAX_VALUE + 10;
        GrandExchangeOffer large = SandboxGameApi.offer(4151, 10, 10, Integer.MAX_VALUE, spent,
            GrandExchangeOfferState.BOUGHT);
        long expected = GrandExchangeOffer.class.getMethod("getSpent").getReturnType() == long.class
            ? spent : Integer.MAX_VALUE;
        assertEquals(expected, large.getSpent());
    }
}
