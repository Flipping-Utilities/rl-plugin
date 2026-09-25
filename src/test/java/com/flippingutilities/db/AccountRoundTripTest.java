package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.HistoryManager;
import com.flippingutilities.model.OfferEvent;
import com.google.gson.Gson;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class AccountRoundTripTest {
    private static final String ACCOUNT = "Round trip";
    private static final int ITEM = 4151;
    private static final Instant TIME = Instant.parse("2020-01-01T00:00:00Z");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void migrationPreservesMarginCheckPairing() throws Exception {
        AccountData original = account();
        List<OfferEvent> history = original.getTrades().get(0).getHistory().getCompressedOfferEvents();
        history.add(offer("ordinary-buy", GrandExchangeOfferState.BOUGHT, 1, 1, 90, 100, 0));
        history.add(offer("margin-buy", GrandExchangeOfferState.BOUGHT, 1, 1, 110, 2, 60));
        history.add(offer("margin-sell", GrandExchangeOfferState.SOLD, 1, 1, 100, 2, 70));
        assertEquals(-10, firstFlipProfit(history));

        AccountData restored = migrateAndReopen(original);
        List<OfferEvent> restoredHistory = restored.getTrades().get(0).getHistory().getCompressedOfferEvents();

        assertEquals(-10, firstFlipProfit(restoredHistory));
        assertFalse(restoredHistory.get(0).isMarginCheck());
        assertTrue(restoredHistory.get(1).isMarginCheck());
        assertTrue(restoredHistory.get(2).isMarginCheck());
        assertEquals(history, restoredHistory);
    }

    @Test
    public void migrationRestoresPartialFillAndCompletionReplacesIt() throws Exception {
        AccountData original = account();
        OfferEvent partial = offer("partial", GrandExchangeOfferState.BUYING, 5, 10, 100, 8, 0);
        partial.setSlot(3);
        original.getTrades().get(0).updateHistory(partial);
        original.getLastOffers().put(3, partial);

        AccountData restored = migrateAndReopen(original);

        assertEquals(1, restored.getTrades().size());
        FlippingItem item = restored.getTrades().get(0);
        assertEquals(Collections.singletonList(partial), item.getHistory().getCompressedOfferEvents());
        assertEquals(partial, restored.getLastOffers().get(3));
        assertEquals(5, item.getItemsBoughtThisLimitWindow());

        OfferEvent complete = partial.clone();
        complete.setUuid("completion");
        complete.setCurrentQuantityInTrade(10);
        complete.setState(GrandExchangeOfferState.BOUGHT);
        complete.setTime(TIME.plusSeconds(10));
        item.updateHistory(complete);
        assertEquals(Collections.singletonList(complete), item.getHistory().getCompressedOfferEvents());
        assertEquals(10, item.getItemsBoughtThisLimitWindow());
    }

    @Test
    public void migrationPreservesHiddenItemsIncludingItemsWithoutTrades() throws Exception {
        AccountData original = account();
        FlippingItem hidden = original.getTrades().get(0);
        hidden.setValidFlippingPanelItem(false);
        hidden.updateHistory(offer("hidden", GrandExchangeOfferState.BOUGHT, 1, 1, 100, 100, 0));
        FlippingItem noTrades = new FlippingItem(4587, "Other item", 70, ACCOUNT);
        noTrades.setValidFlippingPanelItem(false);
        original.getTrades().add(noTrades);

        AccountData restored = migrateAndReopen(original);

        assertEquals(2, restored.getTrades().size());
        for (FlippingItem item : restored.getTrades()) {
            assertEquals(Boolean.FALSE, item.getValidFlippingPanelItem());
        }
    }

    @Test
    public void liveWritesPreserveOfferClassificationAndVisibilityChanges() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.newFile("live.db"));
        try {
            storage.initializeSchema();
            OfferEvent cancelled = offer("cancelled", GrandExchangeOfferState.CANCELLED_BUY, 1, 10, 100, 1, 0);
            storage.recordTrade(ACCOUNT, cancelled);
            storage.recordTrade(ACCOUNT, cancelled);
            storage.upsertItemVisibility(ACCOUNT, ITEM, false);
            storage.close();

            AccountData restored = storage.loadAccount(ACCOUNT);
            FlippingItem item = restored.getTrades().get(0);
            assertEquals(Collections.singletonList(cancelled), item.getHistory().getCompressedOfferEvents());
            assertFalse(item.getHistory().getCompressedOfferEvents().get(0).isMarginCheck());
            assertEquals(Boolean.FALSE, item.getValidFlippingPanelItem());

            storage.upsertItemVisibility(ACCOUNT, ITEM, true);
            assertEquals(Boolean.TRUE, storage.loadAccount(ACCOUNT).getTrades().get(0).getValidFlippingPanelItem());
        } finally {
            storage.close();
        }
    }

    @Test
    public void livePartialFillSurvivesRestartWithoutAddingItTwice() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.newFile("live-partial.db"));
        try {
            storage.initializeSchema();
            OfferEvent partial = offer("partial", GrandExchangeOfferState.BUYING, 5, 10, 100, 8, 0);
            storage.upsertSlot(ACCOUNT, 0, partial, true);
            storage.close();

            AccountData restored = storage.loadAccount(ACCOUNT);
            assertEquals(Collections.singletonList(partial), restored.getTrades().get(0).getHistory().getCompressedOfferEvents());

            OfferEvent complete = partial.clone();
            complete.setState(GrandExchangeOfferState.BOUGHT);
            complete.setCurrentQuantityInTrade(10);
            storage.recordTrade(ACCOUNT, complete);
            // A stale slot snapshot must never duplicate a completed offer's history.
            assertEquals(Collections.singletonList(complete), storage.loadAccount(ACCOUNT).getTrades().get(0).getHistory().getCompressedOfferEvents());
            storage.upsertSlot(ACCOUNT, 0, complete, false);
            assertTrue(storage.loadAccount(ACCOUNT).getLastOffers().isEmpty());
        } finally {
            storage.close();
        }
    }

    @Test
    public void deletingPartialHistoryPreservesSlotContinuityWithoutRestoringTheDeletedFill() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.newFile("deleted-partial.db"));
        try {
            storage.initializeSchema();
            OfferEvent partial = offer("deleted-partial", GrandExchangeOfferState.BUYING, 5, 10, 100, 8, 0);
            storage.upsertSlot(ACCOUNT, 0, partial, true);
            storage.deleteTradesByUuid(ACCOUNT, Collections.singletonList(partial.getUuid()));
            storage.close();

            AccountData restored = storage.loadAccount(ACCOUNT);
            assertTrue(restored.getTrades().stream()
                .allMatch(item -> item.getHistory().getCompressedOfferEvents().isEmpty()));
            assertEquals(partial, restored.getLastOffers().get(0));

            OfferEvent update = partial.clone();
            update.setUuid("next-partial");
            update.setCurrentQuantityInTrade(7);
            storage.upsertSlot(ACCOUNT, 0, update, true);
            assertEquals(Collections.singletonList(update), storage.loadAccount(ACCOUNT).getTrades()
                .get(0).getHistory().getCompressedOfferEvents());
        } finally {
            storage.close();
        }
    }

    @Test
    public void deletingLastHistoryOfferPreservesGeLimitsForEveryRestoredItem() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.newFile("deleted-history-limits.db"));
        try {
            storage.initializeSchema();
            Instant nextRefresh = Instant.ofEpochMilli(System.currentTimeMillis() + 3_600_000L);
            int[] itemIds = {ITEM, 4587, 11802};
            for (int itemId : itemIds) {
                OfferEvent completed = offer("deleted-" + itemId, GrandExchangeOfferState.BOUGHT, 5, 5, 100, 8, 0);
                completed.setItemId(itemId);
                storage.recordTrade(ACCOUNT, completed);
                storage.upsertGeLimitState(ACCOUNT, itemId, nextRefresh, 7, 5);
                storage.deleteTradesByUuid(ACCOUNT, Collections.singletonList(completed.getUuid()));
            }
            storage.upsertItemVisibility(ACCOUNT, ITEM, true);
            storage.upsertFavorite(ACCOUNT, 4587, true, "q");
            // The third item has only its GE limit state left in storage.
            storage.close();

            AccountData restored = storage.loadAccount(ACCOUNT);
            assertEquals(3, restored.getTrades().size());
            for (int itemId : itemIds) {
                FlippingItem item = restored.getTrades().stream()
                    .filter(candidate -> candidate.getItemId() == itemId).findFirst().orElseThrow(AssertionError::new);
                assertTrue(item.getHistory().getCompressedOfferEvents().isEmpty());
                assertEquals(nextRefresh, item.getGeLimitResetTime());
                assertEquals(7, item.getItemsBoughtThisLimitWindow());
                assertEquals(5, item.getHistory().getItemsBoughtThroughCompleteOffers());
                if (itemId == 4587) {
                    assertTrue(item.isFavorite());
                    assertEquals("q", item.getFavoriteCode());
                }
            }
        } finally {
            storage.close();
        }
    }

    @Test
    public void migrationKeepsDeletedPartialFillOutOfHistory() throws Exception {
        AccountData original = account();
        OfferEvent partial = offer("deleted", GrandExchangeOfferState.BUYING, 5, 10, 100, 8, 0);
        original.getLastOffers().put(0, partial);

        AccountData restored = migrateAndReopen(original);

        assertTrue(restored.getTrades().get(0).getHistory().getCompressedOfferEvents().isEmpty());
        assertEquals(partial, restored.getLastOffers().get(0));
    }

    private AccountData migrateAndReopen(AccountData original) throws Exception {
        File database = folder.newFile("round-trip.db");
        SqliteStorage storage = new SqliteStorage(database);
        try {
            TradePersister source = new TradePersister(new Gson()) {
                @Override
                public Map<String, AccountData> loadAllAccountsForMigration() {
                    return Collections.singletonMap(ACCOUNT, original);
                }

                @Override
                public AccountWideData loadAccountWideData() {
                    return new AccountWideData();
                }
            };
            assertEquals(1, new MigrationService(storage, source).migrate());
        } finally {
            storage.close();
        }
        SqliteStorage reopened = new SqliteStorage(database);
        try {
            reopened.initializeSchema();
            return reopened.loadAccount(ACCOUNT);
        } finally {
            reopened.close();
        }
    }

    private int firstFlipProfit(List<OfferEvent> history) {
        com.flippingutilities.model.Flip flip = HistoryManager.getFlips(history).get(0);
        return (flip.getSellPrice() - flip.getBuyPrice()) * flip.getQuantity();
    }

    private AccountData account() {
        AccountData data = new AccountData();
        data.getTrades().add(new FlippingItem(ITEM, "Abyssal whip", 70, ACCOUNT));
        return data;
    }

    private OfferEvent offer(String uuid, GrandExchangeOfferState state, int quantity, int total,
                             int price, int ticks, int seconds) {
        return new OfferEvent(uuid, OfferEvent.isBuy(state), ITEM, quantity, price,
            TIME.plusSeconds(seconds), 0, state, 123, ticks, total, TIME, false,
            ACCOUNT, "Abyssal whip", price, quantity * price);
    }
}
