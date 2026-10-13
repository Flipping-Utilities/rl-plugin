package com.flippingutilities.controller.accounting;

import com.flippingutilities.accounting.AccountingPlan.Mode;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.ui.accounting.AccountingUiService.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AccountingCoordinatorIntegrationTest
{
    private static final Instant START = Instant.parse("2020-01-01T00:00:00Z");
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private SqliteStorage storage;
    private AccountingCoordinator coordinator;

    @Before public void setUp() throws Exception
    {
        storage = new SqliteStorage(folder.newFile("accounting.db"));
        storage.initializeSchema();
        coordinator = new AccountingCoordinator(storage, Runnable::run, () -> true);
    }

    @After public void tearDown() { storage.close(); }

    @Test public void salePeriodUsesPurchaseFromPreviousDayAndDetailMatchesSqlSummary()
    {
        trade("A", 10, "buy", true, 1, 100, 10, "Test item");
        trade("A", 10, "sale", false, 1, 120, 86410, "Test item");
        activate("A");
        ReportQuery query = query(List.of("A"), ReportKind.ITEMS, 86400L, 172800L, "", Sort.TIME, null, 0, 10);
        ReportResult report = coordinator.queryReport(query).join();
        assertEquals(Long.valueOf(20), report.segments.get(0).amounts.profit.completeGp);
        assertEquals(Long.valueOf(100), report.rows.get(0).amounts.cost.completeGp);
        assertTrue(report.rows.get(0).amounts.profit.estimated);
        ReportResult details = coordinator.queryReport(query(List.of("A"), ReportKind.FLIPS, 86400L, 172800L,
            "", Sort.TIME, report.rows.get(0).detailKey, 0, 10)).join();
        assertEquals(report.rows.get(0).amounts.profit.completeGp, details.rows.get(0).amounts.profit.completeGp);
        assertEquals(START.plusSeconds(86410), details.rows.get(0).occurredAt);
    }

    @Test public void sqlSearchTreatsPercentAndUnderscoreAsLiteralCharacters()
    {
        pair("A", 10, 20, "Rune%_ item");
        pair("A", 20, 30, "RuneXX item");
        activate("A");
        ReportResult result = coordinator.queryReport(query(List.of("A"), ReportKind.ITEMS, null, null,
            "%_", Sort.PROFIT, null, 0, 10)).join();
        assertEquals(1, result.totalRows);
        assertEquals("Rune%_ item", result.rows.get(0).title);
        assertEquals(Long.valueOf(20), result.segments.get(0).amounts.profit.completeGp);
    }

    @Test public void accountsNeverSharePurchaseBasisAndZeroRemainsKnown()
    {
        trade("A", 10, "buy", true, 1, 100, 10, "Shared item");
        trade("B", 10, "sale", false, 1, 120, 20, "Shared item");
        trade("B", 20, "free-buy", true, 1, 0, 10, "Zero cost item");
        trade("B", 20, "free-sale", false, 1, 20, 20, "Zero cost item");
        activate("A"); activate("B");
        ReportResult report = coordinator.queryReport(query(List.of("A", "B"), ReportKind.ITEMS, null, null,
            "", Sort.PROFIT, null, 0, 10)).join();
        assertEquals(2, report.rows.size());
        assertEquals("Zero cost item", report.rows.get(0).title);
        assertEquals(Long.valueOf(20), report.rows.get(0).amounts.profit.completeGp);
        assertNull(report.rows.get(1).amounts.profit.completeGp);
        assertEquals(Long.valueOf(120), report.rows.get(1).amounts.gross.completeGp);
        assertEquals(1, report.rows.get(1).amounts.profit.unknownCount);
    }

    @Test public void mixedMethodsSortAndPageGloballyWithAccountScopedDrillDown()
    {
        pair("Legacy", 10, 50, "Shared");
        pair("New", 10, 20, "Shared");
        pair("New", 20, 80, "Highest");
        activate("New");
        List<String> accounts = List.of("Legacy", "New");
        ReportResult first = coordinator.queryReport(query(accounts, ReportKind.ITEMS, null, null,
            "", Sort.PROFIT, null, 0, 1)).join();
        ReportResult second = coordinator.queryReport(query(accounts, ReportKind.ITEMS, null, null,
            "", Sort.PROFIT, null, 1, 1)).join();
        assertEquals(3, first.totalRows);
        assertEquals("Highest", first.rows.get(0).title);
        assertEquals("Legacy", second.rows.get(0).accountLabel);
        assertEquals(2, first.segments.size());
        ReportResult details = coordinator.queryReport(query(accounts, ReportKind.FLIPS, null, null,
            "", Sort.PROFIT, second.rows.get(0).detailKey, 0, 10)).join();
        assertEquals(1, details.totalRows);
        assertEquals("Legacy", details.rows.get(0).accountLabel);
    }

    @Test public void tiedSortOrderMatchesAcrossWholeAndSingleRowPages()
    {
        pair("A", 2, 20, "Two"); pair("A", 10, 20, "Ten"); pair("A", 3, 20, "Three");
        activate("A");
        ReportResult all = coordinator.queryReport(query(List.of("A"), ReportKind.ITEMS, null, null,
            "", Sort.PROFIT, null, 0, 10)).join();
        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++)
        {
            ReportResult part = coordinator.queryReport(query(List.of("A"), ReportKind.ITEMS, null, null,
                "", Sort.PROFIT, null, page, 1)).join();
            paged.add(part.rows.get(0).id);
        }
        assertEquals(all.rows.stream().map(row -> row.id).collect(Collectors.toList()), paged);
    }

    @Test public void staleMigrationPreviewCannotActivateAfterAnotherTrade()
    {
        pair("A", 10, 20, "Item");
        Preview preview = coordinator.preview(request("A", Mode.RECALCULATE)).join();
        trade("A", 20, "next", true, 1, 50, 40, "Another");
        assertStale(() -> coordinator.apply(preview).join());
        assertNull(storage.getAccountingStore().getActivePlan(storage.getAccountingStore().findAccountId("A")));
    }

    @Test public void staleExportPreservesExistingDestinationAndMatchingExportHasSnapshotColumns() throws Exception
    {
        pair("A", 10, 20, "Wand, \"fine\""); activate("A");
        ReportQuery query = query(List.of("A"), ReportKind.ITEMS, null, null, "", Sort.TIME, null, 0, 10);
        ReportResult report = coordinator.queryReport(query).join();
        Path path = folder.newFile("report.csv").toPath();
        coordinator.exportReport(query.atRevision(report.sourceRevision, report.projectionRevision), path).join();
        String csv = Files.readString(path);
        assertTrue(csv.contains("Source revision,Projection revision"));
        assertTrue(csv.contains("\"Wand, \"\"fine\"\"\""));
        assertTrue(csv.contains(report.sourceRevision));
        trade("A", 20, "new-trade", true, 1, 50, 40, "Another");
        assertStale(() -> coordinator.exportReport(query.atRevision(report.sourceRevision, report.projectionRevision), path).join());
        assertEquals(csv, Files.readString(path));
    }

    @Test public void datedNewReportExcludesUndatedFactsWhileAllRetainsThem()
    {
        OfferEvent sale = new OfferEvent("sale", false, 10, 1, 120, null, 0,
            GrandExchangeOfferState.SOLD, 0, 10, 1, null, false, "A", "Undated item", 120, 120);
        storage.recordTrade("A", sale);
        activate("A");
        ReportResult all = coordinator.queryReport(query(List.of("A"), ReportKind.ITEMS, null, null,
            "", Sort.TIME, null, 0, 10)).join();
        assertEquals(1, all.totalRows);
        ReportResult dated = coordinator.queryReport(query(List.of("A"), ReportKind.ITEMS, 0L, 20L,
            "", Sort.TIME, null, 0, 10)).join();
        assertEquals(0, dated.totalRows);
    }

    @Test public void freshLedgerKeepsHistoricalArchiveAndExportsThatSameView() throws Exception
    {
        pair("A", 10, 20, "Historical item");
        PlanRequest fresh = new PlanRequest("A", Mode.FRESH_START, START.plusSeconds(100), null,
            ZoneOffset.UTC, Map.of(), null);
        coordinator.apply(coordinator.preview(fresh).join()).join();
        ReportQuery normal = query(List.of("A"), ReportKind.ITEMS, null, null, "", Sort.TIME, null, 0, 10);
        assertEquals(0, coordinator.queryReport(normal).join().totalRows);
        ReportQuery archived = new ReportQuery(normal.accounts, null, null, "Archive", "", Sort.TIME,
            ReportKind.ITEMS, null, 0, 10, null, null, true);
        ReportResult history = coordinator.queryReport(archived).join();
        assertEquals(1, history.totalRows);
        assertEquals(Long.valueOf(20), history.rows.get(0).amounts.profit.completeGp);
        assertTrue(history.rows.get(0).methodLabel.contains("Archived"));
        Path file = folder.newFile("archive.csv").toPath();
        coordinator.exportReport(archived.atRevision(history.sourceRevision, history.projectionRevision), file).join();
        assertTrue(Files.readString(file).contains("Archived retained history"));
        assertTrue(Files.readString(file).contains("Historical item"));
    }

    @Test public void restrictedRecipeEvidenceCountsOnlyReservedSegmentsOnceInTradeActivity()
    {
        OfferEvent input = detached("input", true, 10, 10, 10, 10);
        OfferEvent output = detached("output", false, 20, 8, 30, 20);
        RecipeFlip recipe = new RecipeFlip(START.plusSeconds(50),
            Map.of(20, Map.of("output", new PartialOffer(output, 2))),
            Map.of(10, Map.of("input", new PartialOffer(input, 2))), 0);
        storage.insertRecipeFlip("A", "10:2|20:2", recipe);
        activate("A");
        ReportResult report = coordinator.queryReport(query(List.of("A"), ReportKind.RECIPES, null, null,
            "", Sort.TIME, null, 0, 10)).join();
        assertEquals(1, report.totalRows);
        assertEquals(Long.valueOf(40), report.rows.get(0).amounts.profit.completeGp);
        Activity activity = report.segments.get(0).activity;
        assertNotNull(activity);
        assertEquals(2, activity.boughtQuantity);
        assertEquals(2, activity.soldQuantity);
        assertEquals(Long.valueOf(20), activity.spent.completeGp);
        assertEquals(Long.valueOf(60), activity.proceeds.completeGp);
        assertEquals(Long.valueOf(0), activity.tax.completeGp);
    }

    @Test public void incompleteFinancialSortTiesRemainStableAcrossPages()
    {
        pair("A", 2, 90, "Two"); pair("A", 10, 20, "Ten");
        trade("A", 2, "unknown-two", false, 1, 200, 30, "Two");
        trade("A", 10, "unknown-ten", false, 1, 200, 30, "Ten");
        activate("A");
        for (Sort sort : List.of(Sort.PROFIT, Sort.PROFIT_EACH, Sort.ROI))
        {
            ReportResult all = coordinator.queryReport(query(List.of("A"), ReportKind.ITEMS, null, null,
                "", sort, null, 0, 10)).join();
            ReportResult first = coordinator.queryReport(query(List.of("A"), ReportKind.ITEMS, null, null,
                "", sort, null, 0, 1)).join();
            ReportResult second = coordinator.queryReport(query(List.of("A"), ReportKind.ITEMS, null, null,
                "", sort, null, 1, 1)).join();
            assertNull(first.rows.get(0).amounts.profit.completeGp);
            assertEquals(all.rows.get(0).id, first.rows.get(0).id);
            assertEquals(all.rows.get(1).id, second.rows.get(0).id);
        }
    }

    @Test public void openInventoryRetainsExactResidualCostAndDetailsUseTheSameBasis()
    {
        OfferEvent buy = detached("buy", true, 10, 3, 100, 10);
        buy.setCumulativeAmount(301L);
        storage.recordTrade("A", buy);
        trade("A", 10, "sale", false, 1, 150, 20, "Residual item");
        activate("A");
        ReportQuery inventoryQuery = query(List.of("A"), ReportKind.INVENTORY, null, null,
            "", Sort.TIME, null, 0, 10);
        ReportResult inventory = coordinator.queryReport(inventoryQuery).join();
        assertEquals(1, inventory.totalRows);
        assertEquals(2, inventory.rows.get(0).quantity);
        assertEquals(Long.valueOf(201), inventory.rows.get(0).amounts.cost.completeGp);
        ReportDetails inventoryDetails = coordinator.queryDetails(inventoryQuery.atRevision(
            inventory.sourceRevision, inventory.projectionRevision), inventory.rows.get(0).id).join();
        assertTrue(inventoryDetails.lines.toString(), inventoryDetails.lines.stream().anyMatch(line -> line.contains("201 gp")));
        ReportQuery flipsQuery = query(List.of("A"), ReportKind.FLIPS, null, null, "", Sort.TIME, null, 0, 10);
        ReportResult flips = coordinator.queryReport(flipsQuery).join();
        assertEquals(Long.valueOf(100), flips.rows.get(0).amounts.cost.completeGp);
        ReportDetails details = coordinator.queryDetails(flipsQuery.atRevision(flips.sourceRevision, flips.projectionRevision),
            flips.rows.get(0).id).join();
        assertTrue(details.lines.toString(), details.lines.stream().anyMatch(line -> line.contains("100 gp")));
        assertEquals(Long.valueOf(301), Long.valueOf(inventory.rows.get(0).amounts.cost.completeGp
            + flips.rows.get(0).amounts.cost.completeGp));
    }

    @Test public void inventoryPagesUseSameOrderingWhenAnUnsupportedFinancialSortIsRequested()
    {
        trade("A", 2, "two", true, 1, 100, 30, "Two");
        trade("A", 10, "ten", true, 1, 100, 10, "Ten");
        trade("A", 3, "three", true, 1, 100, 20, "Three");
        activate("A");
        ReportResult all = coordinator.queryReport(query(List.of("A"), ReportKind.INVENTORY, null, null,
            "", Sort.PROFIT, null, 0, 10)).join();
        for (int page = 0; page < all.totalRows; page++)
        {
            ReportResult slice = coordinator.queryReport(query(List.of("A"), ReportKind.INVENTORY, null, null,
                "", Sort.PROFIT, null, page, 1)).join();
            assertEquals(all.rows.get(page).id, slice.rows.get(0).id);
        }
    }

    @Test public void migrationPreviewComparesActualActiveMethodAcrossResolvedPeriods()
    {
        Instant now = Instant.now();
        long buyTime = now.minusSeconds(48 * 3600).getEpochSecond() - START.getEpochSecond();
        long saleTime = now.minusSeconds(3600).getEpochSecond() - START.getEpochSecond();
        trade("A", 10, "old-buy", true, 1, 20, buyTime, "Item");
        trade("A", 10, "recent-sale", false, 1, 23, saleTime, "Item");
        Preview initial = coordinator.preview(request("A", Mode.RECALCULATE)).join();
        assertEquals(Long.valueOf(3), comparison(initial, "Current", "All history").amounts.profit.completeGp);
        assertEquals(Long.valueOf(3), comparison(initial, "Proposed", "All history").amounts.profit.completeGp);
        assertEquals(Long.valueOf(0), comparison(initial, "Current", "Last 24 hours").amounts.profit.completeGp);
        assertEquals(Long.valueOf(3), comparison(initial, "Proposed", "Last 24 hours").amounts.profit.completeGp);
        assertTrue(comparison(initial, "Proposed", "Last 24 hours").periodLabel.contains("UTC"));
        coordinator.apply(initial).join();
        Preview revert = coordinator.preview(request("A", Mode.LEGACY)).join();
        assertTrue(comparison(revert, "Current", "Last 24 hours").methodLabel.contains("sale-time"));
        assertEquals(Long.valueOf(3), comparison(revert, "Current", "Last 24 hours").amounts.profit.completeGp);
        assertEquals(Long.valueOf(0), comparison(revert, "Proposed", "Last 24 hours").amounts.profit.completeGp);
        assertEquals(6, revert.comparisons.size());
    }

    @Test public void reviewingSavedHybridShowsItsOriginalFrozenComparison()
    {
        Instant cutover = Instant.now().minusSeconds(2 * 86400);
        long before = cutover.getEpochSecond() - START.getEpochSecond();
        OfferEvent buy = trade("A", 10, "buy", true, 1, 20, before - 7200, "Item");
        trade("A", 10, "sale", false, 1, 23, before - 3600, "Item");
        PlanRequest hybrid = new PlanRequest("A", Mode.HYBRID, cutover, null, ZoneOffset.UTC, Map.of(), null);
        Preview original = coordinator.preview(hybrid).join();
        coordinator.apply(original).join();
        buy.setPrice(22);
        storage.recordTrade("A", buy);
        storage.getAccountingStore().reconcile(storage.getAccountingStore().findAccountId("A"));
        PlanRequest recalculate = new PlanRequest("A", Mode.RECALCULATE, null, null, ZoneOffset.UTC, Map.of(), original.id);
        Preview replacement = coordinator.preview(recalculate).join();
        assertEquals(Long.valueOf(3), comparison(replacement, "Saved plan", "All history").amounts.profit.completeGp);
        assertEquals(Long.valueOf(1), comparison(replacement, "Proposed", "All history").amounts.profit.completeGp);
        assertTrue(comparison(replacement, "Saved plan", "All history").periodLabel
            .contains(Instant.ofEpochMilli(cutover.toEpochMilli()).toString()));
    }

    @Test public void deepGlobalPageHydratesOnlyRequestedRowsAndExportYieldsToQueuedWrites() throws Exception
    {
        manyFlips(1200);
        activate("A");
        ReportQuery deep = query(List.of("A"), ReportKind.FLIPS, null, null, "", Sort.TIME, null, 59, 20);
        ReportResult result = coordinator.queryReport(deep).join();
        assertEquals(1200, result.totalRows);
        assertEquals(20, result.rows.size());
        QueueExecutor executor = new QueueExecutor();
        AccountingCoordinator queued = new AccountingCoordinator(storage, executor, () -> true);
        Path target = folder.newFile("interrupted.csv").toPath();
        Files.writeString(target, "previous export");
        CompletableFuture<Path> export = queued.exportReport(deep.atRevision(result.sourceRevision, result.projectionRevision), target);
        executor.execute(() -> trade("A", 20, "during-export", true, 1, 10, 10000, "Another"));
        executor.next(); // First 500-row read; it queues the continuation behind the save.
        assertFalse(export.isDone());
        assertEquals(2, executor.tasks.size());
        executor.next(); // Live persistence runs before the second export read.
        executor.next();
        assertStale(export::join);
        assertEquals("previous export", Files.readString(target));
        try (java.util.stream.Stream<Path> files = Files.list(folder.getRoot().toPath())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".flipping-report-")));
        }
    }

    @Test public void successfulExportUsesFixedChunksWithoutHoldingTheStorageMonitor() throws Exception
    {
        manyFlips(1100); activate("A");
        QueueExecutor executor = new QueueExecutor();
        AccountingCoordinator queued = new AccountingCoordinator(storage, executor, () -> true);
        Path target = folder.newFile("complete.csv").toPath();
        CompletableFuture<Path> exported = queued.exportReport(query(List.of("A"), ReportKind.FLIPS, null, null,
            "", Sort.TIME, null, 0, 20), target);
        boolean[] checkpoint = {false};
        executor.execute(() -> checkpoint[0] = true);
        executor.next();
        assertFalse(exported.isDone());
        executor.next();
        assertTrue(checkpoint[0]);
        executor.next();
        assertFalse(exported.isDone());
        executor.next();
        assertEquals(target.toAbsolutePath(), exported.join());
        assertEquals(1101, Files.readAllLines(target).size());
    }

    private Segment comparison(Preview preview, String method, String period)
    {
        return preview.comparisons.stream().filter(segment -> segment.methodLabel.startsWith(method)
            && segment.periodLabel.startsWith(period)).findFirst().orElseThrow(AssertionError::new);
    }

    private void manyFlips(int count) throws Exception
    {
        storage.getConnection().setAutoCommit(false);
        try {
            for (int index = 0; index < count; index++) {
                trade("A", 10, "buy-" + index, true, 1, 100, index * 2, "Item");
                trade("A", 10, "sale-" + index, false, 1, 120, index * 2 + 1, "Item");
            }
            storage.getConnection().commit();
        } finally { storage.getConnection().setAutoCommit(true); }
    }

    private static final class QueueExecutor implements Executor
    {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        void next() { tasks.removeFirst().run(); }
    }

    private OfferEvent detached(String id, boolean buy, int item, int quantity, int price, long seconds)
    {
        return new OfferEvent(id, buy, item, quantity, price, START.plusSeconds(seconds), 0,
            buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD, 0, 10, quantity,
            null, false, "A", "Item " + item, price, price * quantity);
    }

    private void activate(String account)
    {
        coordinator.apply(coordinator.preview(request(account, Mode.RECALCULATE)).join()).join();
    }

    private PlanRequest request(String account, Mode mode)
    {
        return new PlanRequest(account, mode, null, null, ZoneOffset.UTC, Map.of(), null);
    }

    private void pair(String account, int item, int profit, String title)
    {
        trade(account, item, "buy-" + item, true, 1, 100, 10, title);
        trade(account, item, "sale-" + item, false, 1, 100 + profit, 20, title);
    }

    private OfferEvent trade(String account, int item, String id, boolean buy, int quantity, int price,
                             long seconds, String title)
    {
        OfferEvent offer = new OfferEvent(id, buy, item, quantity, price, START.plusSeconds(seconds), 0,
            buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD, 0, 10, quantity,
            null, false, account, title, price, price * quantity);
        storage.recordTrade(account, offer);
        return offer;
    }

    private ReportQuery query(List<String> accounts, ReportKind kind, Long from, Long to, String search,
                              Sort sort, String key, int page, int size)
    {
        return new ReportQuery(accounts, from == null ? null : START.plusSeconds(from),
            to == null ? null : START.plusSeconds(to), "Test period", search, sort, kind, key, page, size, null, null);
    }

    private void assertStale(Runnable action)
    {
        try { action.run(); fail("Expected stale preview/report"); }
        catch (CompletionException expected) { assertTrue(expected.getCause() instanceof StalePreviewException); }
    }
}
