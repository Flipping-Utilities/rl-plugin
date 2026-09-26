# Browser RuneLite sandbox

The `experiment/cheerpj-gallery` branch runs the desktop sandbox's plugin panels, trading pipeline and simulated Grand Exchange in a browser through CheerpJ 4.3 and Java 11. GitHub Pages serves it at [flipping-utilities.github.io/rl-plugin](https://flipping-utilities.github.io/rl-plugin/); the deployment workflow is restricted to this branch.

Choose **Open empty sandbox** to simulate trades from scratch, or import saved data with **Choose RuneLite folder**, **Choose database / files**, or drag and drop. The browser cannot discover `~/.runelite` automatically. Select the RuneLite root, its `flipping` folder, JSON saves, or a SQLite database. Close RuneLite before selecting database files so the selection is consistent, and include the matching `-wal` file if one remains. A folder import also carries companion `accountwide.json` data such as custom recipe definitions and sidebar preferences; those are absent from a standalone database.

The shared Grand Exchange controls support account selection, buy/sell offers, timed or manual fills, price changes, cancellation and collection. Changes update the real sidebar. Wiki names, icons, prices and charts use public browser requests without account credentials. The sandbox does not connect to a game account or place real offers. See the [desktop sandbox guide](../docs/component-gallery.md) for control details.

Each launch creates a fresh working copy in the browser filesystem. Editing that copy never overwrites the selected originals. **New session** stops the sandbox, deletes its virtual working directory, then reloads; select the source again to reopen it. Browser refresh or abrupt tab closure always starts a new empty session, but may leave temporary files in browser-managed IndexedDB storage. The app never reopens those abandoned copies. Clearing this site’s browser data removes them. SQLite is an import format: sql.js reads a snapshot, and `BrowserSqliteImporter` reuses the production `SqliteStorage` account reconstruction methods through read-only JDBC proxies. The resulting accounts are written to temporary JSON, which the shared sandbox then loads and edits. There is no native SQLite driver in the browser and no SQLite export or database round-trip.

The importer accepts schema version 1, checks required columns, database integrity and foreign keys, and transfers 64-bit integers as decimal strings. Native comparison tests cover multiple accounts, active and deleted-history slots, recipes with retained or missing offer snapshots, favorites, visibility and GE limits. Browser import code validates SQLite pages and WAL headers, salts, checksums and commit boundaries before assembling the last committed snapshot. Native SQLite fixtures check appended, repeated, truncated, reset and unfinished WAL transactions. Active rollback journals are rejected. Selecting a WAL-mode database without its WAL produces a warning because missing committed data cannot be recovered. SQLite snapshots are limited to 256 MiB.

**Component gallery** remains available with the same 21 synthetic fixtures and `UiGallery.Workbench` used on desktop. State selection, reset, width controls and interactions share their Java implementation. **Export PNG** uses `/files/downloads` to trigger a browser download. Add states to [`GalleryFixtures`](../src/test/java/com/flippingutilities/ui/uiutilities/GalleryFixtures.java); each fixture should mount fresh production components and release its owned resources when replaced.

Build from the repository root with JDK 11, then serve the generated directory with byte-range support:

```sh
./gradlew browserGallery -PruneLiteVersion=1.12.39
npx http-server@14.1.1 build/browser-gallery -p4173 -c-1
```

Open [localhost:4173](http://localhost:4173/). Rebuild and reload after Java changes. `build/browser-gallery/manifest.json` records the source commit, entry points and relative dependency paths. The first launch downloads the CheerpJ runtime and application dependencies; later launches can reuse browser caches. A warm empty-sandbox launch reached the Swing-ready callback in 10.8 seconds and populated the item dropdown from the Wiki. A direct Wiki request from the deployed Pages origin returned HTTP 200. Chromium checks also imported SQLite and JSON folders, preserved paused offers and account selection, filled buy/sell chunks, completed and collected an offer, placed new offers, and opened a chart from a sidebar price widget. The two-account SQLite import matched native Java output exactly after normalizing generated modification timestamps, including integers above JavaScript’s safe range. Startup measurements are not performance guarantees.

Run the browser import tests with Node 20 or newer and Python 3:

```sh
node --test web-gallery/test/import-data.test.mjs
./gradlew verifyBrowserGallery -PruneLiteVersion=1.12.39
```

`verifyBrowserGallery` renders all 21 fixtures at both widths using only the published dependencies. The branch workflow also runs the Java importer, game-interface adapter, sandbox loading, snapshot-copying, Wiki-data and component tests. Desktop window-interaction tests remain opt-in. Earlier component-gallery checks rendered Statistics and Quick Look and exercised selection, widths and PNG export in Chromium on macOS. RuneScape and Whitney font remapping was observed, so browser images do not guarantee pixel parity. `./gradlew renderUiGallery` produces the desktop reference images in `build/ui-gallery/`.

All browser host adapters live under `src/test`; this experiment makes no `src/main` changes and leaves production plugin JAR packaging unchanged. The separate browser artifact includes production classes/resources, an explicit list of developer host and gallery classes, and filtered dependency JARs. Published assets contain no account files or credentials. JUnit, Mockito, native SQLite and game-only native dependencies are excluded.

The vendored `site/vendor/sql-wasm.js` and `sql-wasm.wasm` come from sql.js 1.14.2. The npm package integrity is `sha512-3ZGPovObMFrdw79zrUHbfdE/DLIsy8jdNdssmMSQuRAymedU6q84asPt0kgiqrdMYlPegDItiIMfmIXzZnYFcw==`; its [MIT license](site/vendor/sql.js-LICENSE) is included. CheerpJ is provided by [Leaning Technologies](https://cheerpj.com/); see its [licensing documentation](https://cheerpj.com/docs/licensing) for FOSS use, technical evaluations and commercial use. This repository retains its [BSD 2-Clause license](../LICENSE), and dependencies retain their own licenses.
