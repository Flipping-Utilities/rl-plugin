package com.flippingutilities.controller;

import com.flippingutilities.db.TradePersister;
import com.flippingutilities.db.JsonStorage;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.google.gson.Gson;
import net.runelite.api.GrandExchangeOfferState;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.Test;

import java.lang.reflect.Field;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AccountViewHandlerTest {
    private static final String FIRST_ACCOUNT = "First player";
    private static final String SECOND_ACCOUNT = "Second player";
    private static final int ITEM_ID = 4151;
    private static final Instant INTERVAL_START = Instant.parse("2026-01-01T00:00:00Z");

    private final Map<String, AccountData> accounts = new HashMap<>();
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private File dataDirectory;
    private ExecutorService storageExecutor;
    private final Gson gson = new Gson();
    private FlippingPlugin plugin;
    private DataHandler dataHandler;
    private OkHttpClient recipeClient;

    @Before
    public void setUp() throws Exception {
        plugin = new FlippingPlugin();
        dataDirectory = folder.newFolder();
        plugin.tradePersister = new TradePersister(gson) {
            @Override public File getAccountDirectory() { return dataDirectory; }
        };
        recipeClient = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create(MediaType.get("application/json"), "[]")).build()).build();
        setField(plugin, FlippingPlugin.class, "recipeHandler",
            new RecipeHandler(gson, recipeClient, Collections.emptyList()));
        storageExecutor = Executors.newSingleThreadExecutor();
        dataHandler = new DataHandler(plugin, storageExecutor);
        setField(plugin, FlippingPlugin.class, "dataHandler", dataHandler);
        setField(plugin, FlippingPlugin.class, "flippingItemHandler", new FlippingItemHandler(plugin));
        dataHandler.loadData();
        assertNull(dataHandler.getStorageError());
        for (String name : Arrays.asList(FIRST_ACCOUNT, SECOND_ACCOUNT)) {
            dataHandler.addAccount(name);
            AccountData account = dataHandler.getAccountData(name);
            FlippingItem item = new FlippingItem(ITEM_ID, "Abyssal whip", 70, name);
            item.setValidFlippingPanelItem(true);
            account.getTrades().add(item);
            account.setAccumulatedSessionTimeMillis(1000L);
            accounts.put(name, account);
        }
        saveAndLoad();
        select(FIRST_ACCOUNT);
    }

    @After
    public void closeResources() throws Exception {
        try {
            if (dataHandler != null) dataHandler.close().get(10, TimeUnit.SECONDS);
        } finally {
            if (storageExecutor != null) storageExecutor.shutdownNow();
            if (recipeClient != null) {
                recipeClient.dispatcher().executorService().shutdownNow();
                recipeClient.connectionPool().evictAll();
            }
        }
    }

    @Test
    public void readOnlyViewsAndSelectionDoNotScheduleAccountSaves() throws Exception {
        byte[] originalJournal = journalBytes();
        assertSame(accounts.get(FIRST_ACCOUNT).getTrades(), plugin.viewItemsForCurrentView());
        assertTrue(plugin.viewRecipeFlipGroupsForCurrentView().isEmpty());
        assertEquals(Duration.ofSeconds(1), plugin.viewAccumulatedTimeForCurrentView());
        assertFalse(plugin.isAccountWideView());
        assertTrue(plugin.isAccountInCurrentView(FIRST_ACCOUNT));
        assertFalse(plugin.isAccountInCurrentView(SECOND_ACCOUNT));

        select(FlippingPlugin.ACCOUNT_WIDE);
        assertEquals(1, plugin.viewItemsForCurrentView().size());
        assertNotSame(item(FIRST_ACCOUNT), plugin.viewItemsForCurrentView().get(0));
        assertEquals(Duration.ofSeconds(2), plugin.viewAccumulatedTimeForCurrentView());
        assertTrue(plugin.isAccountWideView());
        assertTrue(plugin.isAccountInCurrentView(FIRST_ACCOUNT));
        assertTrue(plugin.isAccountInCurrentView(SECOND_ACCOUNT));
        List<String> selectedNames = plugin.getAccountNamesForCurrentView();
        select(FIRST_ACCOUNT);
        assertEquals(accounts.keySet(), new HashSet<>(selectedNames));

        assertFalse(dataHandler.isSavePending());
        Map<String, AccountData> persisted = closeAndLoad();
        assertEquals(accounts.keySet(), persisted.keySet());
        assertArrayEquals(originalJournal, journalBytes());
    }

    @Test
    public void hidingTheSelectedAccountLeavesOtherAccountsUntouched() throws Exception {
        FlippingItem first = item(FIRST_ACCOUNT);
        plugin.setAllFlippingItemsAsHidden();

        assertFalse(first.getValidFlippingPanelItem());
        assertTrue(accounts.get(FIRST_ACCOUNT).getTrades().isEmpty());
        assertTrue(item(SECOND_ACCOUNT).getValidFlippingPanelItem());
        Map<String, AccountData> persisted = closeAndLoad();
        assertTrue(persisted.get(FIRST_ACCOUNT).getTrades().isEmpty());
        assertTrue(persisted.get(SECOND_ACCOUNT).getTrades().get(0).getValidFlippingPanelItem());
    }

    @Test
    public void hidingAMergedItemUpdatesAndSavesEachUnderlyingAccount() throws Exception {
        select(FlippingPlugin.ACCOUNT_WIDE);
        FlippingItem merged = plugin.viewItemsForCurrentView().get(0);
        plugin.setItemVisible(merged, false);

        assertFalse(merged.getValidFlippingPanelItem());
        assertFalse(item(FIRST_ACCOUNT).getValidFlippingPanelItem());
        assertFalse(item(SECOND_ACCOUNT).getValidFlippingPanelItem());
        plugin.truncateTradeList();
        assertTrue(accounts.get(FIRST_ACCOUNT).getTrades().isEmpty());
        assertTrue(accounts.get(SECOND_ACCOUNT).getTrades().isEmpty());
        Map<String, AccountData> persisted = closeAndLoad();
        assertTrue(persisted.get(FIRST_ACCOUNT).getTrades().isEmpty());
        assertTrue(persisted.get(SECOND_ACCOUNT).getTrades().isEmpty());
    }

    @Test
    public void addingAFavoriteTargetsOnlyAccountsInTheCurrentView() throws Exception {
        FlippingItem favorite = new FlippingItem(ITEM_ID, "Abyssal whip", 70, FIRST_ACCOUNT);
        plugin.addFavoritedItem(favorite);
        assertTrue(item(FIRST_ACCOUNT).isFavorite());
        assertFalse(item(SECOND_ACCOUNT).isFavorite());
        Map<String, AccountData> persisted = saveAndLoad();
        assertTrue(persisted.get(FIRST_ACCOUNT).getTrades().get(0).isFavorite());
        assertFalse(persisted.get(SECOND_ACCOUNT).getTrades().get(0).isFavorite());

        select(FlippingPlugin.ACCOUNT_WIDE);
        plugin.addFavoritedItem(favorite);
        assertTrue(item(FIRST_ACCOUNT).isFavorite());
        assertTrue(item(SECOND_ACCOUNT).isFavorite());
        persisted = closeAndLoad();
        assertTrue(persisted.get(FIRST_ACCOUNT).getTrades().get(0).isFavorite());
        assertTrue(persisted.get(SECOND_ACCOUNT).getTrades().get(0).isFavorite());
    }

    @Test
    public void mutableItemAccessPersistsTheSingleAccountButMergedItemsRemainCopies() throws Exception {
        List<FlippingItem> selectedItems = plugin.getItemsForCurrentView();
        assertSame(accounts.get(FIRST_ACCOUNT).getTrades(), selectedItems);
        selectedItems.get(0).setFavorite(true);
        assertTrue(dataHandler.isSavePending());
        Map<String, AccountData> persisted = saveAndLoad();
        assertTrue(persisted.get(FIRST_ACCOUNT).getTrades().get(0).isFavorite());
        assertFalse(persisted.get(SECOND_ACCOUNT).getTrades().get(0).isFavorite());

        byte[] originalJournal = journalBytes();
        select(FlippingPlugin.ACCOUNT_WIDE);
        plugin.getItemsForCurrentView().get(0).setFavorite(false);
        assertFalse("A merged view contains copies and must not leave accounts dirty", dataHandler.isSavePending());
        persisted = closeAndLoad();
        assertTrue(persisted.get(FIRST_ACCOUNT).getTrades().get(0).isFavorite());
        assertFalse(persisted.get(SECOND_ACCOUNT).getTrades().get(0).isFavorite());
        assertArrayEquals(originalJournal, journalBytes());
    }

    @Test
    public void accountWideIntervalResetDeletesRecipesFromUnderlyingAccountsAndSavedJson() throws Exception {
        addRecipeHistory();
        select(FlippingPlugin.ACCOUNT_WIDE);
        RecipeFlipGroup merged = plugin.viewRecipeFlipGroupsForCurrentView().get(0);
        assertNotSame(accounts.get(FIRST_ACCOUNT).getRecipeFlipGroups().get(0), merged);
        assertEquals(4, merged.getRecipeFlips().size());

        plugin.deleteOffers(INTERVAL_START);

        for (String account : accounts.keySet()) {
            assertHistory(accounts.get(account), account, "before");
        }
        assertEquals(2, plugin.viewRecipeFlipGroupsForCurrentView().get(0).getRecipeFlips().size());
        Map<String, AccountData> persisted = closeAndLoad();
        for (String account : accounts.keySet()) {
            assertHistory(persisted.get(account), account, "before");
        }
    }

    @Test
    public void singleAccountIntervalResetPreservesOtherAccountsRecipes() throws Exception {
        addRecipeHistory();

        plugin.deleteOffers(INTERVAL_START);

        assertHistory(accounts.get(FIRST_ACCOUNT), FIRST_ACCOUNT, "before");
        assertHistory(accounts.get(SECOND_ACCOUNT), SECOND_ACCOUNT, "before", "after");
        Map<String, AccountData> persisted = closeAndLoad();
        assertHistory(persisted.get(FIRST_ACCOUNT), FIRST_ACCOUNT, "before");
        assertHistory(persisted.get(SECOND_ACCOUNT), SECOND_ACCOUNT, "before", "after");
    }

    private void addRecipeHistory() throws Exception {
        Recipe recipe = new Recipe(singletonList(new RecipeItem(ITEM_ID, 1)),
            singletonList(new RecipeItem(ITEM_ID + 1, 1)), "Test recipe");
        for (String account : accounts.keySet()) {
            List<RecipeFlip> flips = new ArrayList<>();
            for (String position : Arrays.asList("before", "after")) {
                OfferEvent offer = new OfferEvent();
                offer.setUuid(account + "-" + position);
                offer.setMadeBy(account);
                offer.setItemId(ITEM_ID);
                offer.setBuy(true);
                offer.setPrice(100);
                offer.setState(GrandExchangeOfferState.BOUGHT);
                offer.setCurrentQuantityInTrade(1);
                offer.setTotalQuantityInTrade(1);
                offer.setTime(INTERVAL_START.plusSeconds(position.equals("before") ? -60 : 60));
                item(account).getHistory().getCompressedOfferEvents().add(offer);
                flips.add(new RecipeFlip(offer.getTime(), Collections.emptyMap(),
                    singletonMap(ITEM_ID, singletonMap(offer.getUuid(), new PartialOffer(offer, 1))), 0));
            }
            accounts.get(account).getRecipeFlipGroups().add(new RecipeFlipGroup(recipe, flips));
            dataHandler.markDataAsHavingChanged(account);
        }
        saveAndLoad();
    }

    private Map<String, AccountData> saveAndLoad() throws Exception {
        dataHandler.storeData();
        storageExecutor.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertNull(dataHandler.getStorageError());
        assertFalse(dataHandler.isSavePending());
        return new JsonStorage(gson, dataDirectory).load().getAccounts();
    }

    private Map<String, AccountData> closeAndLoad() throws Exception {
        dataHandler.close().get(10, TimeUnit.SECONDS);
        assertNull(dataHandler.getStorageError());
        assertFalse(dataHandler.isSavePending());
        return new JsonStorage(gson, dataDirectory).load().getAccounts();
    }

    private byte[] journalBytes() throws Exception {
        return Files.readAllBytes(dataDirectory.toPath().resolve("json-v2/journal.jsonl"));
    }

    private void assertHistory(AccountData data, String account, String... positions) {
        List<String> expected = Arrays.stream(positions).map(position -> account + "-" + position)
            .collect(Collectors.toList());
        assertEquals(expected, data.getTrades().stream()
            .flatMap(item -> item.getHistory().getCompressedOfferEvents().stream())
            .map(OfferEvent::getUuid).collect(Collectors.toList()));
        assertEquals(expected, data.getRecipeFlipGroups().stream()
            .flatMap(group -> group.getRecipeFlips().stream())
            .flatMap(flip -> flip.getPartialOffers().stream())
            .map(PartialOffer::getOfferUuid).collect(Collectors.toList()));
    }

    private FlippingItem item(String account) {
        return accounts.get(account).getTrades().get(0);
    }

    private void select(String account) throws Exception {
        setField(plugin, FlippingPlugin.class, "accountCurrentlyViewed", account);
    }

    private static void setField(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
