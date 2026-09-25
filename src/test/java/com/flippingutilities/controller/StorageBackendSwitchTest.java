package com.flippingutilities.controller;

import com.flippingutilities.DataSource;
import com.flippingutilities.FlippingConfig;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.google.gson.Gson;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static java.util.Collections.singletonList;
import static org.junit.Assert.*;

public class StorageBackendSwitchTest {
    private static final String ACCOUNT = "Player";
    private static final int ITEM_ID = 4151;
    private static final Instant SESSION_START = Instant.parse("2020-09-01T00:00:00Z");

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ControlledExecutor executor = new ControlledExecutor();
    private final QueuedClientThread clientThread = new QueuedClientThread();
    private final MutableConfig config = new MutableConfig();
    private final MemoryTradePersister persister = new MemoryTradePersister();
    private FlippingPlugin plugin;
    private AccountData account;
    private FlippingItem item;
    private File database;

    @Before
    public void setUp() throws Exception {
        database = new File(temporaryFolder.getRoot(), "flipping.db");
        plugin = new FlippingPlugin() {
            @Override
            protected SqliteStorage createSqliteStorage() {
                return new SqliteStorage(database);
            }
        };
        setField(plugin, "config", config);
        setField(plugin, "executor", executor);
        setField(plugin, "storageExecutor", executor);
        setField(plugin, "clientThread", clientThread);
        setField(plugin, "flippingItemHandler", new FlippingItemHandler(plugin));
        setField(plugin, "accountCurrentlyViewed", ACCOUNT);
        plugin.setCurrentlyLoggedInAccount(ACCOUNT);
        plugin.tradePersister = persister;
        DataHandler dataHandler = new DataHandler(plugin);
        setField(plugin, "dataHandler", dataHandler);
        account = new AccountData();
        account.setVersion(AccountData.CURRENT_VERSION);
        account.setSessionStartTime(SESSION_START);
        account.setAccumulatedSessionTimeMillis(123456L);
        item = new FlippingItem(ITEM_ID, "Abyssal whip", 70, ACCOUNT);
        item.getHistory().getCompressedOfferEvents().add(offer("original", 10));
        account.getTrades().add(item);
        HashMap<String, AccountData> accounts = new HashMap<>();
        accounts.put(ACCOUNT, account);
        setField(dataHandler, "accountSpecificData", accounts);
        setField(dataHandler, "accountWideData", new AccountWideData());
        dataHandler.markDataAsHavingChanged(ACCOUNT);
    }

    @After
    public void tearDown() {
        if (plugin.getSqliteStorage() != null) plugin.getSqliteStorage().close();
        executor.shutdownNow();
    }

    @Test
    public void switchingToSqliteDoesNotWaitForMigrationOrReplaceLiveState() {
        switchTo(DataSource.SQLITE);

        assertEquals("The client action must finish while migration is still queued", 0, persister.loads);
        assertTrue(executor.hasTasks());
        assertLiveStateUnchanged();

        finishStorageWork();

        assertLiveStateUnchanged();
        assertEquals("true", plugin.getSqliteStorage().getSetting("migration_completed"));
        assertEquals(1, plugin.getSqliteStorage().loadAccount(ACCOUNT).getTrades().size());
    }

    @Test
    public void writesDuringPendingMigrationFollowImportAndSurviveReload() {
        RecipeFlip deletedFlip = new RecipeFlip(SESSION_START.plusSeconds(1), new HashMap<>(), new HashMap<>(), 50L);
        RecipeFlipGroup group = new RecipeFlipGroup("4151:4587");
        group.addRecipeFlip(deletedFlip);
        account.getRecipeFlipGroups().add(group);
        switchTo(DataSource.SQLITE);

        // These actions happen after the switch snapshot but before its import executes.
        item.setFavorite(true);
        item.setFavoriteCode("new-code");
        plugin.persistFavoriteOnAccount(ACCOUNT, item);
        plugin.addSelectedGeTabOffers(singletonList(offer("during-import", 7)));
        group.getRecipeFlips().remove(deletedFlip);
        plugin.deleteRecipeFlipFromStorage(group.getRecipeKey(), deletedFlip);

        finishStorageWork();

        AccountData reloaded = plugin.getSqliteStorage().loadAccount(ACCOUNT);
        FlippingItem storedItem = reloaded.getTrades().get(0);
        assertTrue(storedItem.isFavorite());
        assertEquals("new-code", storedItem.getFavoriteCode());
        assertTrue(storedItem.getHistory().getCompressedOfferEvents().stream()
            .anyMatch(offer -> "during-import".equals(offer.getUuid()) && offer.getCurrentQuantityInTrade() == 7));
        assertTrue("Deleted recipe must not return after import", reloaded.getRecipeFlipGroups().isEmpty());
        assertLiveStateUnchanged();
    }

