package com.flippingutilities.controller.accounting;

import com.flippingutilities.accounting.*;
import com.flippingutilities.db.ReportingRepository;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.accounting.SqliteAccountingStore;
import com.flippingutilities.ui.accounting.AccountingUiService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import java.io.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Coordinates one ordered SQLite worker and immutable UI results. */
public final class AccountingCoordinator implements AccountingUiService {
    private final SqliteStorage storage;
    private final SqliteAccountingStore store;
    private final ReportingRepository reports;
    private final LegacyReportingAdapter legacy = new LegacyReportingAdapter();
    private final Executor worker;
    private final BooleanSupplier available;
    private final Supplier<CompletableFuture<String>> retryStorage;
    private final Map<List<Object>, LegacyReportingAdapter.LegacyReport> legacyCache = new LinkedHashMap<>();
    private final Map<String, SqliteAccountingStore.Preview> previews = new LinkedHashMap<>();

    public AccountingCoordinator(SqliteStorage storage, Executor worker, BooleanSupplier available) {
        this(storage, worker, available, () -> CompletableFuture.failedFuture(new IllegalStateException("Storage retry is unavailable.")));
    }
    public AccountingCoordinator(SqliteStorage storage, Executor worker, BooleanSupplier available, Supplier<CompletableFuture<String>> retryStorage) {
        this.storage = storage; this.store = storage.getAccountingStore();
        this.reports = new ReportingRepository(storage); this.worker = worker; this.available = available;
        this.retryStorage = retryStorage;
    }
    @Override public CompletableFuture<String> retryStorage() { return retryStorage.get(); }
    private <T> CompletableFuture<T> run(Supplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            if (!available.getAsBoolean()) throw new IllegalStateException("SQLite saves failed. Trading changes remain pending in memory; use Retry saves before closing the client.");
            synchronized (storage) { return action.get(); }
        }, worker);
    }
    private long accountId(String account) {
        Long id = store.findAccountId(account);
        if (id == null) throw new IllegalArgumentException("Account is no longer available: " + account);
        return id;
    }
    @Override public CompletableFuture<PlanHistory> loadPlans(String account) {
        return run(() -> {
            long id = accountId(account);
            AccountingPlan active = store.getActivePlan(id);
            List<SavedPlan> choices = new ArrayList<>();
            for (AccountingPlan plan : store.listPlans(id)) {
                choices.add(new SavedPlan(plan.getId(), modeLabel(plan), active != null && plan.getId().equals(active.getId()),
                    new PlanRequest(account, plan.getMode(), plan.getCutover(), plan.getPurchaseCutoff(), ZoneId.systemDefault(),
                        plan.getOpeningQuantities(), plan.getId())));
            }
            return new PlanHistory(active == null ? "Keep current calculations" : modeLabel(active), choices);
        });
    }
    @Override public CompletableFuture<Preview> preview(PlanRequest request) {
        return run(() -> snapshot(() -> {
            long account = accountId(request.account);
            AccountingPlan plan = new AccountingPlan(UUID.randomUUID().toString(), account, request.mode,
                request.cutover, request.purchaseCutoff, request.openingQuantities);
            SqliteAccountingStore.Preview candidate = store.preview(plan);
            // Activation recomputes from retained sources; do not retain entire financial histories for every preview.
            previews.put(plan.getId(), new SqliteAccountingStore.Preview(plan, candidate.getSourceRevision(), null, candidate.getDigest()));
            while (previews.size() > 20) previews.remove(previews.keySet().iterator().next());
            Map<Integer, String> names = reports.itemNames();
            List<OpeningCandidate> opening = new ArrayList<>();
            for (AccountingResult.OpeningCandidate item : candidate.getResult().getOpeningCandidates()) {
                opening.add(new OpeningCandidate(item.getSourceId(), names.getOrDefault(item.getItemId(), "Item " + item.getItemId()),
                    item.getAcquiredAt(), item.getQuantity(), item.getCostGp(), item.isEstimated(), item.getExclusionReason()));
            }
            Instant asOf = Instant.now();
            List<Segment> comparisons = previewComparisons(request, candidate, asOf);
            long selected = 0;
            for (Long quantity : request.openingQuantities.values()) selected = Math.addExact(selected, quantity);
            List<Impact> impacts = Arrays.asList(
                new Impact("Opening units selected", selected, null, "Only confirmed quantities become available at the cutover."),
                new Impact("Persisted sale realizations", candidate.getResult().getRealizations().size(), null,
                    "Sales without supported purchase costs remain incomplete; they are never assigned zero cost."));
            List<String> warnings = new ArrayList<>(candidate.getResult().getWarnings());
            AccountingPlan active = store.getActivePlan(account);
            if (active != null && active.getMode() != AccountingPlan.Mode.LEGACY && store.isDirty(account)) {
                warnings.add("The active plan is awaiting reconciliation; its current comparison is unavailable at source revision "
                    + candidate.getSourceRevision() + ". The proposed plan uses that captured revision.");
            }
            warnings.add("Legacy item totals and proposed sale-time totals can differ because period matching and recipe allocation change.");
            warnings.add("Estimated legacy amounts retain averaged prices. Historical execution details cannot be recovered.");
            return new Preview(plan.getId(), Long.toString(candidate.getSourceRevision()), request, opening, comparisons, impacts, warnings, true);
        }));
    }

    private List<Segment> previewComparisons(PlanRequest request, SqliteAccountingStore.Preview candidate, Instant asOf) {
        long account = candidate.getPlan().getAccountId();
        AccountingPlan active = store.getActivePlan(account);
        AccountingPlan saved = null;
        if (request.priorPlanId != null) {
            saved = store.listPlans(account).stream().filter(plan -> plan.getId().equals(request.priorPlanId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The selected saved accounting plan is no longer available"));
        }
        List<Segment> comparisons = new ArrayList<>();
        List<ReportQuery> periods = Arrays.asList(previewPeriod(request.account, "All history", null, null),
            previewPeriod(request.account, "Last 24 hours", asOf.minus(Duration.ofHours(24)), asOf),
            previewPeriod(request.account, "Last 30 days", asOf.minus(Duration.ofDays(30)), asOf));
        for (ReportQuery period : periods) {
            if (active != null && active.getMode() != AccountingPlan.Mode.LEGACY && store.isDirty(account)) {
                Money unknown = new Money(null, 0, 1, false);
                comparisons.add(new Segment(request.account, "Current " + modeLabel(active) + " (pending reconciliation)",
                    period.periodLabel, new Amounts(unknown,unknown,unknown,unknown,unknown),0,0,0));
            } else {
                comparisons.addAll(planComparisons(request.account, active, period, "Current", null));
            }
            comparisons.addAll(planComparisons(request.account, candidate.getPlan(), period, "Proposed", candidate.getResult()));
            if (saved != null && saved.getMode() == AccountingPlan.Mode.HYBRID) {
                Instant end = earlier(period.toExclusive, saved.getCutover());
                if (period.fromInclusive == null || period.fromInclusive.isBefore(end)) {
                    ReportQuery frozenPeriod = withBounds(period, period.fromInclusive, end);
                    comparisons.add(legacyComparison(request.account, store.loadFrozenLegacyAccount(saved.getId()), frozenPeriod,
                        "Saved plan " + saved.getId() + " frozen legacy"));
                }
            }
        }
        return comparisons;
    }

    private List<Segment> planComparisons(String account, AccountingPlan plan, ReportQuery period,
                                          String label, AccountingResult candidate) {
        List<Segment> result = new ArrayList<>();
        if (plan == null || plan.getMode() == AccountingPlan.Mode.LEGACY) {
            result.add(legacyComparison(account, storage.loadAccount(account), period, label + " legacy accounting"));
            return result;
        }
        if (plan.getMode() == AccountingPlan.Mode.HYBRID) {
            Instant end = earlier(period.toExclusive, plan.getCutover());
            if (period.fromInclusive == null || period.fromInclusive.isBefore(end)) {
                result.add(legacyComparison(account, store.loadFrozenLegacyAccount(plan.getId()),
                    withBounds(period, period.fromInclusive, end), label + " frozen legacy accounting"));
            }
        }
        Instant from = period.fromInclusive;
        if (plan.getCutover() != null && (from == null || from.isBefore(plan.getCutover()))) from = plan.getCutover();
        if (from != null && period.toExclusive != null && !from.isBefore(period.toExclusive)) return result;
        ReportQuery newPeriod = withBounds(period, from, period.toExclusive);
        String method = label + " sale-time accounting (" + modeLabel(plan) + ")";
        if (candidate != null) {
            List<AccountingResult.Realization> rows = new ArrayList<>();
            for (AccountingResult.Realization row : candidate.getRealizations()) {
                Instant time = row.getRecognizedAt();
                if (time == null ? newPeriod.fromInclusive == null && newPeriod.toExclusive == null
                    : (newPeriod.fromInclusive == null || !time.isBefore(newPeriod.fromInclusive))
                        && (newPeriod.toExclusive == null || time.isBefore(newPeriod.toExclusive))) rows.add(row);
            }
            result.add(summarize(account, method, boundsLabel(newPeriod, false), rows));
        } else {
            Segment items = reports.query(plan.getAccountId(), account, newPeriod, 1, 0).segment;
            ReportQuery recipes = withKind(newPeriod, ReportKind.RECIPES);
            Segment recipe = reports.query(plan.getAccountId(), account, recipes, 1, 0).segment;
            result.add(combine(items, recipe, method, boundsLabel(newPeriod, false)));
        }
        return result;
    }

    private Segment legacyComparison(String account, com.flippingutilities.model.AccountData snapshot,
                                      ReportQuery period, String method) {
        snapshot = namedSnapshot(snapshot);
        Segment items = legacy.calculate(account, snapshot, period, method).getSegment();
        Segment recipes = legacy.calculate(account, snapshot, withKind(period, ReportKind.RECIPES), method).getSegment();
        return combine(items, recipes, method, boundsLabel(period, true));
    }
    private ReportQuery previewPeriod(String account, String label, Instant from, Instant to) {
        return new ReportQuery(Collections.singletonList(account), from, to, label, "", Sort.TIME,
            ReportKind.ITEMS, null, 0, 1, null, null);
    }
    private static ReportQuery withKind(ReportQuery query, ReportKind kind) {
        return new ReportQuery(query.accounts, query.fromInclusive, query.toExclusive, query.periodLabel, query.search,
            query.sort, kind, query.groupKey, query.page, query.pageSize, query.sourceRevision, query.projectionRevision, query.archived);
    }
    private static Instant earlier(Instant left, Instant right) { return left == null || left.isAfter(right) ? right : left; }
    private String boundsLabel(ReportQuery period, boolean legacy) {
        return period.periodLabel + " " + (legacy ? "(" : "[")
            + (period.fromInclusive == null ? "unbounded" : period.fromInclusive) + ", "
            + (period.toExclusive == null ? "unbounded" : period.toExclusive) + ") UTC"
            + (period.fromInclusive == null && period.toExclusive == null ? "; undated records disclosed separately" : "");
    }
    private Segment combine(Segment left, Segment right, String method, String period) {
        Amounts a = left.amounts, b = right.amounts;
        return new Segment(left.accountLabel, method, period,
            new Amounts(combine(a.profit,b.profit),combine(a.cost,b.cost),combine(a.gross,b.gross),
                combine(a.tax,b.tax),combine(a.net,b.net)), Math.addExact(left.flipCount,right.flipCount),
            Math.addExact(left.soldQuantity,right.soldQuantity),Math.addExact(left.unknownQuantity,right.unknownQuantity));
    }
    private Money combine(Money left, Money right) {
        long sum = Math.addExact(left.knownSubtotalGp,right.knownSubtotalGp);
        long unknown = Math.addExact(left.unknownCount,right.unknownCount);
        return new Money(left.completeGp == null || right.completeGp == null ? null : sum,sum,unknown,left.estimated || right.estimated);
    }
    @Override public CompletableFuture<String> apply(Preview preview) {
        return run(() -> {
            SqliteAccountingStore.Preview candidate = previews.get(preview.id);
            if (candidate == null || !Long.toString(candidate.getSourceRevision()).equals(preview.sourceRevision)) {
                throw new StalePreviewException("Preview expired. Review a new preview before applying.");
            }
            if (store.getSourceRevision(candidate.getPlan().getAccountId()) != candidate.getSourceRevision()) {
                throw new StalePreviewException("Trading data changed. Review a new preview before applying.");
            }
            store.activate(candidate);
            return modeLabel(candidate.getPlan());
        });
    }
    @Override public CompletableFuture<ReportResult> queryReport(ReportQuery query) { return run(() -> snapshot(() -> query(query))); }

    private <T> T snapshot(Supplier<T> read) {
        try {
            Connection connection = storage.getConnection();
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) return read.get();
            connection.setAutoCommit(false);
            try {
                T value = read.get(); connection.commit(); return value;
            } catch (RuntimeException | SQLException failure) {
                reports.invalidateLegacyStage(); connection.rollback(); throw failure;
            }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException failure) { throw new IllegalStateException("Could not read a consistent report snapshot", failure); }
    }

    private ReportResult query(ReportQuery query) {
        if (query.page < 0 || query.pageSize < 1 || query.pageSize > 500) throw new IllegalArgumentException("Invalid report page");
        if (query.fromInclusive != null && query.toExclusive != null && !query.fromInclusive.isBefore(query.toExclusive)) {
            throw new IllegalArgumentException("Report end must follow its start");
        }
        Map<Long, String> accounts = store.listAccounts();
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, String> account : accounts.entrySet()) if (query.accounts.contains(account.getValue())) ids.add(account.getKey());
        String[] revision = reports.revisions(ids);
        if ((query.sourceRevision != null && !query.sourceRevision.equals(revision[0])) ||
            (query.projectionRevision != null && !query.projectionRevision.equals(revision[1]))) {
            throw new StalePreviewException("Report changed. Refresh before continuing or exporting.");
        }
        int offset = Math.multiplyExact(query.page, query.pageSize);
        List<Segment> segments = new ArrayList<>();
        List<List<ReportRow>> legacyRows = new ArrayList<>();
        Map<Long, String> newAccounts = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        long count = 0;
        for (Long id : ids) {
            String account = accounts.get(id);
            AccountingPlan plan = store.getActivePlan(id);
            if (query.kind == ReportKind.INVENTORY) {
                if (query.archived || plan == null || plan.getMode() == AccountingPlan.Mode.LEGACY) {
                    warnings.add(account + ": Tracked inventory is available after applying sale-time accounting.");
                } else {
                    if (store.isDirty(id)) throw new IllegalStateException("Inventory is awaiting reconciliation. Refresh after accounting completes.");
                    ReportingRepository.Page inventory = reports.inventory(id, account, plan.getId(), query, 1);
                    segments.add(inventory.segment); newAccounts.put(id, account); count = Math.addExact(count, inventory.count);
                }
                continue;
            }
            if (query.archived || plan == null || plan.getMode() == AccountingPlan.Mode.LEGACY) {
                LegacyReportingAdapter.LegacyReport report = legacyReport(id, account, query, query.archived ? "Archived retained history (legacy)" : "Legacy accounting", null);
                segments.add(report.getSegment()); legacyRows.add(report.getRows()); count += report.getRows().size(); warnings.addAll(report.getWarnings());
                continue;
            }
            if (store.isDirty(id)) throw new IllegalStateException("Accounting is awaiting reconciliation. The last report has not been replaced.");
            if (plan.getMode() == AccountingPlan.Mode.HYBRID && (query.fromInclusive == null || query.fromInclusive.isBefore(plan.getCutover()))) {
                Instant endLegacy = query.toExclusive == null || query.toExclusive.isAfter(plan.getCutover()) ? plan.getCutover() : query.toExclusive;
                ReportQuery before = withBounds(query, query.fromInclusive, endLegacy);
                LegacyReportingAdapter.LegacyReport report = legacyReport(id, account, before, "Frozen legacy accounting", plan.getId());
                segments.add(report.getSegment()); legacyRows.add(report.getRows()); count += report.getRows().size(); warnings.addAll(report.getWarnings());
            }
            if (plan.getMode() == AccountingPlan.Mode.FRESH_START) warnings.add(account + ": Earlier history is archived. Select Archived history to view it separately.");
            ReportingRepository.Page page = reports.query(id, account, query, 1, 0);
            newAccounts.put(id, account);
            Segment factSegment = page.segment;
            Instant activityFrom = query.fromInclusive;
            if (plan.getCutover() != null && (activityFrom == null || activityFrom.isBefore(plan.getCutover()))) activityFrom = plan.getCutover();
            Activity activity = reports.activity(id, plan.getId(), activityFrom, query.toExclusive);
            segments.add(new Segment(factSegment.accountLabel, factSegment.methodLabel, factSegment.periodLabel,
                factSegment.amounts, factSegment.flipCount, factSegment.soldQuantity, factSegment.unknownQuantity, activity,
                "Session".equals(query.periodLabel) && plan.getMode() != AccountingPlan.Mode.HYBRID ? reports.sessionMillis(id) : null)); count = Math.addExact(count, page.count);
            warnings.addAll(store.getWarnings(id));
            if (query.fromInclusive != null || query.toExclusive != null) {
                long undated = reports.undatedCount(id);
                if (undated > 0) warnings.add(account + ": " + undated + " undated sale records appear only in All history.");
            }
        }
        List<ReportRow> rows = reports.globalPage(newAccounts, query, legacyRows, query.pageSize, offset);
        return new ReportResult(segments, rows, count, revision[0], revision[1], new ArrayList<>(new LinkedHashSet<>(warnings)));
    }
    private LegacyReportingAdapter.LegacyReport legacyReport(long id, String account, ReportQuery query, String method, String frozenPlan) {
        List<Object> key = Arrays.asList(id, store.getSourceRevision(id), frozenPlan, query.fromInclusive, query.toExclusive,
            query.search, query.kind, query.groupKey, query.periodLabel, method);
        LegacyReportingAdapter.LegacyReport cached = legacyCache.get(key);
        if (cached != null) return cached;
        LegacyReportingAdapter.LegacyReport report = legacy.calculate(account,
            namedSnapshot(frozenPlan == null ? storage.loadAccount(account) : store.loadFrozenLegacyAccount(frozenPlan)), query, method);
        legacyCache.put(key, report);
        while (legacyCache.size() > 32) legacyCache.remove(legacyCache.keySet().iterator().next());
        return report;
    }

    @Override public CompletableFuture<ReportDetails> queryDetails(ReportQuery query, String rowId) {
        return queryDetails(query, rowId, 0);
    }
    @Override public CompletableFuture<ReportDetails> queryDetails(ReportQuery query, String rowId, int page) {
        if (page < 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid detail page"));
        return run(() -> snapshot(() -> {
            List<Long> ids = new ArrayList<>();
            for (Map.Entry<Long, String> account : store.listAccounts().entrySet()) if (query.accounts.contains(account.getValue())) ids.add(account.getKey());
            String[] revision = reports.revisions(ids);
            if (!Objects.equals(query.sourceRevision, revision[0]) || !Objects.equals(query.projectionRevision, revision[1])) {
                throw new StalePreviewException("Report changed. Refresh before opening its sources.");
            }
            if (!rowId.startsWith("new:") && !rowId.startsWith("inventory:")) return new ReportDetails("Legacy calculation", Arrays.asList(
                "Legacy flips are calculated from the offers inside the selected period.",
                "They have no persisted source allocation. Recalculate history to inspect sale-time allocations."));
            return reports.details(ids, rowId, page);
        }));
    }

    private com.flippingutilities.model.AccountData namedSnapshot(com.flippingutilities.model.AccountData snapshot) {
        Map<Integer, String> names = reports.itemNames();
        snapshot.getTrades().forEach(item -> {
            String name = names.get(item.getItemId());
            if (name != null) item.setItemName(name);
        });
        return snapshot;
    }
    private static ReportQuery withBounds(ReportQuery query, Instant from, Instant to) {
        return new ReportQuery(query.accounts, from, to, query.periodLabel, query.search, query.sort, query.kind, query.groupKey,
            query.page, query.pageSize, query.sourceRevision, query.projectionRevision, query.archived);
    }
    @Override public CompletableFuture<Path> exportReport(ReportQuery query, Path destination) {
        Export export = new Export(query, destination.toAbsolutePath());
        scheduleExport(export);
        return export.result;
    }
    private void scheduleExport(Export export) {
        try { worker.execute(() -> exportChunk(export)); }
        catch (RuntimeException failure) { failExport(export, failure); }
    }
    private void exportChunk(Export export) {
        try {
            if (!available.getAsBoolean()) throw new IllegalStateException("SQLite is unavailable; retry the export after saves recover.");
            if (export.csv == null) {
                export.temporary = Files.createTempFile(export.target.getParent(), ".flipping-report-", ".csv");
                export.writer = Files.newBufferedWriter(export.temporary, StandardCharsets.UTF_8);
                export.csv = new CSVPrinter(export.writer, CSVFormat.DEFAULT);
                export.csv.printRecord("Account", "Method", "Period", "Kind", "Item or recipe", "Recognized at", "Quantity", "Flip groups",
                    "Profit gp", "Known profit subtotal gp", "Unknown profit rows", "Cost gp", "Gross gp", "Tax gp", "Net gp", "Estimated", "Source revision", "Projection revision");
            }
            ReportQuery original = export.query;
            ReportQuery next = new ReportQuery(original.accounts, original.fromInclusive, original.toExclusive, original.periodLabel,
                original.search, original.sort, original.kind, original.groupKey, export.page++, 500,
                export.source, export.projection, original.archived);
            ReportResult report;
            synchronized (storage) { report = snapshot(() -> query(next)); }
            export.source = report.sourceRevision; export.projection = report.projectionRevision;
            for (ReportRow row : report.rows) export.csv.printRecord(row.accountLabel, row.methodLabel, original.periodLabel, original.kind,
                row.title, row.occurredAt, row.quantity, row.count, row.amounts.profit.completeGp, row.amounts.profit.knownSubtotalGp,
                row.amounts.profit.unknownCount, row.amounts.cost.completeGp, row.amounts.gross.completeGp, row.amounts.tax.completeGp,
                row.amounts.net.completeGp, row.amounts.profit.estimated, export.source, export.projection);
            export.exported += report.rows.size();
            if (export.exported >= report.totalRows) {
                export.csv.close(); export.csv = null; export.writer = null;
                try { Files.move(export.temporary, export.target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(export.temporary, export.target, StandardCopyOption.REPLACE_EXISTING); }
                export.result.complete(export.target);
            } else {
                if (report.rows.isEmpty()) throw new IllegalStateException("Report pagination did not advance");
                // Rejoin the ordered executor after this fixed-size chunk, so queued saves get a turn.
                // A source/projection change makes the next chunk fail rather than mix revisions.
                scheduleExport(export);
            }
        } catch (IOException | RuntimeException failure) { failExport(export, failure); }
    }
    private void failExport(Export export, Throwable failure) {
        if (export.writer != null) try { export.writer.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        if (export.temporary != null) try { Files.deleteIfExists(export.temporary); } catch (IOException deleteFailure) { failure.addSuppressed(deleteFailure); }
        export.result.completeExceptionally(failure);
    }
    private static final class Export {
        final ReportQuery query;
        final Path target;
        final CompletableFuture<Path> result = new CompletableFuture<>();
        Path temporary;
        BufferedWriter writer;
        CSVPrinter csv;
        String source, projection;
        int page;
        long exported;
        Export(ReportQuery query, Path target) {
            this.query = query; this.target = target;
            source = query.sourceRevision; projection = query.projectionRevision;
        }
    }
    private static String modeLabel(AccountingPlan plan) {
        switch (plan.getMode()) {
            case LEGACY: return "Keep current calculations";
            case RECALCULATE: return "Recalculate all history";
            case HYBRID: return "Keep history; switch at " + plan.getCutover();
            default: return "Start fresh at " + plan.getCutover();
        }
    }
    private static Segment summarize(String account, String method, String period, List<AccountingResult.Realization> rows) {
        long quantity = 0, unknown = 0;
        Set<String> flips = new HashSet<>();
        Sum profit = new Sum(), cost = new Sum(), gross = new Sum(), tax = new Sum(), net = new Sum();
        for (AccountingResult.Realization row : rows) {
            quantity = Math.addExact(quantity, row.getQuantity()); flips.add(row.getFlipId());
            if (row.getProfitGp() == null) unknown = Math.addExact(unknown, row.getQuantity());
            profit.add(row.getProfitGp(), row.isEstimated());
            cost.add(row.getCostGp() == null || row.getAdjustmentGp() == null ? null : Math.addExact(row.getCostGp(), row.getAdjustmentGp()), row.isEstimated());
            gross.add(row.getGrossGp(), row.isEstimated()); tax.add(row.getTaxGp(), row.isEstimated()); net.add(row.getNetGp(), row.isEstimated());
        }
        return new Segment(account, method, period, new Amounts(profit.money(), cost.money(), gross.money(), tax.money(), net.money()), flips.size(), quantity, unknown);
    }
    private static final class Sum {
        long value, unknown; boolean estimated;
        void add(Long amount, boolean estimate) { if (amount == null) unknown++; else value = Math.addExact(value, amount); estimated |= estimate; }
        Money money() { return new Money(unknown == 0 ? value : null, value, unknown, estimated); }
    }
}
