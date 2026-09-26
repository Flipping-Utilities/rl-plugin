import {test} from "node:test";
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {planImport, snapshotSqlite, normalizeImportPath} from "../site/import-data.js";

const encode = value => new TextEncoder().encode(value);
const entry = (path, text = "{}") => ({path, bytes: encode(text)});

// SQLite itself produces the fixtures, including valid WAL checksums and spilled
// uncommitted pages. Expected rows come from a separate native SQLite reader.
const fixtures = JSON.parse(execFileSync("python3", ["-c", String.raw`
import base64, json, pathlib, sqlite3, tempfile
result = {}
with tempfile.TemporaryDirectory() as folder:
    path = pathlib.Path(folder) / "flipping.db"
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA page_size=512")
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA wal_autocheckpoint=0")
    connection.execute("CREATE TABLE rows (id INTEGER PRIMARY KEY, value TEXT)")
    connection.commit()
    connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    def capture(name):
        with sqlite3.connect(path) as reader:
            expected = reader.execute("SELECT * FROM rows ORDER BY id").fetchall()
        result[name] = {
            "database": base64.b64encode(path.read_bytes()).decode(),
            "wal": base64.b64encode(path.with_name("flipping.db-wal").read_bytes()).decode(),
            "rows": expected
        }
    connection.executemany("INSERT INTO rows VALUES (?, ?)", [(i, "a" * 100) for i in range(400)])
    connection.commit()
    capture("append")
    connection.execute("UPDATE rows SET value='first update' WHERE id < 20")
    connection.commit()
    connection.execute("UPDATE rows SET value='last update' WHERE id < 20")
    connection.commit()
    capture("repeated")
    connection.execute("DELETE FROM rows WHERE id >= 40")
    connection.commit()
    connection.execute("VACUUM")
    capture("truncate")
    connection.execute("PRAGMA wal_checkpoint(FULL)")
    connection.execute("INSERT INTO rows VALUES (500, 'after checkpoint')")
    connection.commit()
    capture("reset")
    connection.execute("PRAGMA cache_size=3")
    connection.executemany("INSERT INTO rows VALUES (?, ?)", [(i, 'unfinished' * 100) for i in range(1000, 1500)])
    capture("unfinished")
    connection.rollback()
    connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    capture("emptyWal")
    connection.close()
print(json.dumps(result))
`], {maxBuffer: 16 * 1024 * 1024}));

const bytes = base64 => new Uint8Array(Buffer.from(base64, "base64"));
function nativeRows(database) {
  return JSON.parse(execFileSync("python3", ["-c", String.raw`
import base64, json, pathlib, sqlite3, sys, tempfile
with tempfile.TemporaryDirectory() as folder:
    path = pathlib.Path(folder) / "snapshot.db"
    path.write_bytes(base64.b64decode(sys.stdin.read()))
    with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True) as connection:
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        print(json.dumps(connection.execute("SELECT * FROM rows ORDER BY id").fetchall()))
`], {input: Buffer.from(database).toString("base64"), maxBuffer: 16 * 1024 * 1024}));
}

for (const [name, fixture] of Object.entries(fixtures)) {
  test("WAL snapshot matches native SQLite: " + name, () => {
    const database = bytes(fixture.database);
    const wal = bytes(fixture.wal);
    const originalDatabase = database.slice();
    const originalWal = wal.slice();
    const snapshot = snapshotSqlite(database, wal);
    assert.deepEqual(nativeRows(snapshot.bytes), fixture.rows);
    assert.equal(snapshot.bytes[18], 1);
    assert.equal(snapshot.bytes[19], 1);
    assert.deepEqual(database, originalDatabase);
    assert.deepEqual(wal, originalWal);
    if (["unfinished", "reset"].includes(name)) assert.ok(snapshot.warnings.length);
  });
}

