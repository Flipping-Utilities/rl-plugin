package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** A fake game boundary. The real plugin owns slot state, history, accounting and persistence. */
final class SandboxExchange implements AutoCloseable {
    private final FlippingPlugin plugin;
    private final Map<Integer, FlippingItem> items;
    private final AtomicInteger tick;
    private final AtomicInteger catalogVersion;
    private final int[] rates = new int[8];
    private final Map<AccountData, FillSettings> accountFills = new IdentityHashMap<>();
    private FillSettings fills;
    private boolean closed;

    SandboxExchange(FlippingPlugin plugin, Map<Integer, FlippingItem> items, AtomicInteger tick, AtomicInteger catalogVersion) {
        GalleryFixture.requireEdt();
        this.plugin = plugin;
        this.items = items;
        this.tick = tick;
        this.catalogVersion = catalogVersion;
        // A small offline starting set also makes an empty source immediately usable.
        addItem(554, "Fire rune");
        addItem(1513, "Magic logs");
        addItem(4151, "Abyssal whip");
        selectAccount(defaultAccount());
    }

    private void addItem(int id, String name) {
        // Retain saved GE limits; newly introduced items have an unknown limit.
        if (items.putIfAbsent(id, new FlippingItem(id, name, 0, null)) == null) catalogVersion.incrementAndGet();
    }

    int catalogVersion() { return catalogVersion.get(); }
    net.runelite.client.util.AsyncBufferedImage image(int itemId) { return plugin.getItemManager().getImage(itemId); }

