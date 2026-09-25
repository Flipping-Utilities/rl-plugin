package com.flippingutilities.db;

import com.flippingutilities.db.FlipRepository.AggregateStats;
import com.flippingutilities.db.FlipRepository.ItemSummary;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Verifies that {@link SqliteStorage#reconcileFlipsForItem} produces incremental flip events
 * as live trades arrive, so that profit appears without a full re-sync.
 *
 * Scenario: buy 10 @ 50k, then sell 3 @ 55k (partial), then sell 7 @ 54k (rest).
 * Each sell should emit its own flip event for the matched quantity.
 */
public class FlipReconciliationTest {

    private static final String ACCOUNT = "ReconcilePlayer";
    private static final int WHIP = 4151;

    private Path tempDir;
    private File dbFile;
    private SqliteStorage storage;
    private SqliteFlipRepository repository;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("reconcile_test_");
        dbFile = new File(tempDir.toFile(), "reconcile.db");
        storage = new SqliteStorage(dbFile);
        storage.initializeSchema();
        storage.upsertAccount(ACCOUNT, null);
        repository = new SqliteFlipRepository(storage, null);
    }

    @After
    public void tearDown() {
        if (storage != null) storage.close();
        if (tempDir != null) {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (Exception ignored) {}
        }
    }

    // Use a pre-tax-era timestamp (before GE_TAX_START = 1639072800) so that
    // OfferEvent.getPrice() returns the raw price and the expected profit is clean.
    private static final long BASE_TS = 1600000000000L;

    @Test
    public void testBuyAloneShowsNoProfit() {
        // A buy with no matching sell should not produce a flip event.
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS, 10, 50000, true);

        AggregateStats stats = repository.getAggregateStats(ACCOUNT, Instant.EPOCH);
        assertEquals("No sells yet, profit should be 0", 0L, stats.totalProfit);
    }

    @Test
    public void testSellAgainstBuyShowsProfit() {
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS, 10, 50000, true);
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS + 60000, 10, 55000, false);

        AggregateStats stats = repository.getAggregateStats(ACCOUNT, Instant.EPOCH);
        // 10 * (55000 - 50000) = 50000
        assertEquals("Full match profit should be 50000", 50000L, stats.totalProfit);
    }

    @Test
    public void testPartialSellsGrowProfitIncrementally() {
        // Buy 10 @ 50k
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS, 10, 50000, true);

        // Partial sell: 3 @ 55k → flip of qty 3, profit = 3 * 5000 = 15000
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS + 60000, 3, 55000, false);
        AggregateStats afterPartial = repository.getAggregateStats(ACCOUNT, Instant.EPOCH);
        assertEquals("After partial sell of 3, profit should be 15000", 15000L, afterPartial.totalProfit);

        // Second partial sell: 7 @ 54k → flip of qty 7, profit = 7 * 4000 = 28000
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS + 120000, 7, 54000, false);
        AggregateStats afterRest = repository.getAggregateStats(ACCOUNT, Instant.EPOCH);
        assertEquals("After selling the rest, total profit should be 43000", 43000L, afterRest.totalProfit);
    }

    @Test
    public void testReconcileIsIdempotent() {
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS, 10, 50000, true);
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS + 60000, 10, 55000, false);

        AggregateStats stats1 = repository.getAggregateStats(ACCOUNT, Instant.EPOCH);

        // Manually re-run reconcile; should not duplicate events.
        Integer accountId = storage.getAccountId(ACCOUNT);
        assertNotNull(accountId);
        storage.reconcileFlipsForItem(accountId, WHIP);
        storage.reconcileFlipsForItem(accountId, WHIP);

        AggregateStats stats2 = repository.getAggregateStats(ACCOUNT, Instant.EPOCH);
        assertEquals("Re-running reconcile should not change profit", stats1.totalProfit, stats2.totalProfit);
    }

    /**
     * Two identical flips (same timestamp, prices, and quantity) backed by different trades
     * must both be recorded. The natural key includes the first consumed buy/sell trade ids
     * so these don't silently deduplicate.
     */
    @Test
    public void testIdenticalSameTimestampFlipsAreBothCounted() {
        repository.recordTrade(ACCOUNT, WHIP, "buy-1", BASE_TS, 5, 50000, true, 0);
        repository.recordTrade(ACCOUNT, WHIP, "buy-2", BASE_TS, 5, 50000, true, 0);
        repository.recordTrade(ACCOUNT, WHIP, "sell-1", BASE_TS + 60000, 5, 55000, false, 0);
        repository.recordTrade(ACCOUNT, WHIP, "sell-2", BASE_TS + 60000, 5, 55000, false, 0);

        AggregateStats stats = repository.getAggregateStats(ACCOUNT, Instant.EPOCH);
        // 2 flips * 5 items * (55000 - 50000) = 50000
        assertEquals("Both identical same-timestamp flips should be counted", 50000L, stats.totalProfit);
    }

    @Test
    public void testItemSummaryShowsProfitAfterReconcile() {
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS, 5, 50000, true);
        repository.recordTrade(ACCOUNT, WHIP, BASE_TS + 60000, 5, 53000, false);

        List<ItemSummary> summaries = repository.getItemSummaries(ACCOUNT, Instant.EPOCH, "PROFIT", 10, 0);
        assertFalse("Should have at least one item summary", summaries.isEmpty());

        ItemSummary whip = summaries.stream()
            .filter(s -> s.itemId == WHIP)
            .findFirst()
            .orElse(null);
        assertNotNull("Should have a summary for the whip", whip);
        // 5 * (53000 - 50000) = 15000
        assertEquals("Item summary profit should be 15000", 15000L, whip.totalProfit);
    }

    /**
     * Regression: re-running reconcile after a flip event already exists hits the
     * INSERT OR IGNORE on events.natural_key. sqlite-jdbc's getGeneratedKeys() returns a
     * stale rowid for ignored inserts, which previously mis-linked (or FK-violated) the
     * consumed_trade rows. The ignore path must be detected via the update count instead.
     */
    @Test
    public void testRepeatedReconcileKeepsConsumptionConsistent() throws Exception {
        repository.recordTrade(ACCOUNT, WHIP, "rr-buy", BASE_TS, 10, 50000, true, 0);
        repository.recordTrade(ACCOUNT, WHIP, "rr-sell", BASE_TS + 60000, 10, 55000, false, 0);

        Integer accountId = storage.getAccountId(ACCOUNT);
        assertNotNull(accountId);
        // The event now exists; these runs take the OR IGNORE path.
        storage.reconcileFlipsForItem(accountId, WHIP);
        storage.reconcileFlipsForItem(accountId, WHIP);

        assertEquals("Exactly one flip event should exist", 1L,
            count("SELECT COUNT(*) FROM events WHERE type = 'flip'"));
        assertEquals("Exactly two consumed_trade rows (buy + sell)", 2L,
            count("SELECT COUNT(*) FROM consumed_trade"));
        assertEquals("No consumed_trade row may reference a missing event", 0L,
            count("SELECT COUNT(*) FROM consumed_trade ct LEFT JOIN events e ON e.id = ct.event_id WHERE e.id IS NULL"));
        assertEquals("Both trades must be fully consumed", 0L,
            count("SELECT COUNT(*) FROM trades t LEFT JOIN (SELECT trade_id, SUM(qty) AS c FROM consumed_trade GROUP BY trade_id) ct " +
                "ON ct.trade_id = t.id WHERE t.qty - COALESCE(ct.c, 0) > 0"));
    }

    private long count(String sql) throws Exception {
        try (java.sql.Statement st = storage.getConnection().createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
