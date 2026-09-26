# Browser component gallery

This experiment runs the plugin's actual Swing components in a browser through CheerpJ 4.3 and Java 11. It lives on `experiment/cheerpj-gallery`; the planned GitHub Pages URL is [flipping-utilities.github.io/rl-plugin](https://flipping-utilities.github.io/rl-plugin/). The Pages workflow is restricted to this experimental branch.

The browser shares the desktop gallery's 21 synthetic fixtures and `UiGallery.Workbench`: empty Statistics states, paginator states, Quick Look advice and toggle controls. State selection, reset, width controls and component interactions use the same Java code. `BrowserGallery`, under `src/test`, embeds the workbench in a maximized window and tells the page when Swing initialization finishes. **Export PNG** writes to CheerpJ's `/files/downloads` directory to trigger a browser download.

Build from the repository root with JDK 11:

```sh
./gradlew browserGallery -PruneLiteVersion=1.12.39
npx http-server@14.1.1 build/browser-gallery -p4173 -c-1
```

Open [localhost:4173](http://localhost:4173/) and click **Open interactive gallery**. Use an HTTP server with byte-range support, such as the command above. The generated `build/browser-gallery/manifest.json` records the source commit, entry point and relative dependency paths.

Run `./gradlew verifyBrowserGallery -PruneLiteVersion=1.12.39` to render all 21 fixtures at both widths using only the files included in the browser distribution. This catches missing packaged dependencies that normal tests with the full test classpath could hide. The branch workflow runs this check and the gallery, paginator and Quick Look tests before deployment.

The first launch downloads the CheerpJ runtime and gallery dependencies. Later launches can reuse the browser cache; clearing site data or disabling the cache changes that behavior. The runtime comes from CheerpJ's CDN, so a connection is needed for the first launch. Browser verification detected remapping of RuneScape and Whitney fonts. This preview does not guarantee pixel parity with the desktop; use `./gradlew renderUiGallery` and its images in `build/ui-gallery/` as the desktop reference.

The initial local check used Chromium on macOS: Statistics and Quick Look rendered, state selection and width controls worked, and PNG export produced a 225×240 image. A launch with the runtime already cached reached the Swing-ready callback in 6.1 seconds. The static distribution is approximately 18 MiB, excluding the separately hosted CheerpJ runtime; JAR byte ranges are loaded on demand. These are prototype measurements, not performance guarantees.

Add states to the existing [`GalleryFixtures`](../src/test/java/com/flippingutilities/ui/uiutilities/GalleryFixtures.java) registry. Fixtures should mount fresh production components with synthetic data and release owned timers or other resources when replaced. The [desktop gallery documentation](../docs/component-gallery.md) describes the fixture contract. Rebuild the distribution and reload the page after changing Java source.

This experiment makes no `src/main` changes and leaves the production plugin JAR packaging unchanged. Its separate artifact contains compiled production classes and resources, an explicit list of developer gallery classes, and filtered dependency JARs. It publishes no account files or saved credentials. Test runners, Mockito, SQLite and game/native runtime dependencies are excluded. The full desktop sandbox, mocked Grand Exchange simulator, live API data, account imports and SQLite storage are outside this browser gallery's scope.

CheerpJ is provided by [Leaning Technologies](https://cheerpj.com/). Its [licensing documentation](https://cheerpj.com/docs/licensing) covers FOSS use, technical evaluations and commercial use; publishing a public gallery is distinct from a private technical evaluation. This repository retains its [BSD 2-Clause license](../LICENSE), and dependencies retain their own licenses.
