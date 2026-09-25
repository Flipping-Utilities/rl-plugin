# Accounting reports

SQLite accounts keep their current calculations until a player reviews and applies a choice in **Statistics → Setup**:

- **Keep current calculations** retains the legacy period-based calculation.
- **Recalculate all history** recognizes profit when each sale happens, using eligible earlier purchases.
- **Keep history, switch from a date** freezes the earlier legacy view and starts sale-time accounting at the chosen instant. Reports label the two methods separately.
- **Start fresh from a date** excludes earlier activity from the new totals. **Archived history** still displays the retained history with legacy calculations.

Dates include a time zone. The new period includes its starting instant and excludes its ending instant. A preset purchase cutoff resolves to a fixed date when previewed.

## Opening stock

Dated plans begin with no opening purchases. The player can review purchases from the previous 7, 30 or 90 days, or enter a custom cutoff, and select quantities still owned. The cutoff applies after earlier sales and recipe consumption have been reconciled. It never resets a partially consumed purchase to its original quantity.

Hybrid plans conservatively exclude items sold anywhere in the frozen legacy history, as well as recipe-reserved quantities. Legacy period calculations can otherwise reuse a purchase that appears unconsumed in an all-history view. A fresh start or full recalculation supports broader reconciliation without combining that basis with preserved legacy results.

Editing quantities requires another preview. Applying a preview checks that its source revision is still current. Bulk setup produces separate previews and Apply buttons for each account; it does not assume stock is owned.

## Reading reports

Sale-time item, recipe and flip totals come from persisted financial rows. Search, sorting and pages use SQL. Earlier purchases remain eligible when a report covers only a later sale period. Inventory is a current balance of tracked purchase lots, not a bank inventory estimate.

Unknown amounts remain unknown. Reports distinguish complete amounts, known subtotals and estimated historical amounts. Legacy averages and per-unit tax reconstruction cannot recover missing execution details. A supported zero value is different from a missing value.

Recipes reserve their selected sources before ordinary matching. Input cost and signed coin adjustments are allocated across output sales in proportion to their gross proceeds. If all output proceeds are zero, the final output sale receives those costs. New recipe instances retain their definition and execution count; unavailable legacy definitions/counts remain labeled as such. **Sources and allocations** explains the underlying quantities and costs, including whole-flip activity outside the selected period.

Raw trade activity is shown separately from matched profit and includes all items in the account and period. Account and method segments are not combined into an unlabeled total. Session rates use recorded active session duration where a single method applies.

**Export CSV** exports the displayed report scope and revision. If trading changes that revision before export, refresh first. The existing raw-trade export retains its previous meaning.

## Persistence and recovery

The implementation uses the existing `sqlite-jdbc:3.45.1.0` dependency and the same serialized connection. It adds no dependencies. All accounting work runs on the ordered database worker.

The combined, unreleased schema remains version 1. An accounting layout marker and required-column checks reject incompatible layouts. An existing version-1 database receives a consistent SQLite backup before its first accounting extension.

Canonical observations retain cumulative amounts, predecessor identity and corrections. Source changes and dirty markers commit together; financial publication is transactional. Ordinary in-order appends use persisted open lots. Corrections and recipe changes replay only affected item partitions and their recipe dependencies. A large replay can delay queued database work; the game and Swing threads do not perform matching.

A projection failure keeps canonical trades and the last published report. Reports identify stale state. Richer history and reviewed plans are protected against the underlying storage branch's JSON regeneration paths. Reapplying an accounting choice rebuilds from retained SQLite sources; it never restores an older database.

## Validation

Run `./gradlew test`. The suite includes independent financial examples, randomized incremental/replay equivalence and conservation, real SQLite restart/rollback tests, stale-preview/export checks, recipe/source conflicts, and asynchronous UI tests.

An optional scaling fixture runs with:

```sh
FLIPPING_ACCOUNTING_BENCHMARK_SOURCES=10000 ./gradlew test --tests com.flippingutilities.db.AccountingBenchmarkTest --rerun-tasks
```

It prints source capture, preview/publication, report, append and replay timings, and verifies the date-report index. Set the source count to `100000` for the larger fixture. Timing measurements are diagnostic and are not brittle CI thresholds.
