package com.flippingutilities.docs;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.ApiAuthHandler;
import com.flippingutilities.controller.DataHandler;
import com.flippingutilities.controller.FlippingItemHandler;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.controller.OptionHandler;
import com.flippingutilities.controller.RecipeHandler;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.flippingutilities.utilities.WikiDataSource;
import com.flippingutilities.utilities.WikiItemMargins;
import com.flippingutilities.utilities.WikiRequest;
import com.flippingutilities.utilities.WikiRequestWrapper;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.http.api.item.ItemPrice;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link FlippingPlugin} wired with deterministic, offline trade history for two example accounts, so the
 * user guide screenshots show the real panels with realistic content. Item icons come from
 * assets.osrs.cloud (our own cache renders) and are cached under build/docs-icons.
 */
public class DocsFixture extends FlippingPlugin {
    public static final String MAIN = "Gielinor Gains";
    public static final String ALT = "Alt Merchant";

    /** id -> name, wiki insta sell (low), wiki insta buy (high), ge limit */
    static final Object[][] ITEMS = {
        {4151, "Abyssal whip", 1_410_000L, 1_478_000L, 70},
        {536, "Dragon bones", 2_330L, 2_455L, 7_500},
        {12934, "Zulrah's scales", 162L, 171L, 30_000},
        {6685, "Saradomin brew(4)", 7_560L, 7_960L, 2_000},
        {11832, "Bandos chestplate", 14_450_000L, 15_210_000L, 8},
        {2434, "Prayer potion(4)", 9_820L, 10_290L, 2_000},
        {19553, "Amulet of torture", 9_280_000L, 9_755_000L, 8},
        {561, "Nature rune", 108L, 115L, 18_000},
        {2, "Cannonball", 154L, 163L, 11_000},
        {11804, "Bandos godsword", 10_900_000L, 11_280_000L, 8},
        {11798, "Godsword blade", 61_500L, 63_900L, 8},
        {11812, "Bandos hilt", 10_400_000L, 10_790_000L, 8},
        {12924, "Toxic blowpipe (empty)", 5_150_000L, 5_402_000L, 8},
        {20997, "Twisted bow", 1_390_000_000L, 1_428_000_000L, 8},
        {139, "Prayer potion(3)", 7_350L, 7_700L, 2_000},
        {12004, "Kraken tentacle", 820_000L, 845_000L, 70},
        {12006, "Abyssal tentacle", 2_190_000L, 2_260_000L, 70},
        {12926, "Toxic blowpipe", 5_480_000L, 5_610_000L, 8},
    };

    final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    final Map<Integer, String> names = new HashMap<>();
    final Map<Integer, long[]> wiki = new HashMap<>();
    final Map<Integer, Integer> limits = new HashMap<>();
    final File iconCache;
    final OkHttpClient http = new OkHttpClient();
    final Instant now = Instant.now();
    final Map<String, AccountData> accounts = new LinkedHashMap<>();
    WikiRequest wikiRequest;
    OfferEvent latestBgsSell;
    private int slotCounter;

