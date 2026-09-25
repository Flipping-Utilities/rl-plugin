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
import java.math.BigDecimal;
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
    private final Map<List<Object>, LegacyReportingAdapter.LegacyReport> legacyCache = new LinkedHashMap<>();
    private final Map<String, SqliteAccountingStore.Preview> previews = new LinkedHashMap<>();

    public AccountingCoordinator(SqliteStorage storage, Executor worker, BooleanSupplier available) {
        this.storage = storage; this.store = storage.getAccountingStore();
        this.reports = new ReportingRepository(storage); this.worker = worker; this.available = available;
    }
    private <T> CompletableFuture<T> run(Supplier<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            if (!available.getAsBoolean()) throw new IllegalStateException("SQLite is unavailable. Reopen the report after storage recovers.");
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
        return run(() -> {
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
            ReportQuery all = new ReportQuery(Collections.singletonList(request.account), null, null, "All history", "",
                Sort.TIME, ReportKind.ITEMS, null, 0, 50, null, null);
            List<Segment> comparisons = new ArrayList<>();
            comparisons.add(legacy.calculate(request.account, namedSnapshot(storage.loadAccount(request.account)), all, "Current legacy item calculation").getSegment());
            ReportQuery allRecipes = new ReportQuery(all.accounts, null, null, "All history", "", Sort.TIME, ReportKind.RECIPES, null, 0, 50, null, null);
            comparisons.add(legacy.calculate(request.account, namedSnapshot(storage.loadAccount(request.account)), allRecipes, "Current legacy recipe calculation").getSegment());
            if (request.mode != AccountingPlan.Mode.LEGACY) {
                comparisons.add(summarize(request.account, "Proposed sale-time accounting", candidate.getResult().getRealizations()));
                if (request.mode == AccountingPlan.Mode.HYBRID) {
                    comparisons.add(legacy.calculate(request.account, namedSnapshot(store.loadFrozenLegacyAccount(plan.getId())), all,
                        "Frozen legacy items before " + request.cutover).getSegment());
                    comparisons.add(legacy.calculate(request.account, namedSnapshot(store.loadFrozenLegacyAccount(plan.getId())), allRecipes,
                        "Frozen legacy recipes before " + request.cutover).getSegment());
                }
            }
            long selected = 0;
            for (Long quantity : request.openingQuantities.values()) selected = Math.addExact(selected, quantity);
            List<Impact> impacts = Arrays.asList(
                new Impact("Opening units selected", selected, null, "Only confirmed quantities become available at the cutover."),
                new Impact("Persisted sale realizations", candidate.getResult().getRealizations().size(), null,
                    "Sales without supported purchase costs remain incomplete; they are never assigned zero cost."));
            List<String> warnings = new ArrayList<>(candidate.getResult().getWarnings());
            warnings.add("Legacy item totals and proposed sale-time totals can differ because period matching and recipe allocation change.");
            warnings.add("Estimated legacy amounts retain averaged prices. Historical execution details cannot be recovered.");
            return new Preview(plan.getId(), Long.toString(candidate.getSourceRevision()), request, opening, comparisons, impacts, warnings, true);
        });
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
            } catch (RuntimeException | SQLException failure) { connection.rollback(); throw failure; }
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
        int end = Math.multiplyExact(Math.addExact(query.page, 1), query.pageSize);
        List<Segment> segments = new ArrayList<>();
        List<ReportRow> rows = new ArrayList<>();
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
                    ReportingRepository.Page inventory = reports.inventory(id, account, plan.getId(), query, end);
                    segments.add(inventory.segment); rows.addAll(inventory.rows); count = Math.addExact(count, inventory.count);
                }
                continue;
            }
            if (query.archived || plan == null || plan.getMode() == AccountingPlan.Mode.LEGACY) {
                LegacyReportingAdapter.LegacyReport report = legacyReport(id, account, query, query.archived ? "Archived retained history (legacy)" : "Legacy accounting", null);
                segments.add(report.getSegment()); rows.addAll(report.getRows()); count += report.getRows().size(); warnings.addAll(report.getWarnings());
                continue;
            }
            if (store.isDirty(id)) throw new IllegalStateException("Accounting is awaiting reconciliation. The last report has not been replaced.");
            if (plan.getMode() == AccountingPlan.Mode.HYBRID && (query.fromInclusive == null || query.fromInclusive.isBefore(plan.getCutover()))) {
                Instant endLegacy = query.toExclusive == null || query.toExclusive.isAfter(plan.getCutover()) ? plan.getCutover() : query.toExclusive;
                ReportQuery before = withBounds(query, query.fromInclusive, endLegacy);
                LegacyReportingAdapter.LegacyReport report = legacyReport(id, account, before, "Frozen legacy accounting", plan.getId());
                segments.add(report.getSegment()); rows.addAll(report.getRows()); count += report.getRows().size(); warnings.addAll(report.getWarnings());
            }
            if (plan.getMode() == AccountingPlan.Mode.FRESH_START) warnings.add(account + ": Earlier history is archived. Select Archived history to view it separately.");
            ReportingRepository.Page page = reports.query(id, account, query, end, 0);
            Segment factSegment = page.segment;
            Instant activityFrom = query.fromInclusive;
            if (plan.getCutover() != null && (activityFrom == null || activityFrom.isBefore(plan.getCutover()))) activityFrom = plan.getCutover();
            Activity activity = reports.activity(id, plan.getId(), activityFrom, query.toExclusive);
            segments.add(new Segment(factSegment.accountLabel, factSegment.methodLabel, factSegment.periodLabel,
                factSegment.amounts, factSegment.flipCount, factSegment.soldQuantity, factSegment.unknownQuantity, activity,
                "Session".equals(query.periodLabel) && plan.getMode() != AccountingPlan.Mode.HYBRID ? reports.sessionMillis(id) : null)); rows.addAll(page.rows); count = Math.addExact(count, page.count);
            warnings.addAll(store.getWarnings(id));
            if (query.fromInclusive != null || query.toExclusive != null) {
                long undated = reports.undatedCount(id);
                if (undated > 0) warnings.add(account + ": " + undated + " undated sale records appear only in All history.");
            }
        }
        Sort effectiveSort = query.kind == ReportKind.INVENTORY && query.sort != Sort.QUANTITY ? Sort.TIME : query.sort;
        rows.sort(comparator(effectiveSort));
        int begin = Math.min(rows.size(), Math.multiplyExact(query.page, query.pageSize));
        int stop = Math.min(rows.size(), end);
        return new ReportResult(segments, rows.subList(begin, stop), count, revision[0], revision[1], new ArrayList<>(new LinkedHashSet<>(warnings)));
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
            return reports.details(ids, rowId);
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
    public static Comparator<ReportRow> comparator(Sort sort) {
        Comparator<ReportRow> result;
        switch (sort) {
            case PROFIT: result = Comparator.comparing(row -> decimal(row.amounts.profit.completeGp), Comparator.nullsLast(Comparator.reverseOrder())); break;
            case PROFIT_EACH: result = Comparator.comparing(row -> ratio(row.amounts.profit.completeGp, row.quantity), Comparator.nullsLast(Comparator.reverseOrder())); break;
            case ROI: result = Comparator.comparing(row -> ratio(row.amounts.profit.completeGp, row.amounts.cost.completeGp), Comparator.nullsLast(Comparator.reverseOrder())); break;
            case QUANTITY: result = Comparator.comparingLong((ReportRow row) -> row.quantity).reversed(); break;
            default: result = Comparator.comparing(row -> row.occurredAt, Comparator.nullsLast(Comparator.reverseOrder()));
        }
        return result.thenComparing(row -> row.id);
    }
    private static BigDecimal decimal(Long value) { return value == null ? null : BigDecimal.valueOf(value); }
    private static Double ratio(Long value, Long denominator) {
        // Match SQLite REAL ordering exactly when merging bounded per-account pages.
        // Financial values and displayed totals remain integer GP; only ratio sort keys use doubles.
        return value == null || denominator == null || denominator == 0 ? null : value.doubleValue() / denominator.doubleValue();
    }
    @Override public CompletableFuture<Path> exportReport(ReportQuery query, Path destination) {
        return run(() -> {
            Path temporary = null;
            try {
                Path target = destination.toAbsolutePath();
                temporary = Files.createTempFile(target.getParent(), ".flipping-report-", ".csv");
                try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
                     CSVPrinter csv = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
                    csv.printRecord("Account", "Method", "Period", "Kind", "Item or recipe", "Recognized at", "Quantity", "Flip groups",
                        "Profit gp", "Known profit subtotal gp", "Unknown profit rows", "Cost gp", "Gross gp", "Tax gp", "Net gp", "Estimated", "Source revision", "Projection revision");
                    int page = 0;
                    String source = query.sourceRevision, projection = query.projectionRevision;
                    long exported = 0;
                    while (true) {
                        ReportQuery next = new ReportQuery(query.accounts, query.fromInclusive, query.toExclusive, query.periodLabel,
                            query.search, query.sort, query.kind, query.groupKey, page++, 500, source, projection, query.archived);
                        ReportResult report = snapshot(() -> query(next));
                        source = report.sourceRevision; projection = report.projectionRevision;
                        for (ReportRow row : report.rows) csv.printRecord(row.accountLabel, row.methodLabel, query.periodLabel, query.kind,
                            row.title, row.occurredAt, row.quantity, row.count, row.amounts.profit.completeGp, row.amounts.profit.knownSubtotalGp,
                            row.amounts.profit.unknownCount, row.amounts.cost.completeGp, row.amounts.gross.completeGp, row.amounts.tax.completeGp,
                            row.amounts.net.completeGp, row.amounts.profit.estimated, source, projection);
                        exported += report.rows.size();
                        if (exported >= report.totalRows) break;
                        if (report.rows.isEmpty()) throw new IllegalStateException("Report pagination did not advance");
                    }
                }
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                return target;
            } catch (IOException e) { throw new IllegalStateException("Could not export report", e); }
            finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
        });
    }
    private static String modeLabel(AccountingPlan plan) {
        switch (plan.getMode()) {
            case LEGACY: return "Keep current calculations";
            case RECALCULATE: return "Recalculate all history";
            case HYBRID: return "Keep history; switch at " + plan.getCutover();
            default: return "Start fresh at " + plan.getCutover();
        }
    }
    private static Segment summarize(String account, String method, List<AccountingResult.Realization> rows) {
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
        return new Segment(account, method, "Selected accounting scope", new Amounts(profit.money(), cost.money(), gross.money(), tax.money(), net.money()), flips.size(), quantity, unknown);
    }
    private static final class Sum {
        long value, unknown; boolean estimated;
        void add(Long amount, boolean estimate) { if (amount == null) unknown++; else value = Math.addExact(value, amount); estimated |= estimate; }
        Money money() { return new Money(unknown == 0 ? value : null, value, unknown, estimated); }
    }
}