test("partial trailing WAL frame preserves the last committed snapshot", () => {
  const fixture = fixtures.unfinished;
  const wal = bytes(fixture.wal);
  const snapshot = snapshotSqlite(bytes(fixture.database), wal.subarray(0, wal.length - 200));
  assert.deepEqual(nativeRows(snapshot.bytes), fixture.rows);
  assert.ok(snapshot.warnings.length);
});

test("missing WAL is explicit and a checkpointed database still opens", () => {
  const snapshot = snapshotSqlite(bytes(fixtures.emptyWal.database));
  assert.equal(snapshot.warnings.length, 1);
  assert.deepEqual(nativeRows(snapshot.bytes), fixtures.emptyWal.rows);
});

test("rejects incomplete database and corrupt WAL headers/frames", () => {
  const database = bytes(fixtures.append.database);
  assert.throws(() => snapshotSqlite(encode("not a db")), /not a SQLite/);
  assert.throws(() => snapshotSqlite(database.subarray(0, database.length - 1)), /incomplete/);
  const wal = bytes(fixtures.append.wal);
  assert.throws(() => snapshotSqlite(database, wal.subarray(0, 20)), /header is incomplete/);
  const badHeader = wal.slice();
  badHeader[24] ^= 1;
  assert.throws(() => snapshotSqlite(database, badHeader), /header checksum/);
  const badFrame = wal.slice();
  badFrame[60] ^= 1;
  assert.throws(() => snapshotSqlite(database, badFrame), /frame checksum/);
});

test("folder import includes only plugin saves and RuneLite settings", () => {
  const entries = [
    entry(".runelite/settings.properties", "flipping.dataSource=JSON"),
    entry(".runelite/credentials.properties", "never copy"),
    entry(".runelite/flipping/Alice.json"),
    entry(".runelite/flipping/Alice.json.pre-migration"),
    entry(".runelite/flipping/accountwide.json"),
    entry(".runelite/flipping/nested/private.json"),
    entry(".runelite/flipping/flipping.db", "damaged stale DB"),
    entry(".runelite/cache/random.json"),
  ];
  const plan = planImport(entries, {sourceKind: "folder"});
  assert.equal(plan.database, null);
  assert.deepEqual(plan.files.map(file => file.path), [
    "flipping/accountwide.json", "flipping/Alice.json", "flipping/Alice.json.pre-migration", "settings.properties"
  ].sort((a, b) => a.localeCompare(b)));
  plan.files[0].bytes[0] = 0;
  assert.equal(entries[4].bytes[0], "{".charCodeAt(0));
});

test("JSON selection respects Java properties escapes, continuations and last value", () => {
  const properties = "flipping.dataSource=SQLITE\nflipping.data\\u0053ource : JSO\\\n  N\n";
  const plan = planImport([
    entry("root/settings.properties", properties),
    entry("root/flipping/Alice.json"),
    entry("root/flipping/flipping.db", "stale")
  ]);
  assert.equal(plan.database, null);
});

test("resync marker ignores stale DB and preserves JSON/pre-migration saves", () => {
  const plan = planImport([
    entry("flipping/Alice.json"), entry("flipping/Alice.json.pre-migration"),
    entry("flipping/flipping.db.needs-resync", ""), entry("flipping/flipping.db", "damaged")
  ]);
  assert.equal(plan.database, null);
  assert.equal(plan.files.length, 3);
});

test("direct plugin-folder settings apply and unrelated nested JSON is excluded", () => {
  const plan = planImport([
    entry("flipping/settings.properties", "flipping.dataSource=JSON"),
    entry("flipping/Alice.json"), entry("flipping/flipping.db", "stale")
  ], {sourceKind: "folder"});
  assert.equal(plan.database, null);
  assert.ok(plan.files.some(file => file.path === "settings.properties"));
  assert.throws(() => planImport([entry(".runelite/cache/secret.json")], {sourceKind: "folder"}), /No flipping folder/);
});