    @Test
    public void switchBackToJsonDiscardsStaleMigrationCompletion() {
        switchTo(DataSource.SQLITE);
        switchTo(DataSource.JSON);
        item.setFavoriteCode("json-edit-after-switch");
        plugin.getDataHandler().markDataAsHavingChanged(ACCOUNT);

        finishStorageWork();

        assertNull(plugin.getSqliteStorage());
        assertEquals("json-edit-after-switch", item.getFavoriteCode());
        assertLiveStateUnchanged();
        plugin.getDataHandler().storeData();
        assertEquals("json-edit-after-switch", persister.loadAllAccounts().get(ACCOUNT).getTrades().get(0).getFavoriteCode());
    }

    @Test
    public void switchingToSqliteRemovesAccountsDeletedWhileUsingJson() {
        SqliteStorage oldStorage = new SqliteStorage(database);
        try {
            oldStorage.initializeSchema();
            oldStorage.upsertAccount("Deleted player", null);
            oldStorage.setSetting("migration_completed", "true");
        } finally {
            oldStorage.close();
        }

        switchTo(DataSource.SQLITE);
        finishStorageWork();

        assertEquals(singletonList(ACCOUNT), plugin.getSqliteStorage().listAccounts());
        assertEquals(singletonList(ACCOUNT), new java.util.ArrayList<>(plugin.getDataHandler().getCurrentAccounts()));
        assertLiveStateUnchanged();
    }

    @Test
    public void failedMigrationRetainsLiveStateAndFallsBackToJson() {
        SqliteStorage existing = new SqliteStorage(database);
        try {
            existing.initializeSchema();
            existing.recordTrade(ACCOUNT, offer("previously-persisted", 4));
            existing.setSetting("migration_completed", "true");
        } finally {
            existing.close();
        }
        persister.failLoads = true;
        switchTo(DataSource.SQLITE);
        item.setFavoriteCode("unsaved-edit");

        finishStorageWork();

        assertNull(plugin.getSqliteStorage());
        assertEquals("unsaved-edit", item.getFavoriteCode());
        assertLiveStateUnchanged();
        SqliteStorage reopened = new SqliteStorage(database);
        try {
            assertTrue(hasOffer(reopened.loadAccount(ACCOUNT), "previously-persisted"));
            assertTrue(reopened.requiresFullResync());
        } finally {
            reopened.close();
        }
    }

    @Test
    public void bulkHiddenItemsStayHiddenAfterReload() {
        switchTo(DataSource.SQLITE);
        finishStorageWork();

        plugin.setAllFlippingItemsAsHidden();
        finishStorageWork();

        assertFalse(item.getValidFlippingPanelItem());
        assertEquals("Reset visibility must survive a SQLite reload", Boolean.FALSE,
            plugin.getSqliteStorage().loadAccount(ACCOUNT).getTrades().get(0).getValidFlippingPanelItem());

        plugin.addSelectedGeTabOffers(singletonList(offer("new-trade-after-hide", 7)));
        finishStorageWork();

        assertTrue(item.getValidFlippingPanelItem());
        assertEquals("A newly imported trade makes its item visible again", Boolean.TRUE,
            plugin.getSqliteStorage().loadAccount(ACCOUNT).getTrades().get(0).getValidFlippingPanelItem());
    }

    @Test
    public void individualVisibilityChangesSurviveReload() {
        switchTo(DataSource.SQLITE);
        finishStorageWork();

        plugin.setItemVisible(item, false);
        finishStorageWork();

        assertFalse(item.getValidFlippingPanelItem());
        assertEquals(Boolean.FALSE,
            plugin.getSqliteStorage().loadAccount(ACCOUNT).getTrades().get(0).getValidFlippingPanelItem());

        plugin.setItemVisible(item, true);
        finishStorageWork();

        assertTrue(item.getValidFlippingPanelItem());
        assertEquals(Boolean.TRUE,
            plugin.getSqliteStorage().loadAccount(ACCOUNT).getTrades().get(0).getValidFlippingPanelItem());
    }

