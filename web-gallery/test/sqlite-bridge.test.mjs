import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {createRequire} from "node:module";
import {fileURLToPath} from "node:url";
import vm from "node:vm";
import {execFileSync} from "node:child_process";
import {SqliteBridge} from "../site/sqlite-bridge.js";

// Load the same vendored browser distribution as CommonJS without another npm copy.
const vendor = new URL("../site/vendor/", import.meta.url);
const module = {exports: {}};
const context = {module, exports: module.exports, require: createRequire(import.meta.url),
  __dirname: fileURLToPath(vendor), __filename: fileURLToPath(new URL("sql-wasm.js", vendor)),
  process, console, Buffer, TextDecoder, TextEncoder, URL, fetch, WebAssembly, setTimeout, clearTimeout};
vm.runInNewContext(readFileSync(new URL("sql-wasm.js", vendor), "utf8"), context);
const SQL = await module.exports({locateFile: name => fileURLToPath(new URL(name, vendor))});

function call(bridge, operation, sql, parameters = []) {
  return JSON.parse(bridge.execute(operation, sql, JSON.stringify(parameters)));
}
function successful(bridge, operation, sql, parameters = []) {
  const response = call(bridge, operation, sql, parameters);
  assert.equal(response.error, undefined, response.error);
  return response;
}
function database() {
  const bridge = new SqliteBridge(new SQL.Database());
  successful(bridge, "update", "CREATE TABLE accounts(id INTEGER PRIMARY KEY, name TEXT UNIQUE, amount INTEGER, note TEXT)");
  successful(bridge, "update", "CREATE TABLE trades(id INTEGER PRIMARY KEY, account_id INTEGER REFERENCES accounts(id), value INTEGER)");
  return bridge;
}

test("writes exact int64 values and returns generated keys, nulls, and ignored-insert counts", () => {
  const bridge = database();
  try {
    assert.deepEqual(successful(bridge, "update", "INSERT INTO accounts(name, amount, note) VALUES(?, ?, ?)",
      ["Synthetic", "9007199254740993", null]), {changes: 1, lastInsertId: "1"});
    assert.deepEqual(successful(bridge, "query", "SELECT amount, typeof(amount), note FROM accounts"),
      {columns: ["amount", "typeof(amount)", "note"], rows: [["9007199254740993", "integer", null]]});
    assert.deepEqual(successful(bridge, "update", "INSERT INTO accounts(name) VALUES(?) ON CONFLICT(name) DO NOTHING",
      ["Synthetic"]), {changes: 0, lastInsertId: null});
    assert.match(call(bridge, "update", "INSERT INTO accounts(amount) VALUES(?)", [9007199254740992]).error, /decimal strings/);
  } finally { bridge.close(); }
});

test("a 500-row prepared batch binds each row independently within its transaction", () => {
  const bridge = database();
  try {
    successful(bridge, "control", "BEGIN");
    const rows = Array.from({length: 500}, (_, index) => [String(index + 1), "Account " + index,
      index % 2 ? null : "9223372036854775807", index % 2 ? "bound text" : null]);
    const result = successful(bridge, "batch", "INSERT INTO accounts(id, name, amount, note) VALUES(?, ?, ?, ?)", rows);
    assert.deepEqual(result.counts, Array(500).fill(1));
    successful(bridge, "control", "COMMIT");
    assert.deepEqual(successful(bridge, "query", "SELECT COUNT(*), COUNT(amount), COUNT(note) FROM accounts").rows,
      [["500", "250", "250"]]);
  } finally { bridge.close(); }
});

test("foreign-key batch failure can roll back the whole account without changing prior commits", () => {
  const bridge = database();
  try {
    successful(bridge, "update", "INSERT INTO accounts(id, name) VALUES(1, 'Committed')");
    successful(bridge, "control", "BEGIN");
    successful(bridge, "update", "INSERT INTO accounts(id, name) VALUES(2, 'Pending')");
    assert.match(call(bridge, "batch", "INSERT INTO trades(account_id, value) VALUES(?, ?)",
      [["2", "10"], ["999", "20"]]).error, /FOREIGN KEY/);
    successful(bridge, "control", "ROLLBACK");
    assert.deepEqual(successful(bridge, "query", "SELECT id FROM accounts").rows, [["1"]]);
    assert.deepEqual(successful(bridge, "query", "SELECT COUNT(*) FROM trades").rows, [["0"]]);
  } finally { bridge.close(); }
});

test("the downloadable snapshot freezes committed data and restores foreign keys for later GE writes", () => {
  const bridge = database();
  try {
    successful(bridge, "update", "PRAGMA user_version=1");
    successful(bridge, "update", "INSERT INTO accounts(id, name, amount) VALUES(1, 'Converted', ?)", ["9007199254740993"]);
    successful(bridge, "control", "BEGIN");
    assert.throws(() => bridge.snapshot(), /Finish the SQLite transaction/);
    successful(bridge, "control", "COMMIT");
    const converted = bridge.snapshot();
    assert.deepEqual(successful(bridge, "query", "PRAGMA foreign_keys").rows, [["1"]]);
    successful(bridge, "update", "INSERT INTO trades(account_id, value) VALUES(1, 50)");
    assert.match(call(bridge, "update", "INSERT INTO trades(account_id, value) VALUES(999, 50)").error, /FOREIGN KEY/);
    const result = JSON.parse(execFileSync("python3", ["-c", String.raw`
import base64, json, pathlib, sqlite3, sys, tempfile
with tempfile.TemporaryDirectory() as directory:
    path = pathlib.Path(directory) / 'download.db'
    path.write_bytes(base64.b64decode(sys.stdin.read()))
    with sqlite3.connect(path.as_uri() + '?mode=ro&immutable=1', uri=True) as connection:
        print(json.dumps({
            'integrity': connection.execute('PRAGMA integrity_check').fetchone()[0],
            'version': connection.execute('PRAGMA user_version').fetchone()[0],
            'amount': str(connection.execute('SELECT amount FROM accounts').fetchone()[0]),
            'trades': connection.execute('SELECT COUNT(*) FROM trades').fetchone()[0]
        }))
`], {input: Buffer.from(converted).toString("base64")}));
    assert.deepEqual(result, {integrity: "ok", version: 1, amount: "9007199254740993", trades: 0});
    assert.deepEqual(successful(bridge, "query", "SELECT COUNT(*) FROM trades").rows, [["1"]]);
  } finally { bridge.close(); }
});

test("unsupported backup paths and transaction misuse fail explicitly, and close is idempotent", () => {
  const bridge = database();
  assert.match(call(bridge, "update", "VACUUM INTO '/files/example/pre-migration.db'").error, /backup is unavailable/);
  assert.match(call(bridge, "update", "BEGIN").error, /transaction bridge/);
  assert.match(call(bridge, "control", "COMMIT").error, /No SQLite transaction/);
  successful(bridge, "control", "BEGIN");
  assert.match(call(bridge, "control", "BEGIN").error, /already active/);
  successful(bridge, "control", "CLOSE");
  successful(bridge, "control", "CLOSE");
  assert.match(call(bridge, "query", "SELECT 1").error, /closed/);
});