test("folder DB import replays WAL and includes companion JSON metadata", () => {
  const fixture = fixtures.repeated;
  const plan = planImport([
    {path: "root/flipping/flipping.db", bytes: bytes(fixture.database)},
    {path: "root/flipping/flipping.db-wal", bytes: bytes(fixture.wal)},
    entry("root/flipping/flipping.db-shm", "unused"),
    entry("root/flipping/accountwide.json"), entry("root/flipping/Alice.json.pre-migration"),
    entry("root/settings.properties", "flipping.dataSource=SQLITE")
  ], {sourceKind: "folder"});
  assert.deepEqual(nativeRows(plan.database), fixture.rows);
  assert.equal(plan.files.length, 3);
  assert.ok(plan.files.every(file => !file.path.includes(".db")));
});

test("individual arbitrary-name DB ignores folder dataSource/resync and accepts its WAL", () => {
  const fixture = fixtures.repeated;
  const plan = planImport([
    {path: "chosen.db", bytes: bytes(fixture.database)},
    {path: "chosen.db-wal", bytes: bytes(fixture.wal)},
    entry("settings.properties", "flipping.dataSource=JSON"),
    entry("flipping.db.needs-resync", "")
  ], {sourceKind: "file"});
  assert.deepEqual(nativeRows(plan.database), fixture.rows);
  assert.equal(plan.sourceLabel, "chosen.db");
  assert.ok(!plan.files.some(file => file.path === "settings.properties"));
});

test("auto file selection accepts arbitrary DB names with accountwide and migration companions", () => {
  const fixture = fixtures.repeated;
  const plan = planImport([
    {path: "custom-test.sqlite", bytes: bytes(fixture.database)},
    {path: "custom-test.sqlite-wal", bytes: bytes(fixture.wal)},
    entry("accountwide.json"), entry("Alice.json.pre-migration"),
    entry("settings.properties", "flipping.dataSource=JSON")
  ]);
  assert.deepEqual(nativeRows(plan.database), fixture.rows);
  assert.equal(plan.sourceLabel, "custom-test.sqlite");
  assert.deepEqual(plan.files.map(file => file.path), ["flipping/accountwide.json", "flipping/Alice.json.pre-migration"]);
});

test("auto selection still respects folder settings with canonical flipping.db", () => {
  const plan = planImport([
    {path: "flipping.db", bytes: bytes(fixtures.repeated.database)},
    entry("settings.properties", "flipping.dataSource=JSON"), entry("Alice.json")
  ]);
  assert.equal(plan.database, null);
});

test("rejects active rollback journals instead of importing inconsistent pages", () => {
  assert.throws(() => planImport([
    {path: "db.sqlite", bytes: bytes(fixtures.emptyWal.database)},
    entry("db.sqlite-journal", "hot journal")
  ], {sourceKind: "file"}), /active rollback journal/);
});

test("rejects traversal, duplicates, ambiguous roots and multiple databases", () => {
  for (const path of ["../Alice.json", "root/../Alice.json", "/Alice.json", "C:\\Alice.json", "root/./Alice.json", "root//Alice.json", "bad\0.json"]) {
    assert.throws(() => normalizeImportPath(path));
  }
  assert.equal(normalizeImportPath("root\\Alice.json"), "root/Alice.json");
  assert.throws(() => planImport([entry("root/Alice.json"), entry("root\\Alice.json")]), /duplicate/);
  assert.throws(() => planImport([entry("one/flipping/Alice.json"), entry("two/flipping/Bob.json")]), /one RuneLite/);
  assert.throws(() => planImport([entry("one/Alice.json"), entry("two/Bob.json")]), /multiple data/);
  assert.throws(() => planImport([
    {path: "one.db", bytes: bytes(fixtures.emptyWal.database)},
    {path: "two.db", bytes: bytes(fixtures.emptyWal.database)}
  ]), /one SQLite/);
});
