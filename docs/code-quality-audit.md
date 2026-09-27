# Code quality, performance and maintainability audit

Audited 2026-09-25 against SQLite PR [#84](https://github.com/Flipping-Utilities/rl-plugin/pull/84), commit `3c2df744b1cf8b64863b1eed7ddeb0966537e139`, and the accounting/reporting plan in [#85](https://github.com/Flipping-Utilities/rl-plugin/pull/85). The UI follow-up also inspected the implemented accounting/reporting work in [#94](https://github.com/Flipping-Utilities/rl-plugin/pull/94), commit `e0209a39c5b8fef443d924d0d8c64d56739af055`. Build-only changes also target `master` at `62a8f13ce3592d56355b4dc8d85f7e2bbf6301e6`. Initial findings describe that baseline; the progress sections also cover the linked implementation follow-ups. UI source and repository screenshots were inspected; component renders accompany the UI follow-up. This is not a live gameplay or screen-reader audit.

## Recommended order

1. Add automated builds and reliable test discovery, then land the bounded request, resource-lifecycle and sidebar correctness fixes.
2. Keep SQLite migration/recovery review separate from account-load optimization. Batch recipe reads without changing schema or accounting semantics.
3. Review and integrate #94's persisted accounting and SQL reports through #85's explicit migration choices. The implementation now exists; prioritize migration/recovery validation and measured responsiveness of the complete UI stack.
4. Define thread ownership and plugin lifecycle cleanup before further expanding background jobs. Measure end-to-end reporting before adding caches or more indexes.

## Implemented follow-ups

| Topic | Baseline problem | Change and evidence | Dependency |
| --- | --- | --- | --- |
| [Build and test discovery (#88)](https://github.com/Flipping-Utilities/rl-plugin/pull/88) | No checked-in CI; `TestRunner` manually lists two classes and duplicates them when Gradle also discovers individual tests. | Java 11 builds on Linux/macOS/Windows, wrapper checksum, test report artifacts and contributor commands. Local master runs 15 unique tests instead of 25 executions. Patch also builds on SQLite: 148 executions, one external-fixture skip. | Independent of #84. |
| [SQLite recipe loading (#90)](https://github.com/Flipping-Utilities/rl-plugin/pull/90) | `loadRecipeFlipGroups` runs two component queries per flip, in addition to metadata. Indexes improve each lookup but retain query/statement overhead. | Account-scoped bulk loading uses three recipe queries. Real SQLite regression: 1,000 flips went from 2,001 to 3 queries. Isolation and missing-data behavior remain covered. | Stack on #84. |
| [Timeseries requests (#86)](https://github.com/Flipping-Utilities/rl-plugin/pull/86) | Repeated chart requests miss the cache together and issue duplicate HTTP calls; malformed or null responses can throw or enter the cache. | Coalesce requests by item and timestep, validate responses before caching, release failures for retry, close bodies, isolate subscriber exceptions. Seven controlled HTTP-boundary tests. | Stack on #84. |
| [Chart selection state (#89)](https://github.com/Flipping-Utilities/rl-plugin/pull/89) | Responses may arrive after a different item/timestep is selected and mutate client widgets from HTTP callbacks. | Follow-up tracks the selected request, discards obsolete results and publishes widget changes on the client thread. | Stack on the timeseries fix. |
| [Account file watcher (#87)](https://github.com/Flipping-Utilities/rl-plugin/pull/87) | Cancelling the future leaves its executor alive; `WatchService` is not closed. Plugin disable does not stop the watcher. A subscriber exception permanently exits the watcher. | Close each watch service, terminate the executor on stop/exhausted retries, make start/stop idempotent, stop on disable and isolate subscribers. Real filesystem notification and shutdown tests. | Stack on #84. |
| [Sidebar navigation and price advice (#91)](https://github.com/Flipping-Utilities/rl-plugin/pull/91) | Pagination can stay beyond the last page after data shrinks; arrows are mouse-only labels. Quick Look retains old advice on missing data and conflates equal-price rows. | Bound page state, supply keyboard-operable controls and clear current-item advice correctly. See the UI/UX audit and component validation in its follow-up. | Stack on #84. |
| [Large-trade totals (#92)](https://github.com/Flipping-Utilities/rl-plugin/pull/92) | Monetary products and lifetime quantities can wrap in 32-bit intermediates; UI average-price guards also narrow quantities. | Use 64-bit arithmetic throughout existing totals and display guards; five fixed-value regression tests. No accounting-policy or storage-format change. | Stack on #84. |

### UI follow-up drafts

| Topic | Problem fixed | Implementation | Dependency |
| --- | --- | --- | --- |
| [Sidebar consistency (#95)](https://github.com/Flipping-Utilities/rl-plugin/pull/95) | Mouse-only toolbar actions, uneven focus/disabled behavior, narrow scrollbars and empty states that obscure active filters. | Shared icon action/toggle behavior; keyboard account selection; wrapped, contextual item/recipe guidance; Clear search and Show all time; adaptive page input; search UI updates on the EDT. Actual before/after renders at 225/300 px. | Stack on #91. |
| [Login feedback (#96)](https://github.com/Flipping-Utilities/rl-plugin/pull/96) | Login cannot be submitted by keyboard, has no pending feedback and allows duplicate requests. | Token form with Enter/Space, local empty validation, masked input, one pending submission, disabled controls and inline retry feedback. Membership and sign-out actions are real buttons. Existing data-sharing/sign-out confirmations remain. | Stack on #91. |
| [Chart states and retry (#97)](https://github.com/Flipping-Utilities/rl-plugin/pull/97) | Failed requests leave loading unresolved; data outside the selected period can produce a blank active plot. | Per-subscriber failure completion; current-request ownership; loading/empty/error states; overlay and magnifier retry. Correct chart dimensions restore time labels and full hover bounds. Real consumer fixtures exercise loading, empty, error and loaded views. | Stack on #89. |
| [Native component gallery (#98)](https://github.com/Flipping-Utilities/rl-plugin/pull/98) | UI states are difficult to inspect without reproducing them in a game session. | Named synthetic fixtures, real RuneLite components/theme, reset, width presets, current-state PNG export and headless HTML contact sheet. Tests cover same-width preset transitions, queued search rendering and disabled external actions. | Stack on #91. |

### Recipe-load measurement

A local Java 11/macOS benchmark loaded an account with 5,000 recipe flips and 10,000 embedded snapshots, using five warmups and nine measured loads. Median time was 148.2 ms before batching and 38.1 ms after, including the read transaction that keeps the three batches consistent during another client's writes. This is about 74% lower latency in that recipe-loading scenario, not a prediction for full startup or gameplay. The durable regression is the constant query count; timing is observational and has no CI threshold. No schema or migration-format change is needed.

### Numeric boundaries in the compatibility path

[`HistoryManager.getTotalRevenueOrExpense`](../src/main/java/com/flippingutilities/model/HistoryManager.java) multiplies two `int` values before returning the result through `mapToLong`, allowing a large valid trade to wrap before widening. `countAccountFlipQuantity` and the cross-account quantity sum also use `int`, although aggregate lifetime quantities can exceed an individual offer's range. The item detail panel consumes these totals. These are numeric representation defects, separate from choosing a new accounting policy.

The follow-up uses 64-bit intermediate arithmetic and quantity totals across the public calculation/display boundary, with fixed-value fixtures for an individual trade above 2,147,483,647 gp and lifetime quantities above the same integer boundary. It preserves #85's accounting choices and the current matching rules.

## Remaining priorities

### High: integrate and measure bounded reporting

[`StatsPanel.rebuildItemsDisplay`](../src/main/java/com/flippingutilities/ui/statistics/StatsPanel.java) schedules filtering and sorting on Swing's event dispatch thread. Its totals repeatedly scan histories, calculate profit and build flip lists. [`SqliteStorage.loadAccount`](../src/main/java/com/flippingutilities/db/SqliteStorage.java) still hydrates complete account histories, so selecting a page does not bound storage or calculation work. Sort-key precomputation in #84 removes repeated comparator work but does not remove these full-history passes.

#94 now implements persisted calculations, SQL summaries/pages, immutable report results, request generation checks, recovery UI and background export. Integrate that work instead of building a second general-purpose cache over the compatibility models. The legacy Trades view still has the full-history behavior above. Use one report request identity containing account, period, search, ordering, accounting-plan version and source revision. Compute immutable results off the EDT; publish only if the request is still current. Preserve the existing view or show an explicit error when a query fails. Oracle's Swing guidance requires short EDT tasks and confines most component operations to that thread. [Source](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html)

Acceptance: first-page work returns a bounded number of rows; ordinary new-mode reports do not rematch offers; late responses cannot replace the selected view; failed queries cannot appear as successful empty history. Benchmark Session/24 hours/30 days/All at 50,000, 250,000 and 1,000,000 observations, recording wall time, allocations, EDT work and writer delay. Establish budgets on a named supported machine after measuring.

### High: complete plugin lifecycle ownership

[`FlippingPlugin.startUp`](../src/main/java/com/flippingutilities/controller/FlippingPlugin.java) registers a key listener and `SlotStateDrawer`, but `shutDown` does not unregister them. It starts wiki and slot-sender jobs, while only the client-close event calls their stop methods. Those jobs cancel scheduled futures without shutting down executors they create. The watcher fix addresses one independently testable resource; the broader lifecycle still needs a dedicated change.

Retain each registered listener, unregister it on disable, stop all owned jobs through one idempotent cleanup path, and invalidate in-flight callbacks before disposing UI. Preserve RuneLite-owned executors; terminate only plugin-owned ones. Guard delayed startup callbacks so disabling during startup cannot create fresh jobs afterward. Exercise enable → disable → enable, disable before delayed initialization, logout and client close. Assert one active subscription/task per feature and no post-disposal updates.

### Medium: complete asynchronous result contracts

The chart UX follow-up replaces the success-only request contract with per-subscriber futures and explicit loading, empty, failure and retry states. It also treats a series with no priced points in the selected period as empty. The remaining authenticated API methods are a separate concern: `ApiRequestHandler` returns `null` from authenticated request methods when JWT validation fails, although callers chain future operations. Return a completed exceptional future for unavailable authentication and test token expiry between scheduling and execution. Keep UI error messages actionable without exposing token details.

`TimeSeriesChart` repeatedly filters/sorts points during rendering, hover and price lookup. Normalize an immutable series once per successful response, then measure frame/hover allocation and duration before adding downsampling or a second cache. Coordinate this with request ownership so a cached series cannot outlive its item, timestep or data version.

### Medium: typed storage results and explicit mutation ownership

Favorites and GE limits cross the storage boundary as `Map<String, Object>` records, spreading string field names and unchecked casts into reconstruction. [`DataHandler.getAccountData`](../src/main/java/com/flippingutilities/controller/DataHandler.java) and `getAllAccountData` mark reads as dirty, while parallel `view*` methods do not. This makes correctness depend on callers choosing similarly named methods and makes future background reporting harder to reason about.

Replace map-shaped storage rows with small typed immutable values when those methods next change. Route state changes through explicit account commands that own persistence and revision invalidation. Avoid a whole-model rewrite before #85 establishes the durable source/report boundary. Preserve recovery guards with failure-injection tests at the boundary.

### Medium: secondary startup costs and timer parity

`loadAccount` reads favorites twice, and `restoreFavoriteOnlyItems` scans item lists for each favorite. Reuse the existing item-ID map if profiling identifies meaningful cost; avoid speculative caching. [`AccountData.hydrateSlotTimers`](../src/main/java/com/flippingutilities/model/AccountData.java) excludes completed offers, leaving elapsed-time display parity for completed but uncollected SQLite slots unresolved. Restore timing from saved offer timestamps and cover completed, partial, cancelled and collected cases without changing trade identity.

### Medium: stronger proof around tests and packaging

[`FlippingPluginTest.screenOfferTest`](../src/test/java/com/flippingutilities/FlippingPluginTest.java) builds fixtures but comments out both the event pipeline invocation and assertions. Its passing result is not behavioral coverage. Replace it with an actual public pipeline regression or remove it once equivalent cases are accounted for. Keep private account fixtures optional, with synthetic regression cases checked in so CI does not silently depend on one developer's machine.

Add a packaged-JAR smoke check that loads the embedded SQLite driver and performs a minimal transaction from the produced artifact. Source-classpath tests alone do not prove native-driver packaging. The new platform matrix can supply Linux/macOS/Windows coverage after #84 is present. Keep normal RuneLite compatibility checks on its rolling release, and consider a separately pinned baseline only if reproducible failure diagnosis warrants its maintenance cost.

## UI and UX direction

Read the [UI/UX audit](https://github.com/Flipping-Utilities/rl-plugin/blob/7338e65774edaace72fca70f8abf8a9f9144ba03/docs/ui-ux-audit.md) for source-specific recommendations, component renders and primary-source research. The immediate improvements preserve RuneLite's compact visual language: reliable pagination, keyboard access and advice that reflects the selected item. The second UI pass implements distinct no-history, no-search and no-interval messages, recovery actions, consistent toolbar controls, chart loading/failure/retry states and keyboard login feedback. #94 represents unknown cost separately from zero profit. Prioritize clear account/period/accounting-method context before adding more metrics or charts.

#94 implements #85's migration preview and deliberate per-account selection: keep current calculations, recalculate history, switch from a date, or start fresh while retaining the archive. Explain opening inventory and unknown cost in player language, show representative before/after totals and preserve a reversible selection history. Keep validating these labels against the calculation contract as that implementation changes.

### Component preview and remaining UI work

A native Swing component gallery now supplies the equivalent of named Storybook stories for this codebase. `./gradlew uiGallery` opens real RuneLite components with synthetic state selection, reset/remount, width presets and PNG export. `./gradlew renderUiGallery` produces 225 px and 300 px captures plus an HTML index. The current registry has 21 states across the full statistics sidebar, paginator, Quick Look and toggles. Preview code stays in the test source set and outside the plugin JAR. It does not require game login, live account data or application network calls.

This is the recommended default loop for UI changes: add the initial, empty, loading, failed and boundary-value fixtures relevant to a component; implement the change; inspect the same states at sidebar widths; then verify host interaction in RuneLite. Java changes require rerunning the gallery; it does not hot reload. The implementation and primary-source research are documented in [the gallery guide](https://github.com/Flipping-Utilities/rl-plugin/blob/ad1fb333f18cd220304014123656c270fc41cd7f/docs/component-gallery.md). [Storybook's component-state model](https://storybook.js.org/docs/get-started/browse-stories) and [FlatLaf's native preview controls](https://www.formdev.com/flatlaf/theme-editor/) inform this approach without replacing RuneLite's theme.

The next useful UI work is concentrated in these areas:

- Add synthetic service fixtures for #94's accounting setup, migration preview, unknown-cost summaries, custom-date errors, recovery and paged reports. These new panels should use the same preview workflow without opening a database. Their generation checks and background work already exist; visual validation should exercise the real service result types.
- Check the accounting report filter header at 225 px with Custom dates selected. Period, dates, time zone, search, view, sort, actions, status and recovery currently share the fixed header. Measure the remaining report viewport before deciding which advanced filters to collapse.
- Extend explicit accessible names and human labels to accounting arrow navigation, view/sort controls and field labels. Reuse shared action behavior where it fits; preserve the report's request and revision boundaries.
- Move legacy CSV export off the event dispatch thread with progress, disabled duplicate submission and a recoverable failure message. #94's report export already uses background requests; keep that implementation rather than adding a second export path.
- Complete plugin-owned scheduler/listener cleanup, including the login health timer, then use repeated gallery mounts and enable/disable tests to guard resource ownership.

## Review boundaries

The follow-ups are drafts for separate triage. Storage, network, watcher and UI work avoid changing accounting policy. The CI change can merge independently; SQLite-dependent branches should retain #84 as their base until it lands. Chart selection work depends on the request-coalescing branch.

The first-wave implementation commits were applied together on the SQLite baseline without conflicts. The combined Java 11 clean build discovered 184 unique test cases: 183 passed, one external-account-fixture migration test skipped, and no duplicate executions. An isolated classpath check loaded `org.sqlite.JDBC` from the built plugin JAR and created, inserted and read an in-memory database on macOS ARM64. The artifact includes 120 SQLite classes and 23 native libraries; this smoke test exercised the local native library only.

Additional validation includes a real SQLite query-count regression and observational benchmark, workflow static validation, and component-level Swing renders. Live RuneLite gameplay, external private fixtures and assistive-technology testing remain separate acceptance work. The new build workflow passed on GitHub-hosted Linux, macOS and Windows for #88. Those checks cover the master-based build PR; the SQLite-dependent changes were validated locally and in the combined build described by the audit PR.

### Combined accounting and UI validation

The second integration build combines #94 with both audit waves. Java 11 discovered 331 unique cases across 46 classes: 329 passed, two optional private-fixture/benchmark cases skipped, no failures and no duplicate executions. Building the plugin JAR and rendering all 21 gallery fixtures at both widths also passed. The JAR excludes preview classes and retains the SQLite driver and 23 platform-native resources. Unlike the first-wave driver smoke above, this second check inspected packaging; it did not repeat native loading on every platform.

The recipe-loading changes in #90 and accounting metadata in #94 need a merge resolution that retains both the snapshot-consistent batched reads and the recipe natural key, definition and execution count. That combined implementation is exercised by the integration build. The final sidebar-consistency tree merges with #94's accounting wrappers; the new rendering and accounting request guards remain present together. Gallery integration also waits for nested search callbacks before capture and recognizes the updated reset control so synthetic previews keep external actions disabled.

The interactive gallery process launched locally. Headless interaction tests and component renders were verified; native window automation could not attach to the Java process. Gameplay, host focus traversal, screen-reader output and real login remain follow-up acceptance checks. No private account data or real authentication tokens were used in previews.
