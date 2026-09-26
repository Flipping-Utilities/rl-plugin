package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.FlippingItem;
import com.google.common.cache.AbstractLoadingCache;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemClient;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.util.AsyncBufferedImage;

import javax.swing.SwingUtilities;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Synthetic RuneLite host objects shared by the desktop and browser sandboxes. */
final class SandboxGameApi {
    private SandboxGameApi() {}

    static Client client(BooleanSupplier loggedIn, IntSupplier tick,
                         Supplier<GrandExchangeOffer[]> offers, Map<Integer, FlippingItem> items) {
        return proxy(Client.class, (self, method, args) -> {
            switch (method.getName()) {
                case "getGameState": return loggedIn.getAsBoolean() ? GameState.LOGGED_IN : GameState.LOGIN_SCREEN;
                case "getWorldType": return EnumSet.of(WorldType.MEMBERS);
                case "getTickCount": return tick.getAsInt();
                case "getGrandExchangeOffers": return offers.get();
                case "isClientThread": return SwingUtilities.isEventDispatchThread();
                case "getItemDefinition": return itemDefinition((Integer) args[0], items);
                default: return defaultValue(method.getReturnType());
            }
        });
    }

    private static ItemComposition itemDefinition(int id, Map<Integer, FlippingItem> items) {
        FlippingItem item = items.get(id);
        String name = item == null ? "Item " + id : item.getItemName();
        return proxy(ItemComposition.class, (self, method, args) -> {
            switch (method.getName()) {
                case "getId": return id;
                case "getName": return name;
                case "getNote":
                case "getLinkedNoteId":
                case "getPlaceholderId":
                case "getPlaceholderTemplateId": return -1;
                default: return defaultValue(method.getReturnType());
            }
        });
    }

    static GrandExchangeOffer offer(int item, int quantity, int filled, int price, long spent,
                                    GrandExchangeOfferState state) {
        return proxy(GrandExchangeOffer.class, (self, method, args) -> {
            switch (method.getName()) {
                case "getItemId": return item;
                case "getTotalQuantity": return quantity;
                case "getQuantitySold": return filled;
                case "getState": return state;
                case "getPrice":
                    if (method.getReturnType() == long.class) return (long) price;
                    return price;
                case "getSpent":
                    if (method.getReturnType() == long.class) return spent;
                    return (int) Math.min(spent, Integer.MAX_VALUE);
                default: return defaultValue(method.getReturnType());
            }
        });
    }

    static ItemManager itemManager(Client client, ClientThread thread, Map<Integer, FlippingItem> items,
                                   IntFunction<AsyncBufferedImage> images) throws ReflectiveOperationException {
        // RuneLite's constructor is private, so a normal Java subclass is not possible.
        // Construct the real manager with inert scheduling and event registration, then replace
        // its data/cache boundaries. No constructor task can fetch prices or start a thread.
        Constructor<ItemManager> constructor = ItemManager.class.getDeclaredConstructor(Client.class,
            ScheduledExecutorService.class, ClientThread.class, EventBus.class, ItemClient.class, RuneLiteConfig.class);
        constructor.setAccessible(true);
        ScheduledExecutorService idle = proxy(ScheduledExecutorService.class,
            (self, method, args) -> defaultValue(method.getReturnType()));
        EventBus events = new EventBus() {
            @Override public synchronized void register(Object subscriber) {}
        };
        RuneLiteConfig config = proxy(RuneLiteConfig.class,
            (self, method, args) -> defaultValue(method.getReturnType()));
        ItemManager manager = constructor.newInstance(client, idle, thread, events, null, config);
        set(manager, "itemStats", new AbstractMap<Integer, ItemStats>() {
            @Override public ItemStats get(Object key) {
                FlippingItem item = items.get(key);
                return new ItemStats(false, 0, item == null ? 0 : item.getTotalGELimit(), null);
            }
            @Override public Set<Entry<Integer, ItemStats>> entrySet() { return Collections.emptySet(); }
        });
        Field imageId = Class.forName(ItemManager.class.getName() + "$ImageKey").getDeclaredField("itemId");
        imageId.setAccessible(true);
        set(manager, "itemImages", new AbstractLoadingCache<Object, AsyncBufferedImage>() {
            @Override public AsyncBufferedImage getIfPresent(Object key) { return null; }
            @Override public AsyncBufferedImage get(Object key) throws ExecutionException {
                try { return images.apply(imageId.getInt(key)); }
                catch (IllegalAccessException error) { throw new ExecutionException(error); }
            }
        });
        return manager;
    }

    private static void set(ItemManager manager, String name, Object value) throws ReflectiveOperationException {
        Field field = ItemManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals": return self == args[0];
                    case "hashCode": return System.identityHashCode(self);
                    case "toString": return "Sandbox " + type.getSimpleName();
                    default: throw new UnsupportedOperationException(method.toString());
                }
            }
            return handler.invoke(self, method, args);
        }));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == byte.class || type == Byte.class) return (byte) 0;
        if (type == short.class || type == Short.class) return (short) 0;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == float.class || type == Float.class) return 0F;
        if (type == double.class || type == Double.class) return 0D;
        if (type == char.class || type == Character.class) return '\0';
        if (type == List.class || type == Collection.class) return Collections.emptyList();
        if (type == Set.class) return Collections.emptySet();
        if (type == Map.class) return Collections.emptyMap();
        return null;
    }
}
