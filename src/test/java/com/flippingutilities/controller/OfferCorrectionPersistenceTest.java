package com.flippingutilities.controller;

import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.MigrationService;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.*;
import com.google.gson.Gson;
import com.flippingutilities.ui.slots.SlotsPanel;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.Instant;
import java.sql.Statement;
import java.util.Collections;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class OfferCorrectionPersistenceTest {
    private static final String ACCOUNT = "Player";
    private static final int ITEM = 4151;
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private SqliteStorage storage;
    private AccountData account;
    private NewOfferEventPipelineHandler pipeline;

    @Before
    public void setUp() throws Exception {
        storage = new SqliteStorage(folder.newFile("offers.db"));
        storage.initializeSchema();
        FlippingPlugin plugin = new FlippingPlugin() {
            private final DataHandler handler = new DataHandler(this);
            private final ApiAuthHandler auth = new ApiAuthHandler(this);
            @Override public ApiAuthHandler getApiAuthHandler() { return auth; }
            private final SlotsPanel slots = new SlotsPanel(this, null) {
                @Override public void update(OfferEvent ignored) {}
            };
            @Override public DataHandler getDataHandler() { return handler; }
            @Override public SlotsPanel getSlotsPanel() { return slots; }
            @Override public SqliteStorage getSqliteStorage() { return storage; }
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
        assertTrue("The rejected partial must not leave an active slot", restored.getLastOffers().isEmpty());
        assertEquals(1, restored.getRecipeFlipGroups().get(0).getRecipeFlips().size());
    }

    private AccountData reopen() {
        storage.close();
        return storage.loadAccount(ACCOUNT);
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