    List<Item> items() {
        List<Item> result = new ArrayList<>();
        for (FlippingItem item : items.values()) result.add(new Item(item.getItemId(), item.getItemName()));
        result.sort(Comparator.comparing(item -> item.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    List<String> accounts() {
        List<String> result = new ArrayList<>(plugin.getDataHandler().getCurrentAccounts());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    String account() { return plugin.getCurrentlyLoggedInAccount(); }

    private String defaultAccount() {
        if (accounts().isEmpty()) {
            plugin.getDataHandler().addAccount("Sandbox player");
            plugin.getMasterPanel().getAccountSelector().addItem("Sandbox player");
        }
        return accounts().get(0);
    }

    void selectAccount(String account) {
        checkOpen();
        if (!accounts().contains(account)) throw new IllegalArgumentException("Select an existing account");
        Arrays.fill(rates, 0);
        plugin.setCurrentlyLoggedInAccount(account);
        AccountData data = plugin.getDataHandler().viewAccountData(account);
        boolean restored = !accountFills.containsKey(data);
        fills = accountFills.computeIfAbsent(data, ignored -> new FillSettings());
        plugin.getMasterPanel().getAccountSelector().setSelectedItem(account);
        for (int slot = 0; slot < 8; slot++) {
            OfferEvent offer = offer(slot);
            if (restored) {
                fills.prices[slot] = offer == null ? 0 : Math.max(offer.getListedPrice(), offer.getPreTaxPrice());
                fills.spent[slot] = offer == null ? 0 : (long) offer.getPreTaxPrice() * offer.getCurrentQuantityInTrade();
            }
            if (offer != null) tick.set(Math.max(tick.get(), offer.getTickArrivedAt()));
            // Clear the previous account's visuals without changing either account's saved offers.
            OfferEvent empty = new OfferEvent();
            empty.setSlot(slot);
            empty.setTime(Instant.now());
            empty.setState(GrandExchangeOfferState.EMPTY);
            plugin.getSlotsPanel().update(empty);
            if (offer != null) plugin.getSlotsPanel().update(offer);
        }
    }

    OfferEvent offer(int slot) {
        checkSlot(slot);
        AccountData account = plugin.getDataHandler().viewAccountData(account());
        OfferEvent offer = account == null ? null : account.getLastOffers().get(slot);
        return offer == null || offer.isCausedByEmptySlot() ? null : offer.clone();
    }

    int rate(int slot) { checkSlot(slot); return rates[slot]; }
    int fillPrice(int slot) { checkSlot(slot); return fills.prices[slot]; }

    void place(int slot, boolean buy, int itemId, int quantity, int unitPrice) {
        checkOpen();
        checkAccount();
        if (offer(slot) != null) throw new IllegalStateException("Collect this slot before placing another offer");
        if (itemId <= 0 || quantity <= 0 || unitPrice <= 0) {
            throw new IllegalArgumentException("Item ID, quantity and price must be positive whole numbers");
        }
        checkValue((long) quantity * unitPrice);
        addItem(itemId, "Item " + itemId);
        fills.prices[slot] = unitPrice;
        fills.spent[slot] = 0;
        rates[slot] = 0;
        emit(slot, itemId, quantity, 0, unitPrice, 0,
            buy ? GrandExchangeOfferState.BUYING : GrandExchangeOfferState.SELLING);
    }

    void setFillPrice(int slot, int price) {
        OfferEvent offer = active(slot);
        if (price <= 0) throw new IllegalArgumentException("Fill price must be a positive whole number");
        checkValue(fills.spent[slot] + (long) remaining(offer) * price);
        fills.prices[slot] = price;
    }

    void setRate(int slot, int itemsPerSecond) {
        checkOpen();
        checkSlot(slot);
        if (itemsPerSecond < 0) throw new IllegalArgumentException("Fill rate cannot be negative");
        if (itemsPerSecond > 0) {
            active(slot);
            requirePrice(slot);
        }
        rates[slot] = itemsPerSecond;
    }

    void fill(int slot, int quantity) {
        OfferEvent offer = active(slot);
        requirePrice(slot);
        if (quantity <= 0) throw new IllegalArgumentException("Fill quantity must be positive");
        int chunk = Math.min(quantity, remaining(offer));
        int filled = offer.getCurrentQuantityInTrade() + chunk;
        long totalSpent = fills.spent[slot] + (long) chunk * fills.prices[slot];
        checkValue(totalSpent);
        GrandExchangeOfferState state = filled == offer.getTotalQuantityInTrade()
            ? (offer.isBuy() ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD) : offer.getState();
        emit(slot, offer.getItemId(), offer.getTotalQuantityInTrade(), filled,
            offer.getListedPrice() > 0 ? offer.getListedPrice() : fills.prices[slot], totalSpent, state);
        fills.spent[slot] = totalSpent;
        if (filled == offer.getTotalQuantityInTrade()) rates[slot] = 0;
    }

    void cancel(int slot) {
        OfferEvent offer = active(slot);
        emit(slot, offer.getItemId(), offer.getTotalQuantityInTrade(), offer.getCurrentQuantityInTrade(),
            offer.getListedPrice(), fills.spent[slot], offer.isBuy()
                ? GrandExchangeOfferState.CANCELLED_BUY : GrandExchangeOfferState.CANCELLED_SELL);
        rates[slot] = 0;
    }

    void collect(int slot) {
        checkOpen();
        checkAccount();
        OfferEvent offer = offer(slot);
        if (offer == null || !offer.isComplete()) throw new IllegalStateException("Finish or cancel the offer before collecting");
        emit(slot, 0, 0, 0, 0, 0, GrandExchangeOfferState.EMPTY);
        rates[slot] = fills.prices[slot] = 0;
        fills.spent[slot] = 0;
    }

    /** One deterministic timer step; tests can advance fills without sleeping. */
    void advanceSecond() {
        checkOpen();
        if (!accounts().contains(account())) selectAccount(defaultAccount());
        tick.addAndGet(2);
        for (int slot = 0; slot < 8; slot++) {
            if (rates[slot] > 0) fill(slot, rates[slot]);
            String timer = plugin.getDataHandler().viewAccountData(account()).getSlotTimers().get(slot).createFormattedTimeString();
            if (timer != null) plugin.getSlotsPanel().updateTimerDisplays(slot, timer);
        }
    }

    private OfferEvent active(int slot) {
        checkOpen();
        checkAccount();
        OfferEvent offer = offer(slot);
        if (offer == null || offer.isComplete()) throw new IllegalStateException("Select an active offer");
        return offer;
    }

    private void requirePrice(int slot) {
        if (fills.prices[slot] <= 0) throw new IllegalStateException("Set a fill price before continuing this saved offer");
    }

    private static int remaining(OfferEvent offer) {
        return Math.max(0, offer.getTotalQuantityInTrade() - offer.getCurrentQuantityInTrade());
    }

    private static void checkValue(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Simulated offer value must fit the plugin's 2,147,483,647 gp limit");
        }
    }

    private void checkAccount() {
        if (!accounts().contains(account())) throw new IllegalStateException("Select an existing account");
    }

    private void checkOpen() {
        GalleryFixture.requireEdt();
        if (closed) throw new IllegalStateException("The exchange is closed");
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= 8) throw new IllegalArgumentException("Select a slot from 1 to 8");
    }

    private void emit(int slot, int item, int quantity, int filled, int price, long totalSpent,
                      GrandExchangeOfferState state) {
        tick.incrementAndGet();
        GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
        event.setSlot(slot);
        event.setOffer(clientOffer(item, quantity, filled, price, totalSpent, state));
        plugin.onGrandExchangeOfferChanged(event);
    }

    GrandExchangeOffer[] clientOffers() {
        GrandExchangeOffer[] result = new GrandExchangeOffer[8];
        for (int slot = 0; slot < 8; slot++) {
            OfferEvent offer = offer(slot);
            result[slot] = offer == null ? clientOffer(0, 0, 0, 0, 0, GrandExchangeOfferState.EMPTY)
                : clientOffer(offer.getItemId(), offer.getTotalQuantityInTrade(), offer.getCurrentQuantityInTrade(),
                    offer.getListedPrice(), fills.spent[slot], offer.getState());
        }
        return result;
    }

    private static GrandExchangeOffer clientOffer(int item, int quantity, int filled, int price, long spent,
                                                   GrandExchangeOfferState state) {
        return SandboxGameApi.offer(item, quantity, filled, price, spent, state);
    }

    @Override public void close() {
        GalleryFixture.requireEdt();
        Arrays.fill(rates, 0);
        closed = true;
    }

    private static final class FillSettings {
        final int[] prices = new int[8];
        final long[] spent = new long[8];
    }

    static final class Item {
        final int id;
        final String name;
        Item(int id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name + " (" + id + ")"; }
    }
}
