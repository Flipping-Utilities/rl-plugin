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
import java.sql.Connection;
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
    public void failedAccountReadKeepsNewerCachedHistoryThroughRecovery() throws Exception {
        prepareNewerCachedHistory();
        SqliteStorage failedStorage = plugin.getSqliteStorage();
        Connection failedConnection = failedStorage.getConnection();
        try (Statement statement = failedConnection.createStatement()) {
            statement.execute("ALTER TABLE item_favorites RENAME TO unavailable_favorites");
        }

        plugin.getDataHandler().loadAccountData(ACCOUNT);
        // A second directory notification can arrive before queued recovery saves the model.
        plugin.getDataHandler().loadAccountData(ACCOUNT);

        assertSame("A read failure must preserve the newer live account", account,
            plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertEquals(17, totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));
        assertTrue(failedStorage.requiresFullResync());
        try (Statement statement = failedConnection.createStatement()) {
            statement.execute("ALTER TABLE unavailable_favorites RENAME TO item_favorites");
        }

        finishStorageWork();

        assertNull(plugin.getSqliteStorage());
        assertTrue("Recovery must close the failed database connection", failedConnection.isClosed());
        assertLiveStateUnchanged();
        assertEquals(17, totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));
        assertEquals("Recovery must save the newer live account to JSON", 17,
            totalQuantity(persister.loadAccount(ACCOUNT)));
        assertTrue(failedStorage.requiresFullResync());

        switchTo(DataSource.SQLITE);
        finishStorageWork();

        assertFalse(plugin.getSqliteStorage().requiresFullResync());
        assertEquals(17, totalQuantity(plugin.getSqliteStorage().loadAccount(ACCOUNT)));
        assertLiveStateUnchanged();
    }

    @Test
    public void failedRecoverySnapshotKeepsNewerCachedHistoryUntilRetryAndRebuild() throws Exception {
        prepareNewerCachedHistory();
        failAccountRead();
        persister.failWrites = true;

        finishStorageWork();
        assertNull(plugin.getSqliteStorage());
        assertEquals("The failed recovery save leaves the old JSON snapshot", 0,
            totalQuantity(persister.loadAccount(ACCOUNT)));

        plugin.getDataHandler().loadAccountData(ACCOUNT);
        plugin.getDataHandler().loadAccountData(ACCOUNT);

        assertSame("Reload notifications must retain the account whose recovery save failed", account,
            plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertEquals(17, totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));
        assertFalse("Autosave remains retryable while JSON is unwritable", plugin.getDataHandler().storeData());

        persister.failWrites = false;
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals("Retry must save the cached history, not the older JSON snapshot", 17,
            totalQuantity(persister.loadAccount(ACCOUNT)));

        switchTo(DataSource.SQLITE);
        finishStorageWork();

        assertFalse(plugin.getSqliteStorage().requiresFullResync());
        assertEquals(17, totalQuantity(plugin.getSqliteStorage().loadAccount(ACCOUNT)));
        assertLiveStateUnchanged();
    }

    @Test
    public void successfulRecoveryRetryResumesExternalJsonReloads() throws Exception {
        account.getTrades().clear();
        switchTo(DataSource.SQLITE);
        finishStorageWork();
        account.setAccumulatedSessionTimeMillis(654321L);
        failAccountRead();
        persister.failWrites = true;
        finishStorageWork();

        persister.failWrites = false;
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals(654321L, persister.loadAccount(ACCOUNT).getAccumulatedSessionTimeMillis());

        AccountData externalUpdate = new AccountData();
        externalUpdate.setVersion(AccountData.CURRENT_VERSION);
        externalUpdate.setAccumulatedSessionTimeMillis(987654L);
        persister.writeToFile(ACCOUNT, externalUpdate);
        plugin.getDataHandler().loadAccountData(ACCOUNT);

        assertNotSame("A saved recovery must release the cached account for normal reloads", account,
            plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertEquals(987654L, plugin.getDataHandler().viewAccountData(ACCOUNT).getAccumulatedSessionTimeMillis());
    }

    @Test
    public void failedLiveWriteRecoverySnapshotKeepsNewerCachedHistoryUntilRetry() throws Exception {
        account.getTrades().clear();
        switchTo(DataSource.SQLITE);
        finishStorageWork();
        try (Statement statement = plugin.getSqliteStorage().getConnection().createStatement()) {
            statement.execute("PRAGMA query_only=ON");
        }
        account.getTrades().add(item);
        plugin.recordTrade(ACCOUNT, offer("original", 10));
        plugin.addSelectedGeTabOffers(singletonList(offer("write-failed", 7)));
        executor.drain();

        plugin.getDataHandler().loadAccountData(ACCOUNT);

        assertSame("Reloads must preserve the live account before queued write recovery runs", account,
            plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertEquals(17, totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));
        persister.failWrites = true;

        finishStorageWork();
        assertNull(plugin.getSqliteStorage());
        assertEquals(0, totalQuantity(persister.loadAccount(ACCOUNT)));

        plugin.getDataHandler().loadAccountData(ACCOUNT);
        plugin.getDataHandler().loadAccountData(ACCOUNT);

        assertSame("A failed write recovery must preserve its unsaved account through reloads", account,
            plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertEquals(17, totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));

        persister.failWrites = false;
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals(17, totalQuantity(persister.loadAccount(ACCOUNT)));
    }

    @Test
    public void switchToJsonBeforeWriteRecoveryKeepsFailedSnapshotProtected() throws Exception {
        prepareNewerCachedHistory();
        try (Statement statement = plugin.getSqliteStorage().getConnection().createStatement()) {
            statement.execute("PRAGMA query_only=ON");
        }
        plugin.addSelectedGeTabOffers(singletonList(offer("write-failed", 7)));
        persister.failWrites = true;

        // Queue the config change first so it detaches storage before the recovery callback runs.
        queueSwitchTo(DataSource.JSON);
        finishStorageWork();

        assertNull(plugin.getSqliteStorage());
        assertEquals(0, totalQuantity(persister.loadAccount(ACCOUNT)));
        plugin.getDataHandler().loadAccountData(ACCOUNT);
        plugin.getDataHandler().loadAccountData(ACCOUNT);

        assertSame("A config switch must retain recovery protection after its snapshot fails", account,
            plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertEquals(24, totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));

        persister.failWrites = false;
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals(24, totalQuantity(persister.loadAccount(ACCOUNT)));
    }

    @Test
    public void partialRecoverySnapshotRetainsFailedAccountAndResumesHealthyReloads() throws Exception {
        String healthyName = "Healthy player";
        plugin.getDataHandler().addAccount(healthyName);
        AccountData healthyAccount = plugin.getDataHandler().viewAccountData(healthyName);
        healthyAccount.setVersion(AccountData.CURRENT_VERSION);
        plugin.getDataHandler().markDataAsHavingChanged(healthyName);
        prepareNewerCachedHistory();
        healthyAccount.setAccumulatedSessionTimeMillis(654321L);
        failAccountRead();
        persister.failedWriteAccount = ACCOUNT;

        finishStorageWork();

        assertEquals("Other accounts must still be saved when one recovery snapshot fails", 654321L,
            persister.loadAccount(healthyName).getAccumulatedSessionTimeMillis());
        assertEquals(0, totalQuantity(persister.loadAccount(ACCOUNT)));
        plugin.getDataHandler().loadAccountData(ACCOUNT);
        assertSame(account, plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertEquals(17, totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));

        AccountData externalUpdate = new AccountData();
        externalUpdate.setVersion(AccountData.CURRENT_VERSION);
        externalUpdate.setAccumulatedSessionTimeMillis(987654L);
        persister.writeToFile(healthyName, externalUpdate);
        plugin.getDataHandler().loadAccountData(healthyName);

        assertNotSame(healthyAccount, plugin.getDataHandler().viewAccountData(healthyName));
        assertEquals(987654L,
            plugin.getDataHandler().viewAccountData(healthyName).getAccumulatedSessionTimeMillis());
        assertEquals("The healthy account can reload while the other account still needs its snapshot", 0,
            totalQuantity(persister.loadAccount(ACCOUNT)));

        persister.failedWriteAccount = null;
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals(17, totalQuantity(persister.loadAccount(ACCOUNT)));
        assertEquals("Retry must not overwrite the healthy account's external update", 987654L,
            persister.loadAccount(healthyName).getAccumulatedSessionTimeMillis());
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
        plugin.getDataHandler().getCurrentAccounts().remove(ACCOUNT);

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

    @Test
    public void pendingMigrationFailureDetachedBeforeRecoveryMustRetainFailedSnapshot() {
        account.getTrades().clear();
        switchTo(DataSource.SQLITE);
        account.getTrades().add(item);
        plugin.getDataHandler().markDataAsHavingChanged(ACCOUNT);
        persister.failLoads = true;
        persister.failWrites = true;
        queueSwitchTo(DataSource.JSON);
        finishStorageWork();
        assertNull(plugin.getSqliteStorage());
        assertEquals(0, totalQuantity(persister.loadAccount(ACCOUNT)));
        plugin.getDataHandler().loadAccountData(ACCOUNT);
        assertEquals("Pending-import failure must preserve the newer live account after detach", 10,
            totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));
        assertSame(account, plugin.getDataHandler().viewAccountData(ACCOUNT));
        persister.failWrites = false;
        finishStorageWork();
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals(totalQuantity(account), totalQuantity(persister.loadAccount(ACCOUNT)));
    }

    @Test
    public void writeFailureBeforeImportAttachmentMustRetainFailedSnapshot() {
        account.getTrades().clear();
        switchTo(DataSource.SQLITE);
        account.getTrades().add(item);
        plugin.submitStorageTask(storage -> {
            try (Statement statement = storage.getConnection().createStatement()) {
                statement.execute("PRAGMA query_only=ON");
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        plugin.recordTrade(ACCOUNT, offer("original", 10));
        plugin.addSelectedGeTabOffers(singletonList(offer("write-failed-before-attachment", 7)));
        persister.failWrites = true;
        queueSwitchTo(DataSource.JSON);
        finishStorageWork();
        assertNull(plugin.getSqliteStorage());
        assertEquals(0, totalQuantity(persister.loadAccount(ACCOUNT)));
        plugin.getDataHandler().loadAccountData(ACCOUNT);
        assertEquals("A failed write before backend attachment must preserve cached history", 17,
            totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));
        assertSame(account, plugin.getDataHandler().viewAccountData(ACCOUNT));
        persister.failWrites = false;
        finishStorageWork();
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals(totalQuantity(account), totalQuantity(persister.loadAccount(ACCOUNT)));
    }

    @Test
    public void failedPendingMigrationMustRetainCachedDataBeforeRecoveryCallback() {
        account.getTrades().clear();
        switchTo(DataSource.SQLITE);
        account.getTrades().add(item);
        plugin.getDataHandler().markDataAsHavingChanged(ACCOUNT);
        persister.failLoads = true;
        executor.drain();
        assertNotNull("Recovery callback has not detached the backend yet", plugin.getSqliteStorage());
        plugin.getDataHandler().loadAccountData(ACCOUNT);
        assertEquals("The pending-import failure must be visible before the queued recovery callback", 10,
            totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));
        assertSame(account, plugin.getDataHandler().viewAccountData(ACCOUNT));
        persister.failWrites = false;
        finishStorageWork();
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals(totalQuantity(account), totalQuantity(persister.loadAccount(ACCOUNT)));
    }

    @Test
    public void switchBeforeWriteFailureIsReportedPreservesFailedJsonSnapshot() throws Exception {
        prepareNewerCachedHistory();
        try (Statement statement = plugin.getSqliteStorage().getConnection().createStatement()) {
            statement.execute("PRAGMA query_only=ON");
        }
        plugin.addSelectedGeTabOffers(singletonList(offer("write-failed", 7)));
        persister.failWrites = true;
        queueSwitchTo(DataSource.JSON);
        // Detach before the storage worker has reported its write failure.
        clientThread.drain();
        finishStorageWork();

        assertNull(plugin.getSqliteStorage());
        plugin.getDataHandler().loadAccountData(ACCOUNT);
        assertSame(account, plugin.getDataHandler().viewAccountData(ACCOUNT));
        assertEquals(24, totalQuantity(plugin.getDataHandler().viewAccountData(ACCOUNT)));
        assertEquals(0, totalQuantity(persister.loadAccount(ACCOUNT)));

        persister.failWrites = false;
        assertTrue(plugin.getDataHandler().storeData());
        assertEquals(24, totalQuantity(persister.loadAccount(ACCOUNT)));
    }

    @Test
    public void enrichedHistorySurvivesWriteFailureWithoutJsonRebuild() throws Exception {
        switchTo(DataSource.SQLITE);
        finishStorageWork();
        OfferEvent captured = offer("exact-capture", 3);
        captured.setCumulativeAmount(301L);
        captured.setObservedAt(SESSION_START.plusSeconds(20));
        captured.setOrderId("live-order");
        plugin.recordTrade(ACCOUNT, captured);
        finishStorageWork();
        SqliteStorage retained = plugin.getSqliteStorage();
        long revision = retained.getAccountingStore().getSourceRevision(retained.getAccountingStore().findAccountId(ACCOUNT));
        try (Statement statement = retained.getConnection().createStatement()) {
            statement.execute("PRAGMA query_only=ON");
        }
        plugin.recordTrade(ACCOUNT, offer("failed-write", 2));
        finishStorageWork();
        assertSame(retained, plugin.getSqliteStorage());
        assertTrue(plugin.isStorageFailed(retained));
        assertFalse("JSON recovery must not be scheduled for richer accounting data", retained.requiresFullResync());
        assertEquals(revision, retained.getAccountingStore().getSourceRevision(retained.getAccountingStore().findAccountId(ACCOUNT)));
        assertTrue(hasOffer(retained.loadAccount(ACCOUNT), "exact-capture"));
    }

    @Test
    public void resyncMarkerCannotReplaceEnrichedHistoryWithOlderJson() throws Exception {
        switchTo(DataSource.SQLITE);
        finishStorageWork();
        OfferEvent captured = offer("retained-exact", 3);
        captured.setCumulativeAmount(301L);
        captured.setObservedAt(SESSION_START.plusSeconds(20));
        plugin.recordTrade(ACCOUNT, captured);
        finishStorageWork();
        SqliteStorage retained = plugin.getSqliteStorage();
        retained.markOutOfSync();
        java.lang.reflect.Method migrate = FlippingPlugin.class.getDeclaredMethod("runMigrationIfNeeded", SqliteStorage.class, boolean.class);
        migrate.setAccessible(true);
        assertEquals(Boolean.TRUE, migrate.invoke(plugin, retained, true));
        assertTrue(hasOffer(retained.loadAccount(ACCOUNT), "retained-exact"));
        assertFalse(retained.requiresFullResync());
    }

    private void switchTo(DataSource source) {
        queueSwitchTo(source);
        clientThread.drain();
    }

    private void queueSwitchTo(DataSource source) {
        config.source = source;
        ConfigChanged event = new ConfigChanged();
        event.setGroup(FlippingPlugin.CONFIG_GROUP);
        event.setKey("dataSource");
        event.setNewValue(source.name());
        plugin.onConfigChanged(event);
    }

    private void finishStorageWork() {
        do {
            executor.drain();
            clientThread.drain();
        } while (executor.hasTasks());
    }

    private void prepareNewerCachedHistory() {
        account.getTrades().clear();
        switchTo(DataSource.SQLITE);
        finishStorageWork();
        // Keep JSON at the switch snapshot while the live account and SQLite receive newer trades.
        account.getTrades().add(item);
        plugin.recordTrade(ACCOUNT, offer("original", 10));
        plugin.addSelectedGeTabOffers(singletonList(offer("since-snapshot", 7)));
        finishStorageWork();
        assertEquals(17, totalQuantity(account));
        assertEquals(17, totalQuantity(plugin.getSqliteStorage().loadAccount(ACCOUNT)));
        assertEquals(0, totalQuantity(persister.loadAccount(ACCOUNT)));
    }

    private void failAccountRead() throws Exception {
        try (Statement statement = plugin.getSqliteStorage().getConnection().createStatement()) {
            statement.execute("ALTER TABLE item_favorites RENAME TO unavailable_favorites");
            try {
                plugin.getDataHandler().loadAccountData(ACCOUNT);
            } finally {
                statement.execute("ALTER TABLE unavailable_favorites RENAME TO item_favorites");
            }
        }
        assertSame(account, plugin.getDataHandler().viewAccountData(ACCOUNT));
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

    private static int totalQuantity(AccountData account) {
        return account.getTrades().stream().flatMap(item -> item.getHistory().getCompressedOfferEvents().stream())
            .mapToInt(OfferEvent::getCurrentQuantityInTrade).sum();
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
        private String failedWriteAccount;

        MemoryTradePersister() { super(new Gson()); }
        @Override public void writeToFile(String name, Object data) {
            if (failWrites || name.equals(failedWriteAccount)) throw new IllegalStateException("Account file is read-only");
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
