package com.flippingutilities.controller;

import com.flippingutilities.DataSource;
import com.flippingutilities.FlippingConfig;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.MigrationService;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.flippingutilities.ui.slots.SlotsPanel;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class OfferCorrectionPersistenceTest {
    private static final String ACCOUNT = "Player";
    private static final int ITEM = 4151;
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private SqliteStorage storage;
    private FlippingPlugin plugin;
    private AccountData account;
    private NewOfferEventPipelineHandler pipeline;

    @Before
    public void setUp() throws Exception {
        storage = new SqliteStorage(folder.newFile("offers.db"));
        storage.initializeSchema();
        ItemManager itemManager = itemManagerWithoutNetwork();
        plugin = new FlippingPlugin() {
            private final DataHandler handler = new DataHandler(this);
            private final ApiAuthHandler auth = new ApiAuthHandler(this);
            @Override public ApiAuthHandler getApiAuthHandler() { return auth; }
            private final SlotsPanel slots = new SlotsPanel(this, null) {
                @Override public void update(OfferEvent ignored) {}
            };
            @Override public DataHandler getDataHandler() { return handler; }
            @Override public SlotsPanel getSlotsPanel() { return slots; }
            @Override public SqliteStorage getSqliteStorage() { return storage; }
            @Override public ItemManager getItemManager() { return itemManager; }
            @Override public FlippingConfig getConfig() {
                return new FlippingConfig() {
                    @Override public DataSource dataSource() { return DataSource.SQLITE; }
                };
            }
            @Override public String getAccountCurrentlyViewed() { return "Other account"; }
            @Override public synchronized void submitStorageTask(Consumer<SqliteStorage> task) { task.accept(storage); }
        };
        plugin.setCurrentlyLoggedInAccount(ACCOUNT);
        plugin.getDataHandler().addAccount(ACCOUNT);
        account = plugin.getDataHandler().getAccountData(ACCOUNT);
        account.getTrades().add(new FlippingItem(ITEM, "Whip", 70, ACCOUNT));
        account.getTrades().get(0).setValidFlippingPanelItem(true);
        pipeline = new NewOfferEventPipelineHandler(plugin);
    }

    @After public void tearDown() { if (storage != null) storage.close(); }

    @Test
    public void legacyJsonPreparationSharesActiveIdentityThroughMigrationAndCompletion() throws Exception {
        TradePersister persister = legacyJson(legacyOffer(5, 90), legacyOffer(4, 80), legacyOffer(5, 90));
        AccountData raw = persister.loadAccount(ACCOUNT);
        OfferEvent rawHistory = raw.getTrades().get(0).getHistory().getCompressedOfferEvents().get(1);
        assertNotSame(rawHistory, raw.getLastOffers().get(3));
        assertNull(rawHistory.getUuid());
        assertNull(raw.getLastOffers().get(3).getUuid());

        loadLegacyJson(persister);
        String activeUuid = account.getLastOffers().get(3).getUuid();
        assertNotNull(activeUuid);
        assertEquals(activeUuid, history(account).get(1).getUuid());
        account.prepareForUse(plugin);
        assertEquals("Preparation must keep the established identity", activeUuid, account.getLastOffers().get(3).getUuid());
        migrateAccount();
        assertEquals(9, quantity(reopen()));

        String archivedUuid = history(account).get(0).getUuid();
        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertHistoryPreserved(account, 14, Collections.singletonList(archivedUuid));
        assertHistoryPreserved(reopen(), 14, Collections.singletonList(archivedUuid));
    }

    @Test
    public void rawLegacyJsonMigrationSharesActiveIdentityBeforeReopenAndCompletion() throws Exception {
        TradePersister persister = legacyJson(legacyOffer(5, 90), legacyOffer(4, 80), legacyOffer(5, 90));
        assertEquals(1, new MigrationService(storage, persister).migrate());
        reloadFromSqlite();

        assertEquals(9, quantity(account));
        String activeUuid = account.getLastOffers().get(3).getUuid();
        assertNotNull(activeUuid);
        assertEquals(1, history(account).stream().filter(offer -> activeUuid.equals(offer.getUuid())).count());
        String archivedUuid = history(account).stream().filter(offer -> offer.getCurrentQuantityInTrade() == 4)
            .findFirst().get().getUuid();

        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertHistoryPreserved(account, 14, Collections.singletonList(archivedUuid));
        assertHistoryPreserved(reopen(), 14, Collections.singletonList(archivedUuid));
    }

    @Test
    public void deletedLegacyPartialDoesNotRelinkAnOlderFillWithTheSameQuantity() throws Exception {
        loadLegacyJson(legacyJson(legacyOffer(5, 90), legacyOffer(5, 80)));
        String archivedUuid = history(account).get(0).getUuid();
        assertNotEquals(archivedUuid, account.getLastOffers().get(3).getUuid());
        migrateAccount();
        reloadFromSqlite();
        assertEquals("The deleted active partial must stay hidden after migration", 5, quantity(account));

        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertHistoryPreserved(account, 15, Collections.singletonList(archivedUuid));
        assertHistoryPreserved(reopen(), 15, Collections.singletonList(archivedUuid));
    }

    @Test
    public void ambiguousLegacySnapshotsRemainIndependentOfTheActiveSlot() throws Exception {
        loadLegacyJson(legacyJson(legacyOffer(5, 90), legacyOffer(5, 90), legacyOffer(5, 90)));
        List<String> archivedUuids = new ArrayList<>();
        history(account).forEach(offer -> {
            archivedUuids.add(offer.getUuid());
            assertNotEquals(offer.getUuid(), account.getLastOffers().get(3).getUuid());
        });
        assertNotEquals(archivedUuids.get(0), archivedUuids.get(1));
        migrateAccount();
        reloadFromSqlite();
        assertEquals(10, quantity(account));

        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertHistoryPreserved(account, 20, archivedUuids);
        assertHistoryPreserved(reopen(), 20, archivedUuids);
    }

    @Test
    public void legacySlotMatchingRequiresEveryPersistedSnapshotField() throws Exception {
        List<String> snapshots = new ArrayList<>();
        String[][] changes = {
            {"b", "false"}, {"id", "4152"}, {"cQIT", "4"}, {"p", "101"},
            {"t", "1577836890001"}, {"s", "2"}, {"st", "\"CANCELLED_BUY\""},
            {"tAA", "91"}, {"tSFO", "1"}, {"tQIT", "11"},
            {"tradeStartedAt", "1577836800000"}, {"beforeLogin", "true"}
        };
        for (String[] change : changes) {
            JsonObject snapshot = new JsonParser().parse(legacyOffer(5, 90)).getAsJsonObject();
            snapshot.add(change[0], new JsonParser().parse(change[1]));
            snapshots.add(snapshot.toString());
        }
        loadLegacyJson(legacyJson(legacyOffer(5, 90), snapshots.toArray(new String[0])));
        List<String> archivedUuids = new ArrayList<>();
        history(account).forEach(offer -> {
            archivedUuids.add(offer.getUuid());
            assertNotEquals(offer.getUuid(), account.getLastOffers().get(3).getUuid());
        });
        long archivedQuantity = quantity(account);
        migrateAccount();
        reloadFromSqlite();
        assertEquals(archivedQuantity, quantity(account));

        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertHistoryPreserved(account, archivedQuantity + 10, archivedUuids);
        assertHistoryPreserved(reopen(), archivedQuantity + 10, archivedUuids);
    }

    @Test
    public void existingSlotIdentityDoesNotRelinkAnIdenticalArchivedSnapshot() throws Exception {
        String active = legacyOffer(5, 90).replace("{", "{\"uuid\":\"active\",");
        String archived = legacyOffer(5, 90).replace("{", "{\"uuid\":\"archived\",");
        loadLegacyJson(legacyJson(active, archived));
        assertEquals("active", account.getLastOffers().get(3).getUuid());
        assertEquals("archived", history(account).get(0).getUuid());
        migrateAccount();
        reloadFromSqlite();
        assertEquals(5, quantity(account));

        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertHistoryPreserved(account, 15, Collections.singletonList("archived"));
        assertHistoryPreserved(reopen(), 15, Collections.singletonList("archived"));
    }

    @Test
    public void legacySlotCanShareAnExactHistorySnapshotWithoutChangingItsExistingUuid() throws Exception {
        String historical = legacyOffer(5, 90).replace("{", "{\"uuid\":\"historical\",");
        loadLegacyJson(legacyJson(legacyOffer(5, 90), historical));
        assertEquals("historical", account.getLastOffers().get(3).getUuid());
        assertEquals("historical", history(account).get(0).getUuid());
        migrateAccount();
        reloadFromSqlite();

        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertEquals(10, quantity(account));
        assertEquals(10, quantity(reopen()));
    }

    @Test
    public void legacyCancelledSnapshotSharesIdentityForItsLateCorrection() throws Exception {
        String cancelled = legacyOffer(5, 100).replace("BUYING", "CANCELLED_BUY");
        loadLegacyJson(legacyJson(cancelled, cancelled));
        assertEquals(history(account).get(0).getUuid(), account.getLastOffers().get(3).getUuid());
        migrateAccount();
        assertEquals(5, quantity(reopen()));

        pipeline.onNewOfferEvent(offer("corrected", GrandExchangeOfferState.BUYING, 7, 101));

        assertEquals(7, quantity(account));
        assertEquals(7, quantity(reopen()));
    }

    @Test
    public void completionReplacesOnlyTheMigratedActivePartial() {
        migrateArchivedAndActivePartial(true);
        assertEquals(9, quantity(reopen()));

        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertArchivedFillSurvives(account, 14);
        assertArchivedFillSurvives(reopen(), 14);
    }

    @Test
    public void hiddenActivePredecessorDoesNotMakeCompletionReplaceArchivedHistory() {
        // The active offer still provides slot continuity after its history was deleted.
        migrateArchivedAndActivePartial(false);
        assertEquals(4, quantity(reopen()));

        pipeline.onNewOfferEvent(offer("completed", GrandExchangeOfferState.BOUGHT, 10, 100));

        assertArchivedFillSurvives(account, 14);
        assertArchivedFillSurvives(reopen(), 14);
    }

    @Test
    public void freshOfferInReusedSlotPreservesAnArchivedPartial() {
        account.getTrades().get(0).getHistory().getCompressedOfferEvents()
            .add(offer("archived", GrandExchangeOfferState.BUYING, 4, 80));
        migrateAccount();

        pipeline.onNewOfferEvent(offer("new-start", GrandExchangeOfferState.BUYING, 0, 100));
        pipeline.onNewOfferEvent(offer("new-fill", GrandExchangeOfferState.BUYING, 3, 101));

        assertArchivedFillSurvives(account, 7);
        assertArchivedFillSurvives(reopen(), 7);
    }

    private void migrateArchivedAndActivePartial(boolean activeIsInHistory) {
        account.getTrades().get(0).getHistory().getCompressedOfferEvents()
            .add(offer("archived", GrandExchangeOfferState.BUYING, 4, 80));
        OfferEvent active = offer("active", GrandExchangeOfferState.BUYING, 5, 90);
        if (activeIsInHistory) {
            account.getTrades().get(0).getHistory().getCompressedOfferEvents().add(active);
        }
        account.getLastOffers().put(3, active);
        migrateAccount();
    }

    private void migrateAccount() {
        assertEquals(1, new MigrationService(storage, new TradePersister(new Gson()))
            .migrate(Collections.singletonMap(ACCOUNT, account)));
    }

    private void assertArchivedFillSurvives(AccountData data, long expectedQuantity) {
        assertEquals("A new slot update must not discard an unrelated archived fill", expectedQuantity, quantity(data));
        assertTrue("The archived UUID must remain in history", data.getTrades().get(0).getHistory()
            .getCompressedOfferEvents().stream().anyMatch(offer -> "archived".equals(offer.getUuid())));
    }

    @Test
    public void cancellationCorrectionReplacesExactHistoryAndPreservesRecipeSnapshot() {
        pipeline.onNewOfferEvent(offer("start", GrandExchangeOfferState.BUYING, 0, 90));
        OfferEvent cancelled = offer("cancelled", GrandExchangeOfferState.CANCELLED_BUY, 5, 100);
        pipeline.onNewOfferEvent(cancelled);
        storage.insertRecipeFlip(ACCOUNT, "recipe", new RecipeFlip(Instant.parse("2020-01-02T00:00:00Z"),
            Collections.emptyMap(), Collections.singletonMap(ITEM,
                Collections.singletonMap(cancelled.getUuid(), new PartialOffer(cancelled, 2))), 0L));
        OfferEvent correction = offer("corrected", GrandExchangeOfferState.BUYING, 7, 101);
        assertTrue(correction.isUpdateForCancelled(cancelled));

        pipeline.onNewOfferEvent(correction);

        assertEquals(7, quantity(account));
        AccountData restored = reopen();
        assertEquals("A corrected fill must replace the cancelled snapshot", 7, quantity(restored));
        assertEquals("corrected", restored.getTrades().get(0).getHistory().getCompressedOfferEvents().get(0).getUuid());
        PartialOffer recipeInput = restored.getRecipeFlipGroups().get(0).getRecipeFlips().get(0)
            .getInputs().get(ITEM).get("cancelled");
        assertEquals("Recipe snapshots remain independent of history corrections", 5,
            recipeInput.getOffer().getCurrentQuantityInTrade());
        assertEquals(2, recipeInput.getAmountConsumed());
    }

    @Test
    public void correctedFillSurvivesCollectionAndNextOfferInSameSlot() {
        pipeline.onNewOfferEvent(offer("start", GrandExchangeOfferState.BUYING, 0, 90));
        pipeline.onNewOfferEvent(offer("cancelled", GrandExchangeOfferState.CANCELLED_BUY, 5, 100));
        pipeline.onNewOfferEvent(offer("corrected", GrandExchangeOfferState.BUYING, 7, 101));
        pipeline.onNewOfferEvent(offer("collected", GrandExchangeOfferState.EMPTY, 0, 102));

        assertEquals(7, quantity(account));
        assertEquals("Collection must retain the corrected quantity", 7, quantity(reopen()));
        pipeline.onNewOfferEvent(offer("next-start", GrandExchangeOfferState.BUYING, 0, 110));
        pipeline.onNewOfferEvent(offer("next-fill", GrandExchangeOfferState.BUYING, 3, 111));
        assertEquals("An unrelated new offer must not replace the collected fill", 10, quantity(account));
        assertEquals(10, quantity(reopen()));
    }

    @Test
    public void rejectedCorrectionRollsBackHistoryAndSlotWhileKeepingRecipes() throws Exception {
        pipeline.onNewOfferEvent(offer("start", GrandExchangeOfferState.BUYING, 0, 90));
        OfferEvent cancelled = offer("cancelled", GrandExchangeOfferState.CANCELLED_BUY, 5, 100);
        pipeline.onNewOfferEvent(cancelled);
        storage.insertRecipeFlip(ACCOUNT, "recipe", new RecipeFlip(Instant.parse("2020-01-02T00:00:00Z"),
            Collections.emptyMap(), Collections.singletonMap(ITEM,
                Collections.singletonMap(cancelled.getUuid(), new PartialOffer(cancelled, 2))), 0L));
        try (Statement statement = storage.getConnection().createStatement()) {
            statement.execute("CREATE TRIGGER reject_correction BEFORE INSERT ON trades " +
                "WHEN NEW.uuid = 'corrected' BEGIN SELECT RAISE(ABORT, 'injected correction failure'); END");
        }

        try {
            pipeline.onNewOfferEvent(offer("corrected", GrandExchangeOfferState.BUYING, 7, 101));
            fail("The storage failure must propagate to the storage coordinator");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause().getMessage().contains("injected correction failure"));
        }

        AccountData restored = reopen();
        assertEquals("The old history must survive a failed replacement", 5, quantity(restored));
        assertEquals("cancelled", restored.getTrades().get(0).getHistory().getCompressedOfferEvents().get(0).getUuid());
        assertEquals("A rejected correction must preserve the cancelled offer awaiting collection",
            "cancelled", restored.getLastOffers().get(cancelled.getSlot()).getUuid());
        assertEquals(1, restored.getRecipeFlipGroups().get(0).getRecipeFlips().size());
    }

    private AccountData reopen() {
        storage.close();
        return storage.loadAccount(ACCOUNT);
    }

    private List<OfferEvent> history(AccountData data) {
        return data.getTrades().get(0).getHistory().getCompressedOfferEvents();
    }

    private void assertHistoryPreserved(AccountData data, long expectedQuantity, List<String> retainedUuids) {
        assertEquals(expectedQuantity, quantity(data));
        for (String uuid : retainedUuids) {
            assertTrue("Archived snapshot must survive: " + uuid,
                history(data).stream().anyMatch(offer -> uuid.equals(offer.getUuid())));
        }
    }

    private void loadLegacyJson(TradePersister persister) {
        plugin.tradePersister = persister;
        plugin.getDataHandler().loadAccountData(ACCOUNT);
        account = plugin.getDataHandler().getAccountData(ACCOUNT);
        assertEquals(Integer.valueOf(AccountData.CURRENT_VERSION), account.getVersion());
    }

    private void reloadFromSqlite() {
        storage.close();
        plugin.getDataHandler().setSqliteStorage(storage);
        plugin.getDataHandler().loadAccountData(ACCOUNT);
        account = plugin.getDataHandler().getAccountData(ACCOUNT);
    }

    private TradePersister legacyJson(String slotSnapshot, String... historySnapshots) throws Exception {
        File directory = folder.newFolder();
        String json = "{\"lastOffers\":{\"3\":" + slotSnapshot + "},\"trades\":[{\"id\":4151,"
            + "\"name\":\"Whip\",\"fB\":\"Player\",\"h\":{\"sO\":[" + String.join(",", historySnapshots) + "]}}]}";
        Files.write(new File(directory, ACCOUNT + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
        Constructor<TradePersister> constructor = TradePersister.class.getDeclaredConstructor(Gson.class, File.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new Gson(), directory);
    }

    private String legacyOffer(int quantity, int tick) {
        return "{\"b\":true,\"id\":4151,\"cQIT\":" + quantity + ",\"p\":100,\"t\":"
            + Instant.parse("2020-01-01T00:00:00Z").plusSeconds(tick).toEpochMilli()
            + ",\"s\":3,\"st\":\"BUYING\",\"tAA\":" + tick + ",\"tQIT\":10}";
    }

    /** Use RuneLite's real item manager with an empty definition and no background requests. */
    private ItemManager itemManagerWithoutNetwork() throws Exception {
        ItemComposition definition = (ItemComposition) Proxy.newProxyInstance(ItemComposition.class.getClassLoader(),
            new Class<?>[]{ItemComposition.class}, (proxy, method, args) -> null);
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (proxy, method, args) -> "getItemDefinition".equals(method.getName()) ? definition : null);
        ScheduledExecutorService executor = (ScheduledExecutorService) Proxy.newProxyInstance(
            ScheduledExecutorService.class.getClassLoader(), new Class<?>[]{ScheduledExecutorService.class},
            (proxy, method, args) -> null);
        Constructor<?> constructor = ItemManager.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (ItemManager) constructor.newInstance(client, executor, null, new EventBus(), null, null);
    }

    private long quantity(AccountData data) {
        return data.getTrades().stream().flatMap(item -> item.getHistory().getCompressedOfferEvents().stream())
            .mapToLong(OfferEvent::getCurrentQuantityInTrade).sum();
    }

    private OfferEvent offer(String uuid, GrandExchangeOfferState state, int quantity, int tick) {
        OfferEvent offer = new OfferEvent();
        offer.setUuid(uuid);
        offer.setItemId(ITEM);
        offer.setBuy(true);
        offer.setPrice(100);
        offer.setTime(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(tick));
        offer.setCurrentQuantityInTrade(quantity);
        offer.setTotalQuantityInTrade(10);
        offer.setState(state);
        offer.setTickArrivedAt(tick);
        offer.setSlot(3);
        return offer;
    }
}
