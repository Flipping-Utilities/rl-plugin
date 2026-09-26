# RuneLite sandbox and Swing component gallery

Run the real plugin sidebar in a local RuneLite shell, using disposable copies of saved data and public OSRS Wiki prices. The sandbox and synthetic component gallery use RuneLite's look and feel and stay on the test runtime classpath, outside the plugin JAR.

## Run with real data

Use JDK 11 and the checked-in Gradle wrapper:

```sh
./gradlew uiGallery
```

With no arguments, a source chooser opens. Click **Open default** to use `~/.runelite`. For another source, type or paste its path into **Source**, click **Browse…** to select a folder or file, or drag one local folder or file onto the chooser. Then click **Open copy**.

The chooser copies the plugin saves and `settings.properties` into a new temporary home in the background, with loading feedback. Errors stay in the chooser so you can correct the source and retry. Closing or cancelling the chooser exits without opening a sandbox and removes any copy in progress.

The sandbox opens a Grand Exchange simulator on the left and the real account selector, flipping, stats and slots panels on the right. Select an account in the sidebar to browse and edit its history. Stats initially shows **All** history. Drag the divider to resize the sidebar.

Use `--source` to skip the chooser, including for paths with spaces:

```sh
# A RuneLite root directory
./gradlew uiGallery --args='--source "/path/to/.runelite"'
# A plugin data directory containing account JSON files or flipping.db
./gradlew uiGallery --args='--source "/path/to/flipping"'
# Any individual SQLite database filename
./gradlew uiGallery --args='--source "/path/to/test account.sqlite"'
```

Every source is copied, including explicitly selected folders and files. The source is never opened by the running plugin. The launcher redirects `user.home` before RuneLite initializes its static paths, so even JSON saves, backups, migrations and deletion actions target the copy. SQLite uses a read-only backup connection to include committed WAL transactions in a consistent snapshot. The source does not need to be closed first. SQLite may create its `-shm` bookkeeping file and an empty `-wal` beside a closed WAL database while reading it; saved database contents are unchanged.

The window and console show the source and temporary directory. Closing the window or normally terminating the JVM removes the working copy. A forced kill or power loss can leave a `flipping-sandbox-*` temporary directory behind; the original files are still unaffected. Reopening starts with a fresh copy. An empty folder creates a **Sandbox player** account for simulated trades; a missing source produces an error. Relevant symbolic links are rejected; select their actual target instead.

An explicit database selects SQLite, including databases without the plugin's migration marker. Folder mode honors `flipping.dataSource=JSON` in `settings.properties`; otherwise it uses an available `flipping.db` unless it has a `.needs-resync` marker, and falls back to JSON. Inactive databases are skipped, so a corrupt stale database cannot block JSON testing. The sandbox does not rebuild a selected database from JSON. Unsafe account names are rejected before startup backups. Other display preferences use plugin defaults.

The host uses real local models and editing actions. It does not run the game, publish offers, or contact the plugin's backend and authentication services. Public Wiki requests use a separate client with no saved credentials or cookies. Recipe history and local recipes load from the copy; remote recipe catalogs are unavailable. Slot updates come from the local simulator. Explicit CSV exports can be saved outside the temporary directory through the normal file chooser.

## Simulate Grand Exchange trades

Choose the account receiving trades in the simulator's **Account** dropdown. This is independent of the sidebar's account filter. Select one of the eight slots, choose an item from the Wiki catalog (or type its name or numeric ID), select **Buy** or **Sell**, and enter the quantity and price before clicking **Place offer**. Saved items and a small built-in list remain available if the catalog cannot load.

For the selected offer, set **Items / second** and click **Set rate** to fill it automatically once per second; use `0` to pause. To advance manually, enter a **Chunk** quantity and click **Fill chunk**, or use **Fill remaining**. **Cancel offer** stops the unfilled part. Completed and canceled offers stay in their slots until you click **Collect**. These actions update the real sidebar through the plugin's offer handling.

Loaded offers start paused, and switching simulated accounts pauses the previous account's rates. **Fill price (gp/item)** controls the price of future fills; click **Apply price** after changing it. A loaded offer with no filled items may have no saved price, shown as `0`, so set a positive fill price before advancing it. Partially filled offers initially use their saved average price. You can change the fill price to test price improvements. The simulator limits an offer's total gross value to `2,147,483,647` gp to fit the plugin's integer model. Validation errors appear below the controls.

