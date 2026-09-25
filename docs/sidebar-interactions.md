# Sidebar interaction follow-up

This implements the toolbar and empty-state recommendations from the [UI/UX audit](ui-ux-audit.md). It retains RuneLite's palette, arrow/star/action artwork, tabs, and information hierarchy.

## What changes

- Sort, CSV export, reset, and the favorites filter are standard Swing buttons. The shared `IconButtons` factory gives them consistent 32 × 32 logical-unit targets, hover artwork, visible focus, accessible names, and local Enter/Space activation. The favorites filter exposes a selected toggle state. Pagination reuses the same behavior with its existing 24 × 24 targets and disabled page boundaries.
- Account selection can receive keyboard focus. History search and interval selectors have explicit names. The search callback returns to the Swing event dispatch thread before changing controls.
- An account without item history gets concise onboarding. History outside the selected interval gets **Show all time**. An unsuccessful search gets **Clear search**, which preserves the interval. Item and recipe views use the same wrapped text component and RuneLite fonts.
- History and flipping scrollbars use the theme's width. Fixed-width onboarding no longer forces a horizontal scrollbar at a 225 px sidebar width.
- A four-digit page number can display in full; the page input grows to the selected page's digit count.

The change keeps reset confirmation and export behavior. CSV generation still runs in its existing interaction handler; moving it to a worker remains a separate performance recommendation. The contextual messages do not change accounting or storage rules.

## Rendered evidence

These images paint the real `StatsPanel` with RuneLite's look and feel and controlled offline data. Each image includes 225 px and 300 px widths. They are component renders, not a logged-in game session or an accessibility certification.

| State | Evidence |
| --- | --- |
| Previous empty account, from `7338e65` | [Before](images/sidebar-consistency/before-empty.png) |
| Empty account, using wrapped onboarding | [After](images/sidebar-consistency/after-empty.png) |
| Trades exist outside the selected interval | [Show all time](images/sidebar-consistency/after-interval.png) |
| Search has no matches | [Clear search](images/sidebar-consistency/after-search.png) |

Run `com.flippingutilities.ui.statistics.SidebarConsistencyPreview` from the IDE on the test runtime classpath to recreate the three after images under `build/ui-preview`. It uses the same controlled history fixture as `StatsEmptyStateTest` and closes its worker threads after rendering. The prior screenshot was rendered from the unmodified parent revision with an equivalent empty-account fixture.

## Validation and next steps

`IconButtonsTest` checks action/toggle keyboard behavior, accessible roles/names, and disabled activation. `StatsEmptyStateTest` exercises the real sidebar's no-history, no-interval, and no-search results; verifies that the recovery actions update the existing filters; and checks normal scrollbar widths. Existing pagination tests cover the shared factory through RuneLite's look and feel.

PR #94 adds an accounting view around these legacy controls, which remain available in its Trades tab. The rendering changes belong in that shared editor; the new accounting report and setup panels retain their separate behavior. Before merging the combined stack, check keyboard focus through the enclosing tabs and recovery actions in a live client, plus screen-reader output and operating-system look-and-feel differences.

The design basis remains Swing's [button semantics](https://docs.oracle.com/javase/tutorial/uiswing/components/button.html), [focus-local key bindings](https://docs.oracle.com/javase/tutorial/uiswing/misc/keybinding.html), and [event dispatch thread](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html). The original audit records the broader research and deferred design recommendations.