    public DocsFixture(File iconCache) throws Exception {
        this.iconCache = iconCache;
        for (Object[] row : ITEMS) {
            int id = (Integer) row[0];
            names.put(id, (String) row[1]);
            wiki.put(id, new long[]{(Long) row[2], (Long) row[3]});
            limits.put(id, (Integer) row[4]);
        }
        set("config", new FlippingConfig() {});
        set("executor", executor);
        set("client", mock(Client.class));
        set("clientThread", new ClientThread() {
            @Override public void invoke(Runnable r) { r.run(); }
            @Override public void invoke(BooleanSupplier r) { r.getAsBoolean(); }
            @Override public void invokeLater(Runnable r) { r.run(); }
            @Override public void invokeLater(BooleanSupplier r) { r.getAsBoolean(); }
            @Override public void invokeAtTickEnd(Runnable r) { r.run(); }
        });
        set("itemManager", itemManager());
        set("httpClient", http);
        gson = new Gson();
        set("recipeHandler", new RecipeHandler(gson, http, null));
        set("flippingItemHandler", construct(FlippingItemHandler.class, FlippingPlugin.class, this));
        set("optionHandler", new OptionHandler(this));
        set("apiAuthHandler", new ApiAuthHandler(this));
        DataHandler dataHandler = new DataHandler(this);
        set("dataHandler", dataHandler);

        AccountWideData accountWide = new AccountWideData();
        accountWide.setDefaults();
        accountWide.getLocalRecipes().add(new Recipe(
            Arrays.asList(new RecipeItem(4151, 1), new RecipeItem(12004, 1)),
            Collections.singletonList(new RecipeItem(12006, 1)), "Abyssal tentacle"));
        accountWide.getLocalRecipes().add(new Recipe(
            Arrays.asList(new RecipeItem(12924, 1), new RecipeItem(12934, 16_383)),
            Collections.singletonList(new RecipeItem(12926, 1)), "Charged blowpipe"));
        setOn(dataHandler, "accountWideData", accountWide);
        getRecipeHandler().setLocalRecipes(accountWide.getLocalRecipes());

        wikiRequest = new WikiRequest();
        Map<Integer, WikiItemMargins> data = new HashMap<>();
        long nowSec = now.getEpochSecond();
        wiki.forEach((id, p) -> {
            WikiItemMargins m = new WikiItemMargins();
            m.setLow(p[0]);
            m.setHigh(p[1]);
            m.setLowTime(nowSec - 40 - id % 90);
            m.setHighTime(nowSec - 12 - id % 50);
            data.put(id, m);
        });
        wikiRequest.setData(data);
        set("lastWikiRequestWrapper", new WikiRequestWrapper(wikiRequest, WikiDataSource.REGULAR));
        set("timeOfLastWikiRequest", now.minusSeconds(17));

        accounts.put(MAIN, new AccountData());
        accounts.put(ALT, new AccountData());
        buildHistory();
        Map<String, AccountData> map = new HashMap<>(accounts);
        setOn(dataHandler, "accountSpecificData", map);
        for (AccountData a : accounts.values()) {
            a.setSessionStartTime(now.minus(Duration.ofHours(3).plusMinutes(12)));
            a.setAccumulatedSessionTimeMillis(Duration.ofHours(26).plusMinutes(41).toMillis());
            a.prepareForUse(this);
        }
        set("startUpTime", now.minus(Duration.ofHours(3).plusMinutes(12)));
        set("currentlyLoggedInAccount", MAIN);
        java.lang.reflect.Constructor<?> gui = Class.forName("com.flippingutilities.controller.GameUiChangesHandler")
            .getDeclaredConstructor(FlippingPlugin.class, net.runelite.client.eventbus.EventBus.class);
        gui.setAccessible(true);
        set("gameUiChangesHandler", gui.newInstance(this, new net.runelite.client.eventbus.EventBus()));
    }

    /** What the plugin knows while the GE offer setup screen is open for an item. */
    void highlight(FlippingItem item) throws Exception {
        Object gui = getFrom(this, "gameUiChangesHandler");
        setOn(gui, "highlightedItem", Optional.of(item));
        setOn(gui, "highlightedItemId", item.getItemId());
    }

    // ---------------------------------------------------------------- trade history