The simulator has no inventory or cash balance and does not match offers against a market. Changes stay in the temporary copy and disappear when the sandbox closes. Hover over the source and temporary-copy paths at the bottom to see their full values.

## Wiki data and price charts

The launcher reads the public Wiki `/mapping` catalog once per session for item names, GE limits, and icon filenames. The full catalog becomes available in the simulator's item dropdown. Icons download only when needed and are cached in memory. Latest market prices refresh automatically and are cached for 60 seconds; they update the real sidebar's price widgets and offer advice. These reads use only the public `prices.runescape.wiki` API and `oldschool.runescape.wiki` images. Simulated offers never change market prices.

Click a Wiki buy or sell price on the sidebar's **Flipping** tab to open its chart, or click **Prices / chart** in the simulator to inspect the selected slot or the item chosen for a new offer. The window uses the plugin's existing Quick Look and time-series chart widgets. Choose **Last 24 Hours**, **Last 7 Days**, **Last 1 Month**, or **Last Year**, and hover over the chart for timestamps and Insta Buy / Insta Sell prices. Opening a chart for an active simulated offer also shows its price as a reference line.

Use **Refresh** in the chart to retry a failed price or history request. Successful history responses remain cached until the next selected time interval plus 15 seconds. **Refresh Wiki** in the simulator retries catalog and latest-price loads. Failed icon requests can retry after 60 seconds when the icon is needed again.

If the Wiki is unavailable, saved data and simulated trading still work. Item names and GE limits fall back to saved records or the small built-in item list; unknown names appear as `Item <id>` and unsaved limits remain unavailable. Icons keep their placeholders. The status line reports unavailable or stale prices, and previously loaded sidebar prices remain visible. Chart failures show a retry message instead of closing the sandbox.

## Run synthetic component states

```sh
./gradlew uiGallery --args='--fixtures'
```

Choose a **State**, interact with its controls, and use **Reset state** to create a fresh instance with the listed initial values. Change **Width**, choose **225 px** or **300 px**, or enable **Fit available width** and resize the window. Widths are Swing logical units, so display scaling can change physical pixels. Tab and Shift+Tab move through actual controls; use Enter or Space where the component supports them.

**Export PNG** saves the current component, including local interactions, to `build/ui-gallery/<fixture-id>-<width>.png`. The status line reports the path or an error. The initial-state description remains the fixture recipe; it is not a live inspector of edited values. Reset restores that recipe. Exporting an image with the same name replaces it.

Start at a particular state from the command line:

```sh
./gradlew uiGallery --args='--fixture quick-look-buy-equal-prices'
```

Changes to Java source require closing and rerunning the task. This tool does not hot reload. On Windows, use `gradlew.bat` or run `./gradlew` from Git Bash.

## Render without a display

```sh
./gradlew renderUiGallery
```

This creates 225 px and 300 px PNGs for every registered fixture and an `index.html` contact sheet in `build/ui-gallery/`. Open that HTML file locally to compare states. Rendering creates fresh instances, waits for construction-time Swing updates, then releases each fixture's resources. It does not open native windows.

To choose a different output directory:

```sh
./gradlew renderUiGallery --args='--render-all build/review-images'
```

Gradle may download its build dependencies on the first run. The fixtures themselves use synthetic values and make no application network requests. Captures contain only the selected component, not the surrounding gallery controls. Font rendering can vary across operating systems; PNGs are review artifacts, not portable pixel-perfect assertions.

## Included states

| Component | States | Useful checks |
| --- | --- | --- |
| Statistics panel | Empty session, empty all-time history, no search matches | Full sidebar hierarchy, intervals, search field, tabs, sorting, summary labels and scrolling. |
| Paginator | First, middle, last, empty, large page count, results shrinking | Enabled boundaries, page entry, keyboard activation, clamping and long values. |
| Quick Look | Buy and sell below/within/above the price range, equal buy/sell prices, buy-to-sell reuse, populated-to-missing offer/prices | Advice, both highlighted equal-price rows, wrapping and stale-state removal. |
| Production toggle factory | On/off, enabled/disabled | Mouse and keyboard interaction, selected and disabled appearance. |

