# Swing component gallery

Run real plugin components in named states without starting RuneLite, logging into the game or loading account files. The gallery uses RuneLite's look and feel and the existing test runtime classpath. It adds no application dependency and is excluded from the plugin JAR.

## Run

Use JDK 11 and the checked-in Gradle wrapper:

```sh
./gradlew uiGallery
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

The gallery intentionally shows the production component's current behavior, including layout defects. For example, a large paginator page number can expose the existing narrow page field. Fix the production component and rerun the same state to compare. The gallery does not replace gameplay validation, host focus traversal, screen-reader checks, or platform-specific native-driver tests. Accounting PR [#94](https://github.com/Flipping-Utilities/rl-plugin/pull/94) adds more reporting panels; those require their own synthetic service fixtures rather than connecting this tool to a database.

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

`UiGalleryTest` verifies that all registered fixtures render at both widths, queued construction updates appear in the capture, resources close after rendering failures, reset/switch operations discard previous component state, and width controls/export capture the current interactive state. Existing component tests remain responsible for application behavior. The older `SidebarPreview` entry point still produces its original audit image.

## Design sources

- Storybook defines stories as discrete component states with an isolated preview. The gallery adopts named states and fresh mounts using actual Swing components. [Storybook: browse stories](https://storybook.js.org/docs/get-started/browse-stories).
- FlatLaf's native theme editor offers component previews and enabled/editable/focused state controls. That supports a native Swing workbench for examining control states; this gallery retains RuneLite's existing look and feel and does not add FlatLaf or switch themes. [FlatLaf: theme editor preview](https://www.formdev.com/flatlaf/theme-editor/).
- Oracle requires most Swing operations on the event dispatch thread and describes responsive handlers as short tasks. The gallery confines component work to that thread and writes exported PNGs in a worker. [Oracle: the event dispatch thread](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).

These sources inform the developer tool's structure. They are not evidence that every plugin state or accessibility requirement is covered.
