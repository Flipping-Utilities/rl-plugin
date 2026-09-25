package com.flippingutilities.ui.accounting;

import com.flippingutilities.accounting.AccountingPlan.Mode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Presenter boundary. Implementations supply persisted results; Swing never calculates money. */
public interface AccountingUiService {
    CompletableFuture<PlanHistory> loadPlans(String account);
    CompletableFuture<Preview> preview(PlanRequest request);
    /** Must atomically reject previews whose source revision is no longer current. */
    CompletableFuture<String> apply(Preview preview);
    CompletableFuture<ReportResult> queryReport(ReportQuery query);
    default CompletableFuture<ReportDetails> queryDetails(ReportQuery query, String rowId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Details are unavailable"));
    }
    /** Export the captured query/revisions, or fail if that snapshot is no longer available. */
    CompletableFuture<Path> exportReport(ReportQuery query, Path destination);

    enum Sort {
        TIME("Most recent"), PROFIT("Total profit"), PROFIT_EACH("Profit per unit"),
        ROI("Return on investment"), QUANTITY("Quantity");
        private final String label;
        Sort(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum ReportKind { ITEMS, RECIPES, FLIPS, RECIPE_FLIPS, INVENTORY }

    final class PlanRequest {
        public final String account;
        public final Mode mode;
        public final Instant cutover;
        public final Instant purchaseCutoff;
        public final ZoneId displayZone;
        public final Map<String, Long> openingQuantities;
        /** A prior choice to rebuild/review, never an instruction to restore old database contents. */
        public final String priorPlanId;

        public PlanRequest(String account, Mode mode, Instant cutover, Instant purchaseCutoff,
                           ZoneId displayZone, Map<String, Long> openingQuantities, String priorPlanId) {
            this.account = Objects.requireNonNull(account);
            this.mode = Objects.requireNonNull(mode);
            this.cutover = cutover;
            this.purchaseCutoff = purchaseCutoff;
            this.displayZone = Objects.requireNonNull(displayZone);
            this.openingQuantities = Collections.unmodifiableMap(new LinkedHashMap<>(openingQuantities));
            this.priorPlanId = priorPlanId;
        }
    }

    final class SavedPlan {
        public final String id;
        public final String label;
        public final boolean active;
        public final PlanRequest choices;
        public SavedPlan(String id, String label, boolean active, PlanRequest choices) {
            this.id = id;
            this.label = label;
            this.active = active;
            this.choices = choices;
        }
        @Override public String toString() { return label + (active ? " (active)" : ""); }
    }

    final class PlanHistory {
        public final String activeDescription;
        public final List<SavedPlan> plans;
        public PlanHistory(String activeDescription, List<SavedPlan> plans) {
            this.activeDescription = activeDescription;
            this.plans = immutable(plans);
        }
    }

    final class OpeningCandidate {
        public final String sourceId;
        public final String itemName;
        public final Instant acquiredAt;
        public final long availableQuantity;
        public final Long availableCostGp;
        public final boolean estimated;
        /** Null/empty means eligible. Excluded rows cannot be selected. */
        public final String exclusionReason;
        public OpeningCandidate(String sourceId, String itemName, Instant acquiredAt, long availableQuantity,
                                Long availableCostGp, boolean estimated, String exclusionReason) {
            this.sourceId = sourceId;
            this.itemName = itemName;
            this.acquiredAt = acquiredAt;
            this.availableQuantity = availableQuantity;
            this.availableCostGp = availableCostGp;
            this.estimated = estimated;
            this.exclusionReason = exclusionReason;
        }
        public boolean eligible() {
            return availableQuantity > 0 && availableCostGp != null && acquiredAt != null
                && (exclusionReason == null || exclusionReason.isEmpty());
        }
    }

    final class Money {
        /** Null means incomplete, never zero. The known subtotal must be labeled as such. */
        public final Long completeGp;
        public final long knownSubtotalGp;
        public final long unknownCount;
        public final boolean estimated;
        public Money(Long completeGp, long knownSubtotalGp, long unknownCount, boolean estimated) {
            this.completeGp = completeGp;
            this.knownSubtotalGp = knownSubtotalGp;
            this.unknownCount = unknownCount;
            this.estimated = estimated;
        }
        public static Money known(long gp) { return new Money(gp, gp, 0, false); }
    }

    final class Amounts {
        public final Money profit;
        public final Money cost;
        public final Money gross;
        public final Money tax;
        public final Money net;
        public Amounts(Money profit, Money cost, Money gross, Money tax, Money net) {
            this.profit = profit;
            this.cost = cost;
            this.gross = gross;
            this.tax = tax;
            this.net = net;
        }
    }

    final class Segment {
        public final String accountLabel;
        public final String methodLabel;
        public final String periodLabel;
        public final Amounts amounts;
        public final long flipCount;
        public final long soldQuantity;
        public final long unknownQuantity;
        public final Activity activity;
        /** Tracked session duration, absent when an hourly comparison is not meaningful. */
        public final Long sessionMillis;
        public Segment(String accountLabel, String methodLabel, String periodLabel, Amounts amounts,
                       long flipCount, long soldQuantity, long unknownQuantity) {
            this(accountLabel, methodLabel, periodLabel, amounts, flipCount, soldQuantity, unknownQuantity, null);
        }
        public Segment(String accountLabel, String methodLabel, String periodLabel, Amounts amounts,
                       long flipCount, long soldQuantity, long unknownQuantity, Activity activity) {
            this(accountLabel, methodLabel, periodLabel, amounts, flipCount, soldQuantity, unknownQuantity, activity, null);
        }
        public Segment(String accountLabel, String methodLabel, String periodLabel, Amounts amounts,
                       long flipCount, long soldQuantity, long unknownQuantity, Activity activity, Long sessionMillis) {
            this.accountLabel = accountLabel;
            this.methodLabel = methodLabel;
            this.periodLabel = periodLabel;
            this.amounts = amounts;
            this.flipCount = flipCount;
            this.soldQuantity = soldQuantity;
            this.unknownQuantity = unknownQuantity;
            this.activity = activity;
            this.sessionMillis = sessionMillis;
        }
    }

    final class Activity {
        public final long boughtQuantity;
        public final long soldQuantity;
        public final Money spent;
        public final Money proceeds;
        public final Money tax;
        public Activity(long boughtQuantity, long soldQuantity, Money spent, Money proceeds, Money tax) {
            this.boughtQuantity = boughtQuantity;
            this.soldQuantity = soldQuantity;
            this.spent = spent;
            this.proceeds = proceeds;
            this.tax = tax;
        }
    }

    final class Impact {
        public final String label;
        public final long quantity;
        public final Long amountGp;
        public final String explanation;
        public Impact(String label, long quantity, Long amountGp, String explanation) {
            this.label = label;
            this.quantity = quantity;
            this.amountGp = amountGp;
            this.explanation = explanation;
        }
    }

    final class Preview {
        public final String id;
        public final String sourceRevision;
        public final PlanRequest request;
        public final List<OpeningCandidate> candidates;
        public final List<Segment> comparisons;
        public final List<Impact> impacts;
        public final List<String> warnings;
        public final boolean canApply;
        public Preview(String id, String sourceRevision, PlanRequest request,
                       List<OpeningCandidate> candidates, List<Segment> comparisons,
                       List<Impact> impacts, List<String> warnings, boolean canApply) {
            this.id = id;
            this.sourceRevision = sourceRevision;
            this.request = request;
            this.candidates = immutable(candidates);
            this.comparisons = immutable(comparisons);
            this.impacts = immutable(impacts);
            this.warnings = immutable(warnings);
            this.canApply = canApply;
        }
    }

    final class ReportQuery {
        public final List<String> accounts;
        /** Null bounds mean all retained history, including undated facts. Otherwise [from, to). */
        public final Instant fromInclusive;
        public final Instant toExclusive;
        public final String periodLabel;
        public final String search;
        public final Sort sort;
        public final ReportKind kind;
        public final String groupKey;
        /** Zero-based page index; the visible page control is one-based. */
        public final int page;
        public final int pageSize;
        public final String sourceRevision;
        public final String projectionRevision;
        public final boolean archived;
        public ReportQuery(List<String> accounts, Instant fromInclusive, Instant toExclusive,
                           String periodLabel, String search, Sort sort, ReportKind kind,
                           String groupKey, int page, int pageSize, String sourceRevision,
                           String projectionRevision) {
            this(accounts, fromInclusive, toExclusive, periodLabel, search, sort, kind, groupKey, page, pageSize,
                sourceRevision, projectionRevision, false);
        }
        public ReportQuery(List<String> accounts, Instant fromInclusive, Instant toExclusive,
                           String periodLabel, String search, Sort sort, ReportKind kind,
                           String groupKey, int page, int pageSize, String sourceRevision,
                           String projectionRevision, boolean archived) {
            this.accounts = immutable(accounts);
            this.fromInclusive = fromInclusive;
            this.toExclusive = toExclusive;
            this.periodLabel = periodLabel;
            this.search = search;
            this.sort = sort;
            this.kind = kind;
            this.groupKey = groupKey;
            this.page = page;
            this.pageSize = pageSize;
            this.sourceRevision = sourceRevision;
            this.projectionRevision = projectionRevision;
            this.archived = archived;
        }
        public ReportQuery atRevision(String source, String projection) {
            return new ReportQuery(accounts, fromInclusive, toExclusive, periodLabel, search, sort,
                kind, groupKey, page, pageSize, source, projection, archived);
        }
    }

    final class ReportDetails {
        public final String title;
        public final List<String> lines;
        public ReportDetails(String title, List<String> lines) {
            this.title = title;
            this.lines = immutable(lines);
        }
    }

    final class ReportRow {
        public final String id;
        public final String accountLabel;
        public final String methodLabel;
        public final String title;
        public final String description;
        public final Amounts amounts;
        public final long quantity;
        public final long count;
        /** Null means this is already a detail row. */
        public final ReportKind detailKind;
        public final String detailKey;
        public final Instant occurredAt;
        public ReportRow(String id, String accountLabel, String methodLabel, String title,
                         String description, Amounts amounts, long quantity, long count,
                         ReportKind detailKind, String detailKey) {
            this(id, accountLabel, methodLabel, title, description, amounts, quantity, count, detailKind, detailKey, null);
        }
        public ReportRow(String id, String accountLabel, String methodLabel, String title,
                         String description, Amounts amounts, long quantity, long count,
                         ReportKind detailKind, String detailKey, Instant occurredAt) {
            this.id = id;
            this.accountLabel = accountLabel;
            this.methodLabel = methodLabel;
            this.title = title;
            this.description = description;
            this.amounts = amounts;
            this.quantity = quantity;
            this.count = count;
            this.detailKind = detailKind;
            this.detailKey = detailKey;
            this.occurredAt = occurredAt;
        }
    }

    final class ReportResult {
        public final List<Segment> segments;
        public final List<ReportRow> rows;
        public final long totalRows;
        public final String sourceRevision;
        public final String projectionRevision;
        public final List<String> warnings;
        public ReportResult(List<Segment> segments, List<ReportRow> rows, long totalRows,
                            String sourceRevision, String projectionRevision, List<String> warnings) {
            this.segments = immutable(segments);
            this.rows = immutable(rows);
            this.totalRows = totalRows;
            this.sourceRevision = sourceRevision;
            this.projectionRevision = projectionRevision;
            this.warnings = immutable(warnings);
        }
    }

    final class StalePreviewException extends RuntimeException {
        public StalePreviewException(String message) { super(message); }
    }

    static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
