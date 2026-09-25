# Reproduce recipe migration data loss

PR: https://github.com/Flipping-Utilities/rl-plugin/pull/84

Reported symptom: immediately after migrating an account, one recipe input remained
correct, another showed consumed quantity 1 and price 0, and its output showed price 0.
The original JSON is available only on the reporting user's other machine.

## Confirmed local reproduction

Before the fix, legacy JSON with a valid history-backed input and two embedded-only
recipe offers produced this result through the real migration and account loader:

| Component | Original quantity | Unit price before | Unit price after | Consumed |
| --- | ---: | ---: | ---: | ---: |
| History-backed input | 10 | 100 | 100 | 2 |
| Recipe-only input | 20 | 250 | 0 | 1 |
| Recipe-only output | 30 | 1,000 | 0 | 1 |

For January 2026 offers, recipe expense should be 450, revenue after tax 980,
profit 530 and tax 20. The broken loader instead reports expense 200, revenue 0,
profit -200 and tax 0. It also replaces the missing offers' original quantities
with their consumed quantities.

Root cause: migration imported completed offers from item history but retained only
UUID and consumed quantity for recipe components. Loading components joined those
UUIDs against history rows. Missing rows silently became zero-price offers.
An older JSON compaction path also discarded embedded offers on save, so this loss
could happen before the SQLite import. That explains why reproducing only a database
reload is insufficient for the report of immediate breakage.

The fix persists recipe offer snapshots in JSON and SQLite, independently of ordinary
history. UUID-only JSON still resolves against history. If neither the embedded offer
nor the referenced history offer exists, migration rejects that account transaction
and leaves migration incomplete instead of claiming a successful zero-price import.

## Instructions for the agent with the account JSON

1. Preserve untouched copies of the original account JSON, its backups, and any
   existing SQLite database (including its WAL/SHM files). Do not run the old plugin
   against the only original copy: JSON autosaving can erase the embedded offers.
2. Fetch `fix/sqlite2-audit` from this PR. Use JDK 11. Copy the original account JSON
   into `src/test/resources/realdata/<account>.json` in an isolated checkout. This
   directory is gitignored; do not commit or publish the account files. Test only
   copies. The test creates its own temporary database and checks that source files
   remain byte-for-byte unchanged.
3. Run:

   ```sh
   ./gradlew test --tests com.flippingutilities.db.RecipePersistenceTest --tests com.flippingutilities.db.RealDataMigrationTest --no-daemon --console=plain
   ```

   Inspect `build/reports/tests/test/index.html` and
   `build/test-results/test/TEST-com.flippingutilities.db.RealDataMigrationTest.xml`.
   A skipped real-data test means the fixtures were not found; it is not a pass.
   The real-data test now compares each recipe input/output, consumed and original
   quantities, prices, state, side, timestamp (milliseconds), coin cost, expense,
   revenue, profit and tax. It also checks normal-history profit and item counts.
4. For each affected component, report the recipe key, creation timestamp, input or
   output, item ID, consumed amount and effective UUID. The effective UUID is
   `offerUuid`, or the embedded `offer.uuid` for older records. Check for a matching
   offer in `trades[].history.compressedOfferEvents[]` and `lastOffers`. Report whether
   the original embedded offer exists and its `p` (price), `cQIT` (quantity), `st`
   (state), `t` (time), `id` (item ID) and `b` (buy/sell). Compare backups if any
   reference is unresolved. Redact account names when sharing results.
5. To demonstrate the pre-fix failure with real data, create a separate worktree at
   `5d1feb32dddec12d80f0a5e90388ddb32901059c`. Copy
   `RealDataMigrationTest.java` from commit `e9fcdd6` and fixture copies there, then
   run that test. That test version matches the earlier schema; later versions use
   the simplified recipe tables. The component
   component parity assertions should identify the first lost value. This operation
   belongs only in the separate reproduction worktree; do not alter the fixed branch.
6. For the actual UI path, use a disposable RuneLite profile with copies of the data.
   Compare before first startup, immediately after migration, and after restart.
   Keep a separate copy of the rewritten JSON at each stage. If the automated test
   passes but the UI still breaks immediately, capture logs and those stage snapshots
   to isolate the account preparation/save path from the SQLite reload path.

## Limits and recovery

- The release still has one initial schema, version 1. This fix changes that initial
  schema; use a fresh temporary database for reproduction. Development databases
  created by earlier PR builds need regeneration from an intact JSON snapshot.
- The fix cannot reconstruct prices already missing from both JSON and its referenced
  history. Do not replace such values with zero or infer per-component prices from
  aggregate profit. Recover an intact backup first.
- Keeping embedded recipe snapshots increases JSON size relative to the lossy
  UUID-only representation. This is intentional to preserve independent recipe data.
