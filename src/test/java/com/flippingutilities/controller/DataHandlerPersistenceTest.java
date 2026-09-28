package com.flippingutilities.controller;

import com.flippingutilities.db.JsonStorage;
import com.flippingutilities.db.JsonStorageCodec;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercises real JSON persistence while controlling worker and client callback scheduling. */
public class DataHandlerPersistenceTest {
    private static final String ALICE = "Alice";
    private static final String BOB = "Bob";
    private static final Instant ORIGINAL = Instant.parse("2026-09-28T10:00:00Z");
    private static final Instant FIRST = ORIGINAL.plusSeconds(1);
    private static final Instant SECOND = ORIGINAL.plusSeconds(2);
    private final Gson gson = new Gson();
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private File directory;
    private ControlledExecutor io;
    private ControlledClientThread clientThread;
    private DataHandler handler;
    private FlippingPlugin plugin;
    private OkHttpClient recipeClient;

    @Before
    public void setUp() throws Exception {
        directory = temporaryFolder.newFolder();
        JsonStorage initial = new JsonStorage(gson, directory);
        initial.load();
        AccountWideData settings = new AccountWideData();
        settings.setDefaults();
        java.util.Map<String, AccountData> accounts = new java.util.LinkedHashMap<>();
        accounts.put(ALICE, account());
        accounts.put(BOB, account());
        initial.commit(initial.capture(accounts, settings, Collections.emptySet()));
        io = new ControlledExecutor();
        clientThread = new ControlledClientThread();
        recipeClient = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create(MediaType.parse("application/json"), "[]")).build()).build();
        RecipeHandler recipes = new RecipeHandler(gson, recipeClient, Collections.emptyList());
        plugin = new FlippingPlugin() {
            @Override public ClientThread getClientThread() { return clientThread; }
            @Override public RecipeHandler getRecipeHandler() { return recipes; }
        };
        plugin.tradePersister = new TradePersister(gson) {
            @Override public File getAccountDirectory() { return directory; }
        };
        handler = new DataHandler(plugin, io);
        handler.loadData();
        assertNull(handler.getStorageError());
        assertFalse(handler.isSavePending());
    }

    @After
    public void tearDown() throws Exception {
        if (handler != null) {
            io.runAll();
            Future<?> close = handler.close();
            io.runAll();
            try { close.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException expectedForFailureTests) { /* Preserved dirty data is intentionally unsavable. */ }
        }
        if (recipeClient != null) {
            recipeClient.dispatcher().executorService().shutdownNow();
            recipeClient.connectionPool().evictAll();
        }
    }

    @Test
    public void laterMutationDuringPendingSaveRemainsDirtyAndIsSavedNext() throws Exception {
        AccountData live = handler.getAccountData(ALICE);
        live.setLastModifiedAt(FIRST);
        assertFalse(handler.storeData());
        assertTrue(handler.isSavePending());
        handler.getAccountData(ALICE).setLastModifiedAt(SECOND);

        io.runNext();
        assertEquals(FIRST, disk(ALICE).getLastModifiedAt());
        assertTrue("The completed earlier capture must not acknowledge a later mutation", handler.isSavePending());
        handler.storeData();
        io.runAll();
        assertEquals(SECOND, disk(ALICE).getLastModifiedAt());
        assertFalse(handler.isSavePending());
    }

    @Test
    public void failedDiskWriteRemainsDirtyAndRetriesWithoutLosingNewerEdits() throws Exception {
        Path journal = directory.toPath().resolve("json-v2/journal.jsonl");
        Path temporarilyUnavailable = directory.toPath().resolve("journal.saved");
        handler.getAccountData(ALICE).setLastModifiedAt(FIRST);
        Files.move(journal, temporarilyUnavailable);
        handler.storeData();
        io.runAll();
        assertNotNull(handler.getStorageError());
        assertTrue(handler.isSavePending());

        Files.move(temporarilyUnavailable, journal);
        handler.getAccountData(ALICE).setLastModifiedAt(SECOND);
        handler.storeData();
        io.runAll();
        assertEquals(SECOND, disk(ALICE).getLastModifiedAt());
        assertFalse(handler.isSavePending());
        assertNull(handler.getStorageError());
    }

    @Test
    public void metadataOnlySaveDoesNotSerializeUnchangedHistory() throws Exception {
        AccountData live = handler.viewAccountData(ALICE);
        live.setAccumulatedSessionTimeMillis(5000);
        handler.markSessionTimeChanged(ALICE);
        // A live-only object makes full encoding fail. Session updates must not inspect it.
        live.getTrades().add(null);
        handler.storeData();
        io.runAll();
        assertNull(handler.getStorageError());
        assertEquals(5000, disk(ALICE).getAccumulatedSessionTimeMillis());
        assertTrue(disk(ALICE).getTrades().isEmpty());
        live.getTrades().clear();
    }

    @Test
    public void externalUpdatesSkipDirtyAndDeletedAccounts() throws Exception {
        JsonStorage remote = new JsonStorage(gson, directory);
        JsonStorage.LoadedData remoteView = remote.load();
        remoteView.getAccounts().get(ALICE).setLastModifiedAt(FIRST);
        remoteView.getAccounts().get(BOB).setLastModifiedAt(FIRST);
        remote.commit(remote.capture(remoteView.getAccounts(), null, Collections.emptySet()));
        handler.getAccountData(ALICE).setLastModifiedAt(SECOND);
        handler.deleteAccount(BOB);

        handler.refreshExternalData(() -> fail("No dirty account may be replaced"));
        io.runAll();
        clientThread.runAll();
        assertEquals(SECOND, handler.viewAccountData(ALICE).getLastModifiedAt());
        assertNull(handler.viewAccountData(BOB));
        assertTrue(handler.isSavePending());
    }

    @Test
    public void stalePollCannotBecomeEligibleAfterLocalSaveClearsDirtyFlags() throws Exception {
        JsonStorage remote = new JsonStorage(gson, directory);
        AccountData remoteAlice = remote.load().getAccounts().get(ALICE);
        remoteAlice.setLastModifiedAt(FIRST);
        remote.commit(remote.capture(Collections.singletonMap(ALICE, remoteAlice), null, Collections.emptySet()));

        int[] refreshes = {0};
        handler.refreshExternalData(() -> refreshes[0]++);
        io.runAll(); // The callback now holds a snapshot that has not been accepted.
        handler.getAccountData(BOB).setLastModifiedAt(SECOND);
        handler.storeData();
        io.runAll();
        assertFalse(handler.isSavePending());
        clientThread.runAll();
        assertEquals(0, refreshes[0]);
        assertEquals(ORIGINAL, handler.viewAccountData(ALICE).getLastModifiedAt());
        assertEquals(SECOND, handler.viewAccountData(BOB).getLastModifiedAt());

        // The discarded notification schedules one follow-up without another filesystem event.
        io.runAll();
        clientThread.runAll();
        assertEquals(1, refreshes[0]);
        assertEquals(FIRST, handler.viewAccountData(ALICE).getLastModifiedAt());
        assertEquals(SECOND, handler.viewAccountData(BOB).getLastModifiedAt());
    }

    @Test
    public void notificationDuringAnotherRefreshIsCoalescedIntoOneFollowUp() throws Exception {
        JsonStorage remote = new JsonStorage(gson, directory);
        AccountData remoteAlice = remote.load().getAccounts().get(ALICE);
        remoteAlice.setLastModifiedAt(FIRST);
        remote.commit(remote.capture(Collections.singletonMap(ALICE, remoteAlice), null, Collections.emptySet()));
        handler.refreshExternalData(() -> {});
        io.runAll();

        remoteAlice.setLastModifiedAt(SECOND);
        remote.commit(remote.capture(Collections.singletonMap(ALICE, remoteAlice), null, Collections.emptySet()));
        handler.refreshExternalData(() -> {});
        clientThread.runAll();
        assertEquals(FIRST, handler.viewAccountData(ALICE).getLastModifiedAt());
        io.runAll();
        clientThread.runAll();
        assertEquals(SECOND, handler.viewAccountData(ALICE).getLastModifiedAt());
        assertTrue(io.work.isEmpty());
    }

    @Test
    public void notificationDuringSaveIsRetriedOnceTheSaveCompletes() throws Exception {
        handler.getAccountData(BOB).setLastModifiedAt(SECOND);
        handler.storeData();
        JsonStorage remote = new JsonStorage(gson, directory);
        AccountData remoteAlice = remote.load().getAccounts().get(ALICE);
        remoteAlice.setLastModifiedAt(FIRST);
        remote.commit(remote.capture(Collections.singletonMap(ALICE, remoteAlice), null, Collections.emptySet()));
        handler.refreshExternalData(() -> {});

        io.runAll();
        clientThread.runAll();
        io.runAll();
        clientThread.runAll();
        assertEquals(FIRST, handler.viewAccountData(ALICE).getLastModifiedAt());
        assertEquals(SECOND, handler.viewAccountData(BOB).getLastModifiedAt());
        assertTrue(io.work.isEmpty());
    }

    @Test
    public void deleteCommitsOnlyTheSelectedAccount() throws Exception {
        handler.deleteAccount(ALICE);
        handler.storeData();
        io.runAll();
        JsonStorage.LoadedData reloaded = new JsonStorage(gson, directory).load();
        assertFalse(reloaded.getAccounts().containsKey(ALICE));
        assertTrue(reloaded.getAccounts().containsKey(BOB));
        assertFalse(handler.isSavePending());
    }

    @Test
    public void deletingLoggedInAccountRemotelyKeepsLiveModelAndPreservesNewOffersInRecovery() throws Exception {
        plugin.setCurrentlyLoggedInAccount(ALICE);
        AccountData active = handler.viewAccountData(ALICE);
        JsonStorage remote = new JsonStorage(gson, directory);
        AccountData bob = remote.load().getAccounts().get(BOB);
        bob.setLastModifiedAt(FIRST);
        remote.commit(remote.capture(Collections.singletonMap(BOB, bob), null, Collections.singleton(ALICE)));

        handler.refreshExternalData(() -> {});
        io.runAll();
        clientThread.runAll();
        assertSame("GE events must retain their live AccountData", active, handler.viewAccountData(ALICE));
        assertEquals(FIRST, handler.viewAccountData(BOB).getLastModifiedAt());
        assertTrue(handler.getStorageError().contains("deleted the logged-in account"));

        handler.getAccountData(BOB).setLastModifiedAt(SECOND);
        handler.storeData();
        io.runAll();
        assertEquals(SECOND, disk(BOB).getLastModifiedAt());
        assertNotNull("An unrelated successful save must not clear the deletion conflict", handler.getStorageError());
        assertNull(disk(ALICE));

        addLiveOfferAndAssertRecovery();
    }

    @Test
    public void directReloadAlsoKeepsTheDeletedActiveAccountAndItsOldBaseline() throws Exception {
        plugin.setCurrentlyLoggedInAccount(ALICE);
        AccountData active = handler.viewAccountData(ALICE);
        JsonStorage remote = new JsonStorage(gson, directory);
        remote.load();
        remote.commit(remote.capture(Collections.emptyMap(), null, Collections.singleton(ALICE)));

        handler.loadAccountData(ALICE);
        assertSame(active, handler.viewAccountData(ALICE));
        addLiveOfferAndAssertRecovery();
    }

    private void addLiveOfferAndAssertRecovery() throws Exception {
        OfferEvent incoming = new OfferEvent();
        incoming.setUuid("new-live-offer");
        incoming.setItemId(4151);
        incoming.setBuy(true);
        incoming.setPrice(100L);
        incoming.setTime(SECOND);
        incoming.setSlot(1);
        incoming.setState(GrandExchangeOfferState.BUYING);
        incoming.setCurrentQuantityInTrade(3);
        incoming.setTotalQuantityInTrade(10);
        incoming.setTradeStartedAt(FIRST);
        handler.getAccountData(ALICE).getLastOffers().put(1, incoming);
        handler.storeData();
        io.runAll();
        assertNull("A stale client must not recreate an account deleted remotely", disk(ALICE));
        assertTrue("New live changes must remain dirty after CAS rejects them", handler.isSavePending());
        assertTrue(handler.getStorageError().contains("preserved"));
        Path recovery;
        try (java.util.stream.Stream<Path> files = Files.list(directory.toPath().resolve("json-v2"))) {
            recovery = files.filter(file -> file.getFileName().toString().startsWith("conflict-"))
                .findFirst().orElseThrow(() -> new AssertionError("Expected a conflict recovery file"));
        }
        JsonObject checkpoint;
        try (java.io.Reader reader = Files.newBufferedReader(recovery)) {
            checkpoint = new JsonParser().parse(reader).getAsJsonObject();
        }
        java.util.Map<String, JsonElement> records = new java.util.LinkedHashMap<>();
        checkpoint.getAsJsonObject("records").entrySet().forEach(entry -> records.put(entry.getKey(), entry.getValue()));
        AccountData preserved = new JsonStorageCodec(gson).decodeAccount(ALICE, records);
        assertEquals("new-live-offer", preserved.getLastOffers().get(1).getUuid());
        assertEquals(3, preserved.getLastOffers().get(1).getCurrentQuantityInTrade());
    }

    @Test
    public void closeWaitsForEarlierCaptureThenCapturesLatestLiveObjects() throws Exception {
        handler.getAccountData(ALICE).setLastModifiedAt(FIRST);
        handler.storeData();
        handler.getAccountData(ALICE).setLastModifiedAt(SECOND);
        CompletableFuture<Future<?>> closing = CompletableFuture.supplyAsync(handler::close);

        io.runNext();
        Future<?> finalSave = closing.get(5, TimeUnit.SECONDS);
        assertFalse(finalSave.isDone());
        io.runAll();
        finalSave.get(5, TimeUnit.SECONDS);
        assertEquals(SECOND, disk(ALICE).getLastModifiedAt());
        assertFalse(handler.isSavePending());
    }

    @Test
    public void failedInitialLoadCannotOverwriteDamagedStorage() throws Exception {
        handler.close().get(5, TimeUnit.SECONDS);
        Path primary = directory.toPath().resolve("json-v2/checkpoint.json");
        Path previous = directory.toPath().resolve("json-v2/checkpoint.previous.json");
        byte[] bad = "invalid checkpoint".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(primary, bad);
        Files.write(previous, bad);
        io = new ControlledExecutor();
        handler = new DataHandler(plugin, io);
        handler.loadData();
        assertNotNull(handler.getStorageError());
        handler.addAccount("New account");
        assertFalse(handler.storeData());
        io.runAll();
        assertArrayEquals(bad, Files.readAllBytes(primary));
        assertArrayEquals(bad, Files.readAllBytes(previous));
        assertTrue(handler.isSavePending());
    }

    @Test
    public void failedPreparationProtectsAccountFromSaveAndDeletion() throws Exception {
        handler.close().get(5, TimeUnit.SECONDS);
        JsonStorage other = new JsonStorage(gson, directory);
        AccountData invalidForThisClient = other.load().getAccounts().get(ALICE);
        invalidForThisClient.getTrades().add(new FlippingItem(4151, "Abyssal whip", 70, ALICE));
        other.commit(other.capture(Collections.singletonMap(ALICE, invalidForThisClient), null, Collections.emptySet()));
        // The plugin has no ItemManager: a valid persisted item cannot be hydrated here.
        io = new ControlledExecutor();
        handler = new DataHandler(plugin, io);
        handler.loadData();
        assertNotNull(handler.getStorageError());
        handler.markDataAsHavingChanged(ALICE);
        assertFalse(handler.storeData()); // Capture failure must be caught and remain dirty.
        handler.deleteAccount(ALICE);
        io.runAll();
        assertEquals(1, disk(ALICE).getTrades().size());
        assertTrue(handler.isSavePending());
    }

    private AccountData disk(String name) throws Exception {
        return new JsonStorage(gson, directory).load().getAccounts().get(name);
    }

    private static AccountData account() {
        AccountData data = new AccountData();
        data.setLastModifiedAt(ORIGINAL);
        return data;
    }

    private static final class ControlledClientThread extends ClientThread {
        private final Queue<Runnable> callbacks = new ArrayDeque<>();
        @Override public void invokeLater(Runnable runnable) { callbacks.add(runnable); }
        void runAll() { while (!callbacks.isEmpty()) callbacks.remove().run(); }
    }

    private static final class ControlledExecutor extends AbstractExecutorService {
        private final BlockingQueue<Runnable> work = new LinkedBlockingQueue<>();
        private volatile boolean shutdown;
        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            work.add(command);
        }
        void runNext() throws Exception {
            Runnable command = work.poll(5, TimeUnit.SECONDS);
            assertNotNull("Expected queued I/O", command);
            command.run();
        }
        void runAll() { Runnable command; while ((command = work.poll()) != null) command.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>();
            work.drainTo(remaining);
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && work.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
