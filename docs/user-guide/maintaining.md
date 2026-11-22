# Maintaining this guide

[User guide](README.md) › Maintaining this guide

## Screenshots

The images in [`images/`](images) are rendered from the plugin's real Swing panels, not captured from a game
session:

```sh
./gradlew docsScreenshots
```

- `src/test/java/com/flippingutilities/docs/DocsFixture.java` is a `FlippingPlugin` wired with two example
  accounts, an offline trade history, fixed wiki prices and a recipe flip. `ItemManager` is a Mockito stand-in;
  item icons are downloaded from `https://assets.osrs.cloud/icons/<id>.png` and cached in `build/docs-icons`.
  The recipe datasets are fetched from GitHub like the plugin does.
- `src/test/java/com/flippingutilities/docs/DocsScreenshots.java` builds the panels, clicks through them and
  saves each view at 2× scale. Numbered callouts are attached to components, so they follow layout changes;
  keep the numbered lists in the pages in the same order as the `callouts(...)` calls.
- The offer page chart and the quick look tooltip come from the existing `ChartStateFixtures`.
- Times and "x minutes ago" labels depend on when you render, so a re-render changes every image slightly.

Things drawn on the game interface itself (slot timers, chat shortcuts, the GE search) can't be rendered this
way. Those pages reuse the in-game captures in the repository's top-level `images/` folder, which come from an
earlier version of the plugin.

The renderer needs a display (it is not headless), because the panels create dialogs.

## Known issues found while writing the guide

These are documented as the plugin behaves today. Fixing one should come with an update of the page in
parentheses.

1. **Unused settings.** *Account for margin check loss* and *12 hour format* are read nowhere ([Settings](settings.md)).
2. **No way to delete an account.** `ui/settings/SettingsPanel.java`, which has the delete-account action, is
   never created.
3. **Slot enhancement is only reachable after logging in.** Its switch is in the logged-in status window, so
   players without a token can't turn the GE slot borders and magnifiers off ([Slots](slots.md)).
4. **Quick lookup** turns off the whole magnifier, not only the chart its description mentions.
5. The **offer page chart** reads the *Time range* setting once, at startup.
6. **Total Flips Made** says it doesn't count margin checks, but it does.
7. Only **Last buy price** and **Last insta buy** are editable on a card, and the edits aren't saved.
8. **GE history imports** are dated at import time ([Adding untracked trades](ge-history.md)).
9. The sell quantity prompt doesn't switch the offer editor to the quantity tab ([Setting up offers](offer-setup.md)).
10. **Quick look** shows its state as the raw enum name (`IN_RANGE`).
11. **Possible crashes:** the GE price prompt dereferences the last wiki request, which is null if prices were
    never fetched (`GameUiChangesHandler`); the GE search handler looks up the logged-in account's data, which is
    null for untracked accounts such as ironmen (`FlippingPlugin.onGrandExchangeSearched`).
12. **Backup checkpoints** are written as `backupCheckpoints.special.json` but read as
    `backupcheckpoints.special.json`. On case-sensitive file systems every start makes a new backup.
13. **The recipe datasets are fetched with a blocking `join()` in `startUp`**, so a slow network delays the
    plugin.
14. The card customization window uses old names for the values ("price check buy price" is *Last insta sell*).
15. The profile button's popup says *Click to open your profile page!* but it opens the status window.
