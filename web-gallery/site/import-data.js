// Browser-only snapshot preparation. No source file is ever modified or uploaded.
// WAL layout/checksums: https://sqlite.org/fileformat2.html#walformat
export const MAX_SNAPSHOT_BYTES = 256 * 1024 * 1024;

const sqliteMagic = new TextEncoder().encode("SQLite format 3\0");
const basename = path => path.slice(path.lastIndexOf("/") + 1);
const dirname = path => path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "";
const join = (directory, name) => directory ? directory + "/" + name : name;
const isSave = name => name.endsWith(".json") || name.endsWith(".json.pre-migration")
  || name === "flipping.db.needs-resync";
const isDatabase = bytes => bytes.length >= sqliteMagic.length
  && sqliteMagic.every((byte, index) => bytes[index] === byte);

export function normalizeImportPath(path) {
  if (typeof path !== "string" || !path || /^[\\/]/.test(path) || /[:\0]/.test(path)) {
    throw new Error("Choose files with relative paths, without drive letters or special path characters.");
  }
  const parts = path.replaceAll("\\", "/").split("/");
  if (parts.some(part => !part || part === "." || part === "..")) {
    throw new Error("Unsafe import path: " + path);
  }
  return parts.join("/");
}

/**
 * entries: [{path: relative browser path, bytes: Uint8Array}].
 * sourceKind identifies a folder picker/drop or an individual DB/file selection.
 * files are relative to the future .runelite directory; database is an independent
 * checkpointed SQLite image to decode with sql.js, never a file to persist in Java.
 */
export function planImport(entries, {sourceKind = "auto"} = {}) {
  if (!["auto", "folder", "file"].includes(sourceKind)) throw new Error("Unknown import source kind.");
  if (!Array.isArray(entries) || !entries.length) throw new Error("The selected folder contains no files.");
  const selected = new Map();
  for (const entry of entries) {
    const path = normalizeImportPath(entry.path);
    if (!(entry.bytes instanceof Uint8Array)) throw new Error("Import file has no byte data: " + path);
    if (selected.has(path)) throw new Error("The selection contains a duplicate path: " + path);
    selected.set(path, entry.bytes);
  }

  const pluginDirectories = new Set();
  for (const path of selected.keys()) {
    const parts = path.split("/");
    for (let index = 0; index < parts.length - 1; index++) {
      if (parts[index] === "flipping") pluginDirectories.add(parts.slice(0, index + 1).join("/"));
    }
  }
  if (pluginDirectories.size > 1) throw new Error("Choose one RuneLite or flipping folder at a time.");

  let pluginDirectory = pluginDirectories.values().next().value;
  let databasePath = null;
  let individualDatabase = false;
  if (sourceKind === "file" || (sourceKind === "auto" && !pluginDirectory)) {
    const databases = [...selected].filter(([path, bytes]) => isDatabase(bytes)
      && !/-(wal|shm|journal)$/.test(path));
    if (databases.length > 1) throw new Error("Choose one SQLite database at a time.");
    if (databases.length === 1 && (sourceKind === "file"
      || (basename(databases[0][0]) !== "flipping.db"
        && [...selected.keys()].every(path => dirname(path) === dirname(databases[0][0])))
      || [...selected.keys()].every(path => path === databases[0][0]
        || ["-wal", "-shm", "-journal"].some(suffix => path === databases[0][0] + suffix)))) {
      databasePath = databases[0][0];
      pluginDirectory = dirname(databasePath);
      individualDatabase = true;
    }
  }
  if (pluginDirectory === undefined) {
    const saveDirectories = new Set([...selected.keys()]
      .filter(path => basename(path) === "flipping.db" || isSave(basename(path)))
      .map(dirname));
    if (saveDirectories.size > 1) throw new Error("The selection contains multiple data folders. Choose one flipping folder.");
    pluginDirectory = saveDirectories.values().next().value;
    if (sourceKind === "folder" && pluginDirectory?.includes("/")) {
      throw new Error("No flipping folder was found. Select the plugin data folder directly.");
    }
  }
  if (pluginDirectory === undefined) throw new Error("No Flipping Utilities saves or SQLite database were found.");

  const files = [];
  const warnings = [];
  // RuneLite folder settings are one level above flipping/. Direct plugin-folder
  // settings match desktop SandboxData's fallback behavior.
  const settingsPath = join(basename(pluginDirectory) === "flipping" ? dirname(pluginDirectory) : pluginDirectory,
    "settings.properties");
  const settings = selected.get(settingsPath) ?? selected.get(join(pluginDirectory, "settings.properties"));
  if (settings && !individualDatabase) files.push({path: "settings.properties", bytes: settings.slice()});
  const jsonSelected = !individualDatabase && settings
    && readProperties(settings).get("flipping.dataSource")?.trim().toUpperCase() === "JSON";
  const resync = !individualDatabase && selected.has(join(pluginDirectory, "flipping.db.needs-resync"));
  if (!databasePath && !jsonSelected && !resync && selected.has(join(pluginDirectory, "flipping.db"))) {
    databasePath = join(pluginDirectory, "flipping.db");
  }
  for (const [path, bytes] of selected) {
    if (dirname(path) === pluginDirectory && isSave(basename(path))) {
      // SQLite supplies the account histories. Do not duplicate stale JSON histories
      // and migration backups into Java's virtual filesystem as well.
      if (databasePath && !["accountwide.json", "backupcheckpoints.special.json"].includes(basename(path))) continue;
      files.push({path: "flipping/" + basename(path), bytes: bytes.slice()});
    }
  }
  let database = null;
  if (databasePath) {
    const journal = selected.get(databasePath + "-journal");
    if (journal && journal.length > 0 && journal.slice(0, 8).some(byte => byte !== 0)) {
      throw new Error("This database has an active rollback journal. Close RuneLite and select its folder again.");
    }
    const snapshot = snapshotSqlite(selected.get(databasePath), selected.get(databasePath + "-wal"));
    database = snapshot.bytes;
    warnings.push(...snapshot.warnings);
  }
  if (!database && !files.some(file => file.path.startsWith("flipping/"))) {
    throw new Error("No Flipping Utilities saves were found in the selected folder.");
  }
  files.sort((left, right) => left.path.localeCompare(right.path));
  return {files, database, sourceLabel: individualDatabase ? basename(databasePath) : pluginDirectory || "Selected files", warnings};
}