Statistics uses a controlled plugin boundary with empty in-memory history and a fixed elapsed time. Its file-export, deletion and recipe-management controls are disabled inside the preview. Search callbacks use an owned timer that returns UI work to the event dispatch thread and is cancelled when the fixture is replaced or the window closes. Quick Look fixtures omit timestamps to avoid moving age labels between captures.

The gallery intentionally shows the production component's current behavior, including layout defects. For example, a large paginator page number can expose the existing narrow page field. Fix the production component and rerun the same state to compare. The gallery does not replace gameplay validation, host focus traversal, screen-reader checks, or platform-specific native-driver tests. Accounting PR [#94](https://github.com/Flipping-Utilities/rl-plugin/pull/94) adds panels that are not on this branch.

## Add a fixture

The explicit registry is [`GalleryFixtures.all()`](../src/test/java/com/flippingutilities/ui/uiutilities/GalleryFixtures.java). Use the production component, supply a stable filename-safe ID, explain its input state, and choose a useful height. A small component needs only a factory:

```java
GalleryFixture.component("paginator-first", "Paginator / First page",
    "Use the actual arrows and page field.", "60 items, page 1", 80,
    () -> {
        Paginator paginator = new Paginator(() -> {});
        paginator.updateTotalPages(60);
        return paginator;
    });
```

For timers, executors or subscriptions, use a `GalleryFixture` factory returning `new GalleryFixture.Mounted(component, cleanup)`. Construction, interaction, painting and cleanup run on Swing's event dispatch thread; cleanup must finish promptly. `Mounted.close()` is idempotent. Keep expensive image encoding or other file work outside that thread. Every mount must create fresh components and mutable fixture data; never reuse a panel between unrelated stories. Avoid real plugin startup, credentials, account files, background services or external links.

`SandboxSourceChooserTest` exercises default selection, pasted paths, folder/file drops, retries and cancellation cleanup. `SandboxDataTest` checks source selection, independent copies, committed WAL data, failure cleanup and link handling. `SandboxPluginTest` launches fresh JVMs to check JSON/SQLite loading, edits, deletion, restart isolation, unsafe account names and incorrect-home rejection.

On a desktop, run `FLIPPING_SANDBOX_UI_TEST=true ./gradlew test --tests '*SandboxPluginTest' --rerun-tasks`. These checks exercise saved and empty-source exchanges through the real plugin: partial and timed fills, pauses, price changes, cancellation, collection, buy/sell accounting, account switching, invalid inputs and shutdown. They verify both JSON and SQLite writes stay in the temporary copy. The native interaction check opens the source chooser, places and fills an offer through the GE form, clicks a sidebar favorite, switches tabs and captures PNGs at the launcher's default 1000×850 size in the system temporary directory.

`SandboxWikiDataTest` uses in-memory HTTP responses to check request restrictions, coalescing, cache expiry and eviction, malformed data, image limits, retries, and shutdown. `SandboxPricePanelTest` checks the production chart and Quick Look rendering, hover values, period changes, failed-request retries, empty history, and discarded late responses. These tests make no internet requests by default. To check the live Wiki mapping, icon, latest prices, and history for an Abyssal whip, run:

```sh
FLIPPING_SANDBOX_WIKI_TEST=true ./gradlew test --tests '*SandboxWikiDataTest.optionalLiveWikiSmokeTest' --rerun-tasks
```

`UiGalleryTest` verifies that all registered fixtures render at both widths, queued construction updates appear in the capture, resources close after rendering failures, reset/switch operations discard previous component state, and width controls/export capture the current interactive state. Existing component tests remain responsible for application behavior. The older `SidebarPreview` entry point still produces its original audit image.

## Design sources

- Storybook defines stories as discrete component states with an isolated preview. The gallery adopts named states and fresh mounts using actual Swing components. [Storybook: browse stories](https://storybook.js.org/docs/get-started/browse-stories).
- FlatLaf's native theme editor offers component previews and enabled/editable/focused state controls. That supports a native Swing workbench for examining control states; this gallery retains RuneLite's existing look and feel and does not add FlatLaf or switch themes. [FlatLaf: theme editor preview](https://www.formdev.com/flatlaf/theme-editor/).
- Oracle requires most Swing operations on the event dispatch thread and describes responsive handlers as short tasks. The gallery confines component work to that thread and writes exported PNGs in a worker. [Oracle: the event dispatch thread](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).

These sources inform the developer tool's structure. They are not evidence that every plugin state or accessibility requirement is covered.
