package com.flippingutilities.controller;

import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AccountViewHandlerTest {
    private static final String FIRST_ACCOUNT = "First player";
    private static final String SECOND_ACCOUNT = "Second player";
    private static final int ITEM_ID = 4151;

    private final Map<String, AccountData> accounts = new HashMap<>();
    private final Set<String> savedAccounts = new HashSet<>();
    private FlippingPlugin plugin;
    private DataHandler dataHandler;

    @Before
    public void setUp() throws Exception {
        plugin = new FlippingPlugin() {
            @Override
            public void submitStorageTask(Consumer<SqliteStorage> task) {
                // These tests exercise JSON dirty tracking and in-memory view selection.
            }
        };
        plugin.tradePersister = new TradePersister(new Gson()) {
            @Override
            public void writeToFile(String displayName, Object data) {
                savedAccounts.add(displayName);
            }
        };
        dataHandler = new DataHandler(plugin);
        setField(plugin, FlippingPlugin.class, "dataHandler", dataHandler);
        setField(plugin, FlippingPlugin.class, "flippingItemHandler", new FlippingItemHandler(plugin));
        setField(dataHandler, DataHandler.class, "accountSpecificData", accounts);
        for (String name : Arrays.asList(FIRST_ACCOUNT, SECOND_ACCOUNT)) {
            AccountData account = new AccountData();
            FlippingItem item = new FlippingItem(ITEM_ID, "Abyssal whip", 70, name);
            item.setValidFlippingPanelItem(true);
            account.getTrades().add(item);
            account.setAccumulatedSessionTimeMillis(1000L);
            accounts.put(name, account);
        }
        select(FIRST_ACCOUNT);
    }

    @Test
    public void readOnlyViewsAndSelectionDoNotScheduleAccountSaves() throws Exception {
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

        assertTrue(dataHandler.storeData());
        assertTrue(savedAccounts.isEmpty());
    }

    @Test
    public void hidingTheSelectedAccountLeavesOtherAccountsUntouched() {
        FlippingItem first = item(FIRST_ACCOUNT);
        plugin.setAllFlippingItemsAsHidden();

        assertFalse(first.getValidFlippingPanelItem());
        assertTrue(accounts.get(FIRST_ACCOUNT).getTrades().isEmpty());
        assertTrue(item(SECOND_ACCOUNT).getValidFlippingPanelItem());
        assertTrue(dataHandler.storeData());
        assertEquals(singleton(FIRST_ACCOUNT), savedAccounts);
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
        assertTrue(dataHandler.storeData());
        assertEquals(accounts.keySet(), savedAccounts);
    }

    @Test
    public void addingAFavoriteTargetsOnlyAccountsInTheCurrentView() throws Exception {
        FlippingItem favorite = new FlippingItem(ITEM_ID, "Abyssal whip", 70, FIRST_ACCOUNT);
        plugin.addFavoritedItem(favorite);
        assertTrue(item(FIRST_ACCOUNT).isFavorite());
        assertFalse(item(SECOND_ACCOUNT).isFavorite());
        assertTrue(dataHandler.storeData());
        assertEquals(singleton(FIRST_ACCOUNT), savedAccounts);

        savedAccounts.clear();
        select(FlippingPlugin.ACCOUNT_WIDE);
        plugin.addFavoritedItem(favorite);
        assertTrue(item(FIRST_ACCOUNT).isFavorite());
        assertTrue(item(SECOND_ACCOUNT).isFavorite());
        assertTrue(dataHandler.storeData());
        assertEquals(accounts.keySet(), savedAccounts);
    }

    @Test
    public void mutableItemAccessMarksOnlyTheUnderlyingSingleAccount() throws Exception {
        assertSame(accounts.get(FIRST_ACCOUNT).getTrades(), plugin.getItemsForCurrentView());
        assertTrue(dataHandler.storeData());
        assertEquals(singleton(FIRST_ACCOUNT), savedAccounts);

        savedAccounts.clear();
        select(FlippingPlugin.ACCOUNT_WIDE);
        plugin.getItemsForCurrentView();
        assertTrue(dataHandler.storeData());
        assertTrue("A merged view contains copies and must not mark underlying accounts dirty", savedAccounts.isEmpty());
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