// java.util.Properties.load(InputStream) uses ISO-8859-1, escaped separators,
// Unicode escapes and odd-backslash continuations, including in property names.
function readProperties(bytes) {
  const text = Array.from(bytes, byte => String.fromCharCode(byte)).join("");
  const lines = text.split(/\r\n|\n|\r/);
  const properties = new Map();
  for (let index = 0; index < lines.length; index++) {
    let line = lines[index].replace(/^[ \t\f]+/, "");
    if (!line || /^[#!]/.test(line)) continue;
    while ((line.match(/\\+$/)?.[0].length || 0) % 2 === 1) {
      line = line.slice(0, -1);
      if (index + 1 >= lines.length) break;
      line += lines[++index].replace(/^[ \t\f]+/, "");
    }
    let end = 0;
    while (end < line.length) {
      if (line[end] === "\\") { end += 2; continue; }
      if (/[=: \t\f]/.test(line[end])) break;
      end++;
    }
    let valueStart = end;
    while (/[ \t\f]/.test(line[valueStart] || "~")) valueStart++;
    if (/[=:]/.test(line[valueStart] || "~")) valueStart++;
    while (/[ \t\f]/.test(line[valueStart] || "~")) valueStart++;
    properties.set(unescapeProperty(line.slice(0, end)), unescapeProperty(line.slice(valueStart)));
  }
  return properties;
}

function unescapeProperty(text) {
  return text.replace(/\\(u[\s\S]{0,4}|[\s\S])/g, (_, escaped) => {
    if (escaped[0] === "u") {
      if (!/^u[0-9a-f]{4}$/i.test(escaped)) throw new Error("Invalid Unicode escape in settings.properties.");
      return String.fromCharCode(parseInt(escaped.slice(1), 16));
    }
    return ({t: "\t", n: "\n", r: "\r", f: "\f"})[escaped] ?? escaped;
  });
}

/** Reconstruct SQLite's last committed WAL snapshot in a new byte array. */
export function snapshotSqlite(database, wal) {
  if (!(database instanceof Uint8Array) || !isDatabase(database) || database.length < 100) {
    throw new Error("The selected file is not a SQLite database.");
  }
  const dbView = new DataView(database.buffer, database.byteOffset, database.byteLength);
  const rawPageSize = dbView.getUint16(16);
  const pageSize = rawPageSize === 1 ? 65536 : rawPageSize;
  if (pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) !== 0
    || database.length % pageSize !== 0 || database.length > MAX_SNAPSHOT_BYTES) {
    throw new Error("Invalid SQLite page size, incomplete file, or database larger than 256 MiB.");
  }
  if (![1, 2].includes(database[18]) || ![1, 2].includes(database[19])) {
    throw new Error("Unsupported SQLite database format.");
  }
  const warnings = [];
  let bytes = database.slice();
  if (wal?.length) {
    if (database[18] !== 2 || database[19] !== 2) {
      throw new Error("A WAL was selected for a database that is not in WAL mode. Close RuneLite and select the folder again.");
    }
    if (wal.length < 32) throw new Error("The SQLite WAL header is incomplete. Close RuneLite and select the folder again.");
    const view = new DataView(wal.buffer, wal.byteOffset, wal.byteLength);
    const magic = view.getUint32(0);
    if (![0x377f0682, 0x377f0683].includes(magic) || view.getUint32(4) !== 3007000
      || view.getUint32(8) !== pageSize) throw new Error("Invalid SQLite WAL header or mismatched database page size.");
    const littleEndian = magic === 0x377f0682;
    let checksum = walChecksum(view, 0, 24, littleEndian, [0, 0]);
    if (checksum[0] !== view.getUint32(24) || checksum[1] !== view.getUint32(28)) {
      throw new Error("The SQLite WAL header checksum is invalid. Close RuneLite and select the folder again.");
    }
    const frames = [];
    let committedFrames = 0;
    let committedPages = 0;
    let offset = 32;
    for (; offset + 24 + pageSize <= wal.length; offset += 24 + pageSize) {
      // A reset WAL can retain frames from the previous checkpoint. Salts are
      // SQLite's boundary between that old tail and the current WAL generation.
      if (view.getUint32(offset + 8) !== view.getUint32(16)
        || view.getUint32(offset + 12) !== view.getUint32(20)) break;
      const nextChecksum = walChecksum(view, offset, 8, littleEndian, checksum);
      checksum = walChecksum(view, offset + 24, pageSize, littleEndian, nextChecksum);
      if (checksum[0] !== view.getUint32(offset + 16) || checksum[1] !== view.getUint32(offset + 20)) {
        throw new Error("A SQLite WAL frame checksum is invalid. Close RuneLite and select the folder again.");
      }
      const page = view.getUint32(offset);
      const size = view.getUint32(offset + 4);
      if (!page || page * pageSize > MAX_SNAPSHOT_BYTES || size * pageSize > MAX_SNAPSHOT_BYTES) {
        throw new Error("The SQLite WAL references an invalid page or a database larger than 256 MiB.");
      }
      frames.push({page, offset: offset + 24});
      if (size) { committedFrames = frames.length; committedPages = size; }
    }
    if (committedFrames) {
      bytes = new Uint8Array(committedPages * pageSize);
      bytes.set(database.subarray(0, bytes.length));
      for (let index = 0; index < committedFrames; index++) {
        const frame = frames[index];
        // A later transaction can truncate pages written by an earlier commit.
        if (frame.page <= committedPages) bytes.set(wal.subarray(frame.offset, frame.offset + pageSize), (frame.page - 1) * pageSize);
      }
      const snapshot = new DataView(bytes.buffer);
      snapshot.setUint32(28, committedPages);
      snapshot.setUint32(92, snapshot.getUint32(24));
    }
    if (frames.length > committedFrames || offset < wal.length) {
      warnings.push("Only committed WAL transactions were imported; an unfinished transaction or old WAL tail was ignored.");
    }
  } else if (!wal && (database[18] === 2 || database[19] === 2)) {
    warnings.push("This database uses WAL mode. Select its folder to include any companion -wal file, or close RuneLite before selecting the database alone.");
  }
  // sql.js deserializes one standalone database, without a VFS sidecar WAL.
  // https://sqlite.org/c3ref/deserialize.html
  bytes[18] = bytes[19] = 1;
  return {bytes, warnings};
}

function walChecksum(view, offset, length, littleEndian, initial) {
  let [first, second] = initial;
  for (let index = offset; index < offset + length; index += 8) {
    first = (first + view.getUint32(index, littleEndian) + second) >>> 0;
    second = (second + view.getUint32(index + 4, littleEndian) + first) >>> 0;
  }
  return [first, second];
}