    private void buildHistory() {
        AccountData main = accounts.get(MAIN);
        AccountData alt = accounts.get(ALT);

        // Abyssal whip: margin check, a full flip, then a second round still being sold
        FlippingItem whip = item(main, 4151);
        trade(whip, true, 1, 1_478_000, hoursAgo(30), true);
        trade(whip, false, 1, 1_410_000, hoursAgo(29.99), true);
        trade(whip, true, 70, 1_412_000, hoursAgo(28), false);
        trade(whip, false, 70, 1_471_000, hoursAgo(22), false);
        trade(whip, true, 1, 1_478_000, minutesAgo(95), true);
        trade(whip, false, 1, 1_410_000, now.minusSeconds(95 * 60 - 30), true);
        trade(whip, true, 42, 1_413_500, minutesAgo(80), false);
        trade(whip, false, 12, 1_475_000, minutesAgo(3), false);
        whip.setFavorite(true);
        whip.setFavoriteCode("1");

        FlippingItem bones = item(main, 536);
        trade(bones, true, 7_500, 2_335, hoursAgo(50), false);
        trade(bones, false, 7_500, 2_452, hoursAgo(46), false);
        trade(bones, true, 7_500, 2_338, minutesAgo(140), false);
        trade(bones, false, 5_000, 2_450, minutesAgo(35), false);

        FlippingItem scales = item(main, 12934);
        trade(scales, true, 30_000, 162, hoursAgo(20), false);
        trade(scales, false, 30_000, 171, hoursAgo(18), false);
        trade(scales, true, 25_000, 163, minutesAgo(50), false);
        scales.setFavorite(true);
        scales.setFavoriteCode("1");

        FlippingItem brews = item(main, 6685);
        trade(brews, true, 2_000, 7_565, hoursAgo(70), false);
        trade(brews, false, 2_000, 7_955, hoursAgo(66), false);
        trade(brews, true, 1_500, 7_570, minutesAgo(65), false);

        FlippingItem chest = item(main, 11832);
        trade(chest, true, 1, 15_210_000, hoursAgo(9), true);
        trade(chest, false, 1, 14_450_000, hoursAgo(8.995), true);
        trade(chest, true, 8, 14_475_000, hoursAgo(8), false);
        trade(chest, false, 8, 15_190_000, hoursAgo(3), false);

        // a flip that lost money after tax
        FlippingItem torture = item(main, 19553);
        trade(torture, true, 8, 9_615_000, hoursAgo(120), false);
        trade(torture, false, 8, 9_740_000, hoursAgo(100), false);
        trade(torture, true, 3, 9_290_000, minutesAgo(25), false);

        FlippingItem nats = item(main, 561);
        trade(nats, true, 18_000, 108, hoursAgo(200), false);
        trade(nats, false, 18_000, 115, hoursAgo(190), false);

        // Recipe flip: buy a godsword blade + bandos hilt, sell the assembled godsword
        FlippingItem blade = item(main, 11798);
        OfferEvent bladeBuy = trade(blade, true, 2, 61_800, hoursAgo(12), false);
        FlippingItem hilt = item(main, 11812);
        OfferEvent hiltBuy = trade(hilt, true, 2, 10_420_000, hoursAgo(12), false);
        FlippingItem bgs = item(main, 11804);
        OfferEvent bgsSell = trade(bgs, false, 2, 11_270_000, hoursAgo(6), false);
        Recipe recipe = new Recipe(
            Arrays.asList(new RecipeItem(11798, 1), new RecipeItem(11812, 1)),
            Collections.singletonList(new RecipeItem(11804, 1)),
            "Bandos godsword");
        Map<Integer, Map<String, PartialOffer>> parts = new HashMap<>();
        parts.put(11798, Collections.singletonMap(bladeBuy.getUuid(), new PartialOffer(bladeBuy, 2)));
        parts.put(11812, Collections.singletonMap(hiltBuy.getUuid(), new PartialOffer(hiltBuy, 2)));
        parts.put(11804, Collections.singletonMap(bgsSell.getUuid(), new PartialOffer(bgsSell, 2)));
        RecipeFlip flip = new RecipeFlip(recipe, parts, 0);
        main.getRecipeFlipGroups().add(new RecipeFlipGroup(recipe, new ArrayList<>(Collections.singletonList(flip))));

        // a second godsword, not yet recorded as a recipe flip (used for the recipe selection screenshot)
        trade(blade, true, 1, 61_900, hoursAgo(2), false);
        trade(hilt, true, 1, 10_410_000, hoursAgo(2), false);
        latestBgsSell = trade(bgs, false, 1, 11_265_000, minutesAgo(45), false);

        // Prayer potion decanting on the alt
        FlippingItem prayer3 = item(alt, 139);
        OfferEvent p3 = trade(prayer3, true, 2_000, 7_360, hoursAgo(40), false);
        FlippingItem prayer4 = item(alt, 2434);
        OfferEvent p4 = trade(prayer4, false, 1_500, 10_280, hoursAgo(37), false);
        Recipe decant = new Recipe(
            Collections.singletonList(new RecipeItem(139, 4)),
            Collections.singletonList(new RecipeItem(2434, 3)),
            "Prayer potion(3) -> Prayer potion(4)");
        Map<Integer, Map<String, PartialOffer>> decantParts = new HashMap<>();
        decantParts.put(139, Collections.singletonMap(p3.getUuid(), new PartialOffer(p3, 2_000)));
        decantParts.put(2434, Collections.singletonMap(p4.getUuid(), new PartialOffer(p4, 1_500)));
        alt.getRecipeFlipGroups().add(new RecipeFlipGroup(decant,
            new ArrayList<>(Collections.singletonList(new RecipeFlip(decant, decantParts, 0)))));

        FlippingItem cballs = item(alt, 2);
        trade(cballs, true, 11_000, 155, hoursAgo(15), false);
        trade(cballs, false, 11_000, 163, hoursAgo(12), false);
        trade(cballs, true, 11_000, 155, minutesAgo(10), false);

        FlippingItem altBones = item(alt, 536);
        trade(altBones, true, 7_500, 2_336, hoursAgo(26), false);
        trade(altBones, false, 7_500, 2_449, hoursAgo(21), false);
    }

    private FlippingItem item(AccountData account, int id) {
        String flippedBy = account == accounts.get(MAIN) ? MAIN : ALT;
        FlippingItem item = new FlippingItem(id, names.get(id), limits.get(id), flippedBy);
        account.getTrades().add(item);
        return item;
    }

    /** Adds the completed offer the plugin would record when a slot finishes (or cancels part-way). */
    OfferEvent trade(FlippingItem item, boolean buy, int qty, long price, Instant time, boolean marginCheck) {
        OfferEvent o = new OfferEvent(UUID.randomUUID().toString(), buy, item.getItemId(), qty, price, time,
            slotCounter++ % 8, buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD,
            0, marginCheck ? 1 : 10, qty, time.minusSeconds(marginCheck ? 5 : 1_800), false,
            item.getFlippedBy(), item.getItemName(), price, price * qty);
        item.updateHistory(o);
        item.updateLatestProperties(o);
        return o;
    }