    @Test
    public void failedLiveWritePreservesUnsavedHistoryInJson() throws Exception {
        switchTo(DataSource.SQLITE);
        finishStorageWork();
        SqliteStorage failedStorage = plugin.getSqliteStorage();
        try (Statement statement = failedStorage.getConnection().createStatement()) {
            statement.execute("PRAGMA query_only=ON");
        }

        plugin.addSelectedGeTabOffers(singletonList(offer("write-failed", 7)));
        AtomicBoolean laterTaskRan = new AtomicBoolean();
        plugin.submitStorageTask(storage -> laterTaskRan.set(true));
        executor.drain();

        assertSame("Client fallback has not run yet", failedStorage, plugin.getSqliteStorage());
        assertFalse("A failed backend must reject subsequent queued writes", laterTaskRan.get());
        SqliteStorage reopened = new SqliteStorage(database);
        try {
            assertEquals("true", reopened.getSetting("migration_completed"));
            assertTrue("A separate storage instance must detect the failed write", reopened.requiresFullResync());
            assertFalse("The database is still stale until its JSON rebuild",
                hasOffer(reopened.loadAccount(ACCOUNT), "write-failed"));
        } finally {
            reopened.close();
        }

        finishStorageWork();

        assertNull("A failed backend must stop serving authoritative account data", plugin.getSqliteStorage());
        assertLiveStateUnchanged();
        assertTrue("Fallback saves changes made since the last JSON snapshot",
            hasOffer(persister.loadAllAccounts().get(ACCOUNT), "write-failed"));

        switchTo(DataSource.SQLITE);
        finishStorageWork();

        assertFalse("Only a successful rebuild clears recovery state", plugin.getSqliteStorage().requiresFullResync());
        assertTrue(hasOffer(plugin.getSqliteStorage().loadAccount(ACCOUNT), "write-failed"));
        assertLiveStateUnchanged();
    }

    @Test
    public void failedAccountReadUsesJsonAndMarksDatabaseForRebuild() throws Exception {
        account.getTrades().clear();
        switchTo(DataSource.SQLITE);
        finishStorageWork();
        SqliteStorage failedStorage = plugin.getSqliteStorage();
        try (Statement statement = failedStorage.getConnection().createStatement()) {
            statement.execute("DROP TABLE item_favorites");
        }

        plugin.getDataHandler().loadAccountData(ACCOUNT);

        assertTrue("Read failures must prevent the damaged database from being authoritative next startup",
            failedStorage.requiresFullResync());
        assertTrue(plugin.getDataHandler().viewAccountData(ACCOUNT).getTrades().isEmpty());
        assertEquals(123456L, plugin.getDataHandler().viewAccountData(ACCOUNT).getAccumulatedSessionTimeMillis());

        finishStorageWork();

        assertNull(plugin.getSqliteStorage());
        assertEquals(123456L, persister.loadAccount(ACCOUNT).getAccumulatedSessionTimeMillis());
    }

    @Test
    public void failedJsonSnapshotAbortsSwitchAndRemainsDirtyForRetry() {
        SqliteStorage oldStorage = new SqliteStorage(database);
        try {
            oldStorage.initializeSchema();
            oldStorage.setSetting("migration_completed", "true");
        } finally {
            oldStorage.close();
        }
        item.setFavorite(true);
        item.setFavoriteCode("not-yet-saved");
        persister.failWrites = true;

        switchTo(DataSource.SQLITE);

        assertNull("Import cannot start without a current JSON snapshot", plugin.getSqliteStorage());
        assertFalse(executor.hasTasks());
        assertEquals(0, persister.loads);
        assertLiveStateUnchanged();
        SqliteStorage reopened = new SqliteStorage(database);
        try {
            assertTrue("Restart must prefer the JSON snapshot after its eventual retry",
                reopened.requiresFullResync());
        } finally {
            reopened.close();
        }

        persister.failWrites = false;
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals("A failed snapshot remains dirty for the next autosave", "not-yet-saved",
            persister.loadAllAccounts().get(ACCOUNT).getTrades().get(0).getFavoriteCode());

        switchTo(DataSource.SQLITE);
        finishStorageWork();

        assertEquals("not-yet-saved", plugin.getSqliteStorage().loadAccount(ACCOUNT)
            .getTrades().get(0).getFavoriteCode());
    }

    @Test
    public void shutdownRegistersPendingWritesBeforeClosingStorage() {
        switchTo(DataSource.SQLITE);
        item.setFavorite(true);
        item.setFavoriteCode("before-shutdown");
        plugin.persistFavoriteOnAccount(ACCOUNT, item);
        ClientShutdown shutdown = new ClientShutdown();

        plugin.onClientShutdown(shutdown);

        assertEquals(1, shutdown.getTasks().size());
        Future<?> closed = shutdown.getTasks().peek();
        assertFalse("Shutdown handler must not wait on the client thread", closed.isDone());
        finishStorageWork();
        assertTrue("The registered future covers queued writes and close", closed.isDone());
        assertNull(plugin.getSqliteStorage());

        SqliteStorage reopened = new SqliteStorage(database);
        try {
            FlippingItem storedItem = reopened.loadAccount(ACCOUNT).getTrades().get(0);
            assertTrue(storedItem.isFavorite());
            assertEquals("before-shutdown", storedItem.getFavoriteCode());
        } finally {
            reopened.close();
        }
    }

