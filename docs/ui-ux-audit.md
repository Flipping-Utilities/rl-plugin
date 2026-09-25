# Sidebar UI and interaction audit

This audit examines the sidebar and Quick Look components at the SQLite migration baseline `3c2df74` ([PR #84](https://github.com/Flipping-Utilities/rl-plugin/pull/84)), alongside the accounting plan in [PR #85](https://github.com/Flipping-Utilities/rl-plugin/pull/85). The evidence is source inspection, the repository's [statistics screenshot](../images/stats.png), [flipping screenshot](../images/flipping.png), component tests, and rendered Swing fixtures. It is not a live-game usability study or an accessibility certification. The README images document established visual language; they are not assumed to represent every current state.

Keep RuneLite's compact dark sidebar, item imagery, tab structure, and familiar price colors. The highest-value changes make existing actions discoverable and make missing, delayed, or filtered data understandable before introducing a broader visual redesign.

## Implemented in this change

| Problem | User impact | Change and evidence |
| --- | --- | --- |
| The paginator kept an out-of-range page when results shrank. | Removing the last item on a later page could leave an empty list even though earlier results remained. | Clamp the selected page before rendering or slicing results; update the input, count, and disabled controls together. `PaginatorTest` covers shrinking, empty results, page-size changes, and failed callbacks. |
| Pagination arrows were mouse-only `JLabel` controls. | Keyboard users could enter page numbers but could not focus or activate the arrows; boundary arrows looked actionable. | Use named `JButton` controls, Space/Enter activation, a focus border, 24 × 24 logical-unit targets, and disabled first/last-page controls. Preserve the existing arrow artwork. |
| Quick Look returned early for missing data before clearing its previous advice. | A newly unavailable offer could still display a prior offer's competitiveness or suggested price. | Clear advice before handling missing data. Regression tests cover missing prices and missing slots after a populated offer. |
| Quick Look keyed labels by price in a map. | Equal buy/sell prices replaced one another in the map, leaving only one row highlighted. | Apply the relevant price highlight to each matching row, including equal prices. Tests cover both buy and sell offers. |

Swing's standard button APIs provide action handling and disabled appearance, and its accessibility API supports explicit component names. Focused key bindings keep Enter local to a pagination button instead of intercepting gameplay or unrelated fields. These are the mechanisms used here. [Oracle: buttons](https://docs.oracle.com/javase/tutorial/uiswing/components/button.html), [accessibility](https://docs.oracle.com/javase/tutorial/uiswing/misc/access.html), [key bindings](https://docs.oracle.com/javase/tutorial/uiswing/misc/keybinding.html).

## Recommended follow-up work

### 1. Expose storage progress and recovery in the sidebar

**Priority: high; coordinate with the SQLite migration.** `MasterPanel.updateSqliteIndicator` exposes a database icon and tooltip based on storage presence. `FlippingPlugin.runMigrationIfNeeded` logs migration failure and preserves the JSON view. The UI should explain the active user-visible state: preparing history, ready, or unable to switch with the previous history still available. Preserve the selected account and interval while work completes. Offer a retry action only after the persistence layer can safely retry it, and show success after the transition is actually complete.

A small inline status below the account selector can communicate this without a modal on every launch. Its text should describe history availability; technical details and the storage engine name belong in an expandable detail or tooltip. Do not imply that configuration selection means migration succeeded. Source: [MasterPanel](../src/main/java/com/flippingutilities/ui/MasterPanel.java), [FlippingPlugin](../src/main/java/com/flippingutilities/controller/FlippingPlugin.java). WAI's [status-message guidance](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html) is useful for deciding which asynchronous outcomes need a persistent, perceivable status.

**Acceptance:** fixtures for first launch, successful migration, failure with usable prior data, retry, and account change during loading; no blank zero-profit view that implies a completed empty history while loading.

### 2. Finish keyboard access across the sidebar

**Priority: high.** Pagination is only one entry point. `MasterPanel.accountSelector` explicitly disables focus; the statistics sort/export/reset actions and flipping favorite filter use labels with mouse listeners. Replace these interactive labels incrementally with real buttons or toggle buttons and accessible names. Enable account selection from the keyboard, associate search labels with inputs, and verify the host tab controls' focus behavior before replacing them.

Keep focus order aligned with the visible order: account, tabs, filters, summary actions, results, pagination. Preserve a visible focus marker on dark backgrounds. Do not add single-letter shortcuts that compete with the game's input. Sources: [MasterPanel](../src/main/java/com/flippingutilities/ui/MasterPanel.java), [StatsPanel](../src/main/java/com/flippingutilities/ui/statistics/StatsPanel.java), [FlippingPanel](../src/main/java/com/flippingutilities/ui/flipping/FlippingPanel.java). [WAI focus guidance](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html) supports making the current keyboard target visible.

**Acceptance:** traverse and activate core sidebar actions using Tab, Shift+Tab, Enter, and Space in RuneLite; confirm focus can return to the game and no hidden control traps navigation.

### 3. Distinguish empty history, empty filters, unavailable data, and loading

**Priority: high.** Statistics already provide a separate no-search-results message. However, the item and recipe containers use onboarding text when a date/account selection produces no records, even if the player has history elsewhere. Show “No trades in this interval” with “Show all time” for that case. Reserve “Make some trades…” for an account with no history. Retain the query and interval during refreshes; never turn missing price data into a numeric zero.

The baseline `TimeseriesFetcher` only delivers successful data through its callback, so consumers cannot consistently distinguish request failure from ongoing loading. For charts and price refreshes, pair any loading animation with a textual state, last successful update time where available, and a retry action for a terminal failure. Introduce an explicit completion result for success, no history, and failure in the data-loading work before wiring these states. Keep request ownership checks so an older response cannot replace the currently selected item. Sources: [StatsPanel](../src/main/java/com/flippingutilities/ui/statistics/StatsPanel.java), [item container](../src/main/java/com/flippingutilities/ui/statistics/items/FlippingItemContainerPanel.java), [recipe container](../src/main/java/com/flippingutilities/ui/statistics/recipes/RecipeGroupContainerPanel.java), [QuickLookPanel](../src/main/java/com/flippingutilities/ui/uiutilities/QuickLookPanel.java).

**Acceptance:** render no account history, no interval matches, no search matches, loading, missing wiki prices, and failed refresh separately. The next useful action should be visible in each recoverable state.

### 4. Explain deletion scope and preserve accounting meaning

**Priority: high; coordinate with accounting PR #85 and SQLite PR #84.** The statistics reset dialog mentions the selected interval and defaults to “No,” which is useful protection. It does not name the current account or explain that deleting offers can also invalidate linked recipe flips. Individual offer and recipe deletion dialogs also use generic confirmations. Include the account, interval or item, affected record count when available, and dependent-record impact in the confirmation. Prefer a concrete action label such as “Delete 12 offers” with Cancel as the safe initial choice.

SQLite maintenance already confirms deletion/regeneration and distinguishes JSON files. Extend that flow with visible completion/failure feedback and make its explanation match the final recovery model. Do not present “Reset statistics” as a harmless display preference. Source: [StatsPanel.createResetButton](../src/main/java/com/flippingutilities/ui/statistics/StatsPanel.java), [OfferPanel](../src/main/java/com/flippingutilities/ui/statistics/items/OfferPanel.java), [RecipeFlipPanel](../src/main/java/com/flippingutilities/ui/statistics/recipes/RecipeFlipPanel.java), [FlippingPlugin.deleteOffers](../src/main/java/com/flippingutilities/controller/FlippingPlugin.java). [WAI error-prevention guidance](https://www.w3.org/WAI/WCAG22/Understanding/error-prevention-legal-financial-data.html) offers useful confirmation/recovery heuristics for changes to stored user data.

When accounting distinguishes unknown acquisition cost from a real zero, carry that distinction through aggregate profit, ROI, export, and recipe rows. Show a concise reason such as “Cost unavailable for 2 inputs,” not only an unexplained “Unknown.” Validate both JSON and SQLite views before changing financial labels.

### 5. Improve dense layouts without losing useful information

**Priority: medium.** The README screenshots show information-dense cards, small icon actions, and long scrollable histories. Both statistics containers explicitly make the scrollbar only two logical units wide. Use the host scrollbar's normal target width or verify a wider custom target. Keep destructive actions separated from frequent actions; reveal explanatory text on keyboard focus as well as hover. Several labels request a `Whitney` font that may fall back on another machine; prefer the existing RuneLite font manager or an explicitly chosen platform logical font.

Measure actual text and focus contrast from the deployed theme before choosing replacement colors. Do not infer a contrast failure from the README images. Check 225 px and 300 px widths, large prices, long item/account names, and enlarged fonts; protect value legibility and wrap explanatory text before squeezing it. Sources: [item container](../src/main/java/com/flippingutilities/ui/statistics/items/FlippingItemContainerPanel.java), [recipe container](../src/main/java/com/flippingutilities/ui/statistics/recipes/RecipeGroupContainerPanel.java), [QuickLookPanel](../src/main/java/com/flippingutilities/ui/uiutilities/QuickLookPanel.java). [WAI target-size guidance](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) motivates adequate targets and spacing; CSS pixel thresholds are not asserted to map directly to Swing logical units.

### 6. Keep expensive work away from interaction handlers

**Priority: medium; profile representative large histories.** `StatsPanel.createDownloadButton` calls CSV export directly from its mouse handler. Moving export and expensive filtering/aggregation to a worker can prevent a frozen sidebar, provided the worker consumes an immutable snapshot and returns component updates to the event dispatch thread. Coalesce repeated refresh requests; preserve expanded cards, scroll position, and selected page when unrelated prices refresh. Also profile `TimeSeriesChart.filterAndSortData`, which is called during rendering and price/hover calculations; normalize an immutable series once per response if measurements show that repeated sorting matters. Sources: [StatsPanel](../src/main/java/com/flippingutilities/ui/statistics/StatsPanel.java), [TimeSeriesChart](../src/main/java/com/flippingutilities/ui/widgets/graph/TimeSeriesChart.java). [Oracle's event-dispatch-thread guidance](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html) requires most Swing updates on that thread and warns that long-running handlers prevent responsive interaction.

**Acceptance:** measure filter, account-change, export, and refresh latency on a representative large account; verify the UI remains responsive and stale worker results cannot replace a newer selection. Do not claim a performance gain from an architectural change without before/after measurements.

## Verification and limits

The [component preview](images/sidebar-states.png) renders the new paginator at 225 px and 300 px widths, first/last/empty boundaries, and Quick Look with equal prices then unavailable data. It uses RuneLite's look and feel on the test classpath. The outlined next buttons simulate a focus event for the static fixture; this image is not evidence of operating-system focus traversal. Run `com.flippingutilities.ui.uiutilities.SidebarPreview` from the IDE with the test runtime classpath to regenerate `build/ui-preview/sidebar-states.png`.

`PaginatorTest` exercises Swing key bindings for Enter and Space, button semantics, direct input, invalid input, rollback, and clamping. `QuickLookPanelTest` exercises reused-panel transitions rather than only initial render. All component interactions run on the Swing event dispatch thread. Live RuneLite tab traversal, screen-reader output, other operating systems, enlarged fonts, and game-login states still need human testing before a wider accessibility claim.

W3C's [WCAG2ICT guidance](https://www.w3.org/TR/wcag2ict-22/) describes applying accessibility principles to desktop software and is explicitly informative. The WAI documents above are design heuristics for this Swing plugin, not a claim that the plugin satisfies WCAG or a legal standard.