    Instant hoursAgo(double h) { return now.minusSeconds((long) (h * 3600)); }
    Instant minutesAgo(long m) { return now.minusSeconds(m * 60); }

    /** A live offer for the slots tab. */
    OfferEvent liveOffer(int slot, int id, boolean buy, int done, int total, long listed) {
        GrandExchangeOfferState state = done == total
            ? (buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD)
            : (buy ? GrandExchangeOfferState.BUYING : GrandExchangeOfferState.SELLING);
        OfferEvent o = new OfferEvent(UUID.randomUUID().toString(), buy, id, done, listed, now, slot, state,
            0, 10, total, now.minusSeconds(600), false, MAIN, names.get(id), listed, listed * done);
        return o;
    }

    // ---------------------------------------------------------------- plugin overrides

    @Override public ScheduledExecutorService getExecutor() { return executor; }

    public Collection<AccountData> accountData() { return accounts.values(); }

    // ---------------------------------------------------------------- RuneLite stand-ins

    private ItemManager itemManager() {
        ItemManager im = mock(ItemManager.class);
        when(im.getImage(anyInt())).thenAnswer(a -> icon(a.getArgument(0)));
        when(im.getImage(anyInt(), anyInt(), anyBoolean())).thenAnswer(a -> icon(a.getArgument(0)));
        when(im.getItemComposition(anyInt())).thenAnswer(a -> composition(a.getArgument(0)));
        when(im.getItemStats(anyInt())).thenAnswer(a -> {
            Integer limit = limits.get((Integer) a.getArgument(0));
            return limit == null ? null : new ItemStats(false, 0, limit, null);
        });
        when(im.getItemPrice(anyInt())).thenAnswer(a -> {
            long[] p = wiki.get((Integer) a.getArgument(0));
            return p == null ? 0L : p[0];
        });
        when(im.search(anyString())).thenAnswer(a -> {
            String q = ((String) a.getArgument(0)).toLowerCase();
            List<ItemPrice> out = new ArrayList<>();
            names.forEach((id, name) -> {
                if (name.toLowerCase().contains(q)) {
                    ItemPrice ip = new ItemPrice();
                    ip.setId(id);
                    ip.setName(name);
                    ip.setPrice((int) Math.min(Integer.MAX_VALUE, wiki.get(id)[0]));
                    out.add(ip);
                }
            });
            return out;
        });
        return im;
    }

    private ItemComposition composition(int id) {
        ItemComposition c = mock(ItemComposition.class);
        when(c.getName()).thenReturn(names.getOrDefault(id, "Item " + id));
        when(c.getId()).thenReturn(id);
        when(c.isStackable()).thenReturn(id == 561 || id == 2 || id == 12934);
        when(c.isTradeable()).thenReturn(true);
        when(c.getNote()).thenReturn(-1);
        return c;
    }

    private final Map<Integer, BufferedImage> iconMemo = new HashMap<>();

    AsyncBufferedImage icon(int id) {
        BufferedImage src = iconMemo.computeIfAbsent(id, this::loadIcon);
        AsyncBufferedImage img = new AsyncBufferedImage(getClientThread(), 36, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        if (src != null) g.drawImage(src, 0, 0, null);
        g.dispose();
        img.loaded();
        return img;
    }

    BufferedImage rawIcon(int id) {
        return iconMemo.computeIfAbsent(id, this::loadIcon);
    }

    private BufferedImage loadIcon(int id) {
        try {
            iconCache.mkdirs();
            File f = new File(iconCache, id + ".png");
            if (!f.exists()) {
                Request req = new Request.Builder().url("https://assets.osrs.cloud/icons/" + id + ".png")
                    .header("User-Agent", "antony@osrs.cloud").build();
                try (Response res = http.newCall(req).execute()) {
                    if (!res.isSuccessful() || res.body() == null) return null;
                    Files.write(f.toPath(), res.body().bytes());
                }
            }
            return ImageIO.read(f);
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- reflection helpers

    void set(String field, Object value) throws Exception {
        setOn(this, field, value);
    }

    static void setOn(Object target, String field, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }

    static <T> T construct(Class<T> type, Class<?> argType, Object arg) throws Exception {
        java.lang.reflect.Constructor<T> c = type.getDeclaredConstructor(argType);
        c.setAccessible(true);
        return c.newInstance(arg);
    }

    static Object getFrom(Object target, String field) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(field);
    }
}