    private void switchTo(DataSource source) {
        config.source = source;
        ConfigChanged event = new ConfigChanged();
        event.setGroup(FlippingPlugin.CONFIG_GROUP);
        event.setKey("dataSource");
        event.setNewValue(source.name());
        plugin.onConfigChanged(event);
        clientThread.drain();
    }

    private void finishStorageWork() {
        do {
            executor.drain();
            clientThread.drain();
        } while (executor.hasTasks());
    }

    private void assertLiveStateUnchanged() {
        assertSame(account, plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertSame(item, account.getTrades().get(0));
        assertEquals(SESSION_START, account.getSessionStartTime());
        assertEquals(123456L, account.getAccumulatedSessionTimeMillis());
    }

    private static OfferEvent offer(String uuid, int quantity) {
        OfferEvent offer = new OfferEvent();
        offer.setUuid(uuid);
        offer.setBuy(true);
        offer.setItemId(ITEM_ID);
        offer.setCurrentQuantityInTrade(quantity);
        offer.setTotalQuantityInTrade(quantity);
        offer.setPrice(100);
        offer.setTime(SESSION_START.plusSeconds(10));
        offer.setMadeBy(ACCOUNT);
        offer.setState(GrandExchangeOfferState.BOUGHT);
        return offer;
    }

    private static boolean hasOffer(AccountData account, String uuid) {
        return account.getTrades().stream().flatMap(item -> item.getHistory().getCompressedOfferEvents().stream())
            .anyMatch(offer -> uuid.equals(offer.getUuid()));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target instanceof FlippingPlugin ? FlippingPlugin.class : target.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class MutableConfig implements FlippingConfig {
        private DataSource source = DataSource.JSON;
        @Override public DataSource dataSource() { return source; }
    }

    // Replace only JSON disk IO; the real DataHandler, migration and SQLite storage remain in use.
    private static final class MemoryTradePersister extends TradePersister {
        private final Gson gson = new Gson();
        private final Map<String, String> accounts = new HashMap<>();
        private int loads;
        private boolean failLoads;
        private boolean failWrites;

        MemoryTradePersister() { super(new Gson()); }
        @Override public void writeToFile(String name, Object data) {
            if (failWrites) throw new IllegalStateException("Account file is read-only");
            if (data instanceof AccountData) accounts.put(name, gson.toJson(data));
        }
        @Override public Map<String, AccountData> loadAllAccounts() {
            loads++;
            if (failLoads) throw new IllegalStateException("Account file unavailable");
            Map<String, AccountData> result = new HashMap<>();
            accounts.forEach((name, json) -> result.put(name, gson.fromJson(json, AccountData.class)));
            return result;
        }
        @Override public Map<String, AccountData> loadAllAccountsForMigration() {
            return loadAllAccounts();
        }
        @Override public AccountData loadAccount(String name) {
            return gson.fromJson(accounts.get(name), AccountData.class);
        }
        @Override public AccountWideData loadAccountWideData() { return new AccountWideData(); }
    }

    private static final class QueuedClientThread extends ClientThread {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        @Override public void invokeLater(Runnable task) { tasks.add(task); }
        @Override public void invokeLater(BooleanSupplier task) {
            tasks.add(() -> assertTrue("Client action should complete in one tick", task.getAsBoolean()));
        }
        void drain() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }

    private static final class ControlledExecutor extends ScheduledThreadPoolExecutor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        ControlledExecutor() { super(1); }
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public Future<?> submit(Runnable task) {
            NoWaitFuture<Void> future = new NoWaitFuture<>(task);
            execute(future);
            return future;
        }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            // UI refresh timers are outside this test's storage/client boundary.
            return new NoWaitFuture<Void>(task);
        }
        boolean hasTasks() { return !tasks.isEmpty(); }
        void drain() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }
    }

    private static final class NoWaitFuture<V> extends FutureTask<V> implements ScheduledFuture<V> {
        NoWaitFuture(Runnable task) { super(task, null); }
        @Override public V get() {
            throw new AssertionError("Client thread must not wait for migration");
        }
        @Override public V get(long timeout, TimeUnit unit) {
            throw new AssertionError("Client thread must not wait for migration");
        }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
