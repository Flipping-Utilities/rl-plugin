import { planImport } from "./import-data.js";

const status = document.querySelector("#status");
const launcher = document.querySelector("#launcher");
const reload = document.querySelector("#reload");
const drop = document.querySelector("#drop");
let busy = false;
let closeSandbox;
reload.addEventListener("click", () => {
  if (closeSandbox) {
    reload.disabled = true;
    status.textContent = "Discarding this session…";
    closeSandbox();
  } else location.reload();
});
document.querySelector("#start").addEventListener("click", () => launch("sandbox"));
document.querySelector("#gallery").addEventListener("click", () => launch("gallery"));
document.querySelector("#choose-folder").addEventListener("click", () => document.querySelector("#folder").click());
document.querySelector("#choose-file").addEventListener("click", () => document.querySelector("#files").click());
drop.addEventListener("click", () => document.querySelector("#files").click());
drop.addEventListener("keydown", event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); drop.click(); } });
for (const id of ["files", "folder"]) document.querySelector("#" + id).addEventListener("change", event => {
  const files = [...event.target.files];
  if (files.length) select(files.map(file => ({ path: file.webkitRelativePath || file.name, file })), id === "folder" ? "folder" : "auto");
});
for (const type of ["dragenter", "dragover"]) drop.addEventListener(type, event => { event.preventDefault(); drop.classList.add("dragover"); });
drop.addEventListener("dragleave", () => drop.classList.remove("dragover"));
drop.addEventListener("drop", async event => {
  event.preventDefault();
  drop.classList.remove("dragover");
  if (busy) return;
  // Capture entries synchronously: the drag data store closes after this event returns.
  const entries = [...event.dataTransfer.items].filter(item => item.kind === "file").map(item => item.webkitGetAsEntry?.());
  const fallback = [...event.dataTransfer.files];
  try {
    const files = entries.length && entries.every(Boolean) ? (await Promise.all(entries.map(readEntry))).flat()
      : fallback.map(file => ({ path: file.name, file }));
    await select(files, entries.some(entry => entry?.isDirectory) ? "folder" : "auto");
  } catch (error) { status.textContent = error.message; }
});

async function readEntry(entry, prefix = "") {
  const path = prefix + entry.name;
  if (entry.isFile) return [{ path, file: await new Promise((resolve, reject) => entry.file(resolve, reject)) }];
  const reader = entry.createReader();
  const children = [];
  for (;;) {
    const batch = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
    if (!batch.length) break;
    children.push(...batch);
  }
  return (await Promise.all(children.map(child => readEntry(child, path + "/")))).flat();
}

async function select(files, sourceKind) {
  if (busy) return;
  busy = true;
  try {
    status.textContent = "Reading selected plugin files…";
    // Do not read unrelated RuneLite cache files or credentials into the runtime.
    if (sourceKind === "folder") {
      const prefixes = new Set(files.map(({ path }) => path.match(/^((?:.*\/)?flipping\/)[^/]+$/)?.[1]).filter(Boolean));
      if (prefixes.size > 1) throw new Error("Choose one RuneLite or flipping folder at a time.");
      const prefix = prefixes.values().next().value;
      files = prefix ? files.filter(({ path }) => (path.startsWith(prefix) && !path.slice(prefix.length).includes("/"))
        || path === prefix.replace(/flipping\/$/, "settings.properties"))
        : files.filter(({ path }) => path.split("/").length <= 2);
    }
    const selected = files.filter(({ path }) => sourceKind !== "folder" || /(?:\.json(?:\.pre-migration)?|\.db(?:-wal|-shm|-journal)?|\.sqlite(?:3)?(?:-wal|-shm|-journal)?|settings\.properties|flipping\.db\.needs-resync)$/i.test(path));
    const bytes = await Promise.all(selected.map(async ({ path, file }) => ({ path, bytes: new Uint8Array(await file.arrayBuffer()) })));
    const plan = planImport(bytes, { sourceKind });
    busy = false;
    await launch("sandbox", plan);
  } catch (error) {
    busy = false;
    status.textContent = error.message || String(error);
  }
}

async function wikiFetch(url) {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:" || !["prices.runescape.wiki", "oldschool.runescape.wiki"].includes(parsed.hostname)) throw new Error("Unsupported Wiki URL");
    const response = await fetch(url, { credentials: "omit", redirect: "error", signal: AbortSignal.timeout(15000) });
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length > 10 * 1024 * 1024) throw new Error("Wiki response too large");
    let binary = "";
    for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
    return JSON.stringify({ status: response.status, type: response.headers.get("content-type") || "application/octet-stream", body: btoa(binary) });
  } catch (error) { return JSON.stringify({ error: error.message || String(error) }); }
}

async function launch(mode, plan = { files: [], database: null, sourceLabel: "Empty sandbox", warnings: [] }) {
  if (busy) return;
  busy = true;
  launcher.querySelectorAll("button, input").forEach(element => element.disabled = true);
  const started = performance.now();
  let database;
  let failed = false;
  try {
    if (plan.database) {
      status.textContent = "Opening SQLite snapshot…";
      const SQL = await initSqlJs({ locateFile: file => new URL("./vendor/" + file, import.meta.url).href });
      database = new SQL.Database(plan.database);
      database.run("PRAGMA query_only = ON");
    }
    status.textContent = "Loading Java 11…";
    if (typeof cheerpjInit !== "function") throw new Error("The CheerpJ runtime could not load. Check your connection and reload.");
    const response = await fetch("./manifest.json");
    if (!response.ok) throw new Error("The sandbox build manifest could not load.");
    const manifest = await response.json();
    const classPath = manifest.classpath.map(path => "/app" + new URL(path, document.baseURI).pathname).join(":");
    if (manifest.sourceCommit) document.querySelector("#build").textContent = "Build " + manifest.sourceCommit.slice(0, 7);
    const ready = () => {
      window.galleryReadyMs = Math.round(performance.now() - started);
      status.textContent = mode === "gallery" ? "Ready · Choose a state below. Export PNG downloads an image."
        : "Ready · " + plan.sourceLabel + ". Changes are discarded on reload." + (plan.warnings.length ? " " + plan.warnings.join(" ") : "");
      launcher.hidden = true;
      reload.hidden = false;
    };
    // Each launch gets a fresh virtual home; the original local files are never writable.
    const home = "/files/flipping-sandbox-" + crypto.randomUUID();
    await cheerpjInit({
      version: 11,
      status: "default",
      javaProperties: ["user.home=" + home, "java.io.tmpdir=/files", "flatlaf.useNativeLibrary=false"],
      overrideShortcuts: event => event.target.closest?.("#display") != null
        && ["Home", "End", "PageUp", "PageDown", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", " "].includes(event.key),
      natives: {
        Java_com_flippingutilities_ui_uiutilities_BrowserGallery_ready: ready,
        Java_com_flippingutilities_ui_uiutilities_BrowserSandbox_ready: ready,
        Java_com_flippingutilities_ui_uiutilities_BrowserSandbox_awaitClose: () => new Promise(resolve => { closeSandbox = resolve; }),
        Java_com_flippingutilities_ui_uiutilities_BrowserSandbox_closed: () => location.reload(),
        Java_com_flippingutilities_ui_uiutilities_BrowserSandbox_failed: (_lib, message) => {
          failed = true;
          closeSandbox = null;
          status.textContent = message;
          reload.disabled = false;
          reload.hidden = false;
        },
        Java_com_flippingutilities_ui_uiutilities_BrowserSandbox_fetch: (_lib, url) => wikiFetch(url),
        Java_com_flippingutilities_ui_uiutilities_BrowserSandbox_imported: (_lib, accounts) => {
          window.sandboxImportedAccounts = accounts;
          database?.close(); database = null;
        },
        Java_com_flippingutilities_ui_uiutilities_BrowserSqliteImporter_query: (_lib, sql, parameters) => {
          const statement = database.prepare(sql);
          try {
            statement.bind(JSON.parse(parameters));
            const columns = statement.getColumnNames(), rows = [];
            while (statement.step()) rows.push(statement.get(null, { useBigInt: true }));
            return JSON.stringify({ columns, rows }, (_key, value) => typeof value === "bigint" ? value.toString() : value);
          } finally { statement.free(); }
        }
      }
    });
    const files = plan.files.map((file, index) => {
      const staged = "/str/import-" + index;
      cheerpOSAddStringFile(staged, file.bytes);
      return { path: file.path, staged };
    });
    cheerpOSAddStringFile("/str/import.json", JSON.stringify({ files, sourceLabel: plan.sourceLabel, database: Boolean(database) }));
    document.querySelector("#display").hidden = false;
    cheerpjCreateDisplay(-1, -1, document.querySelector("#display"));
    status.textContent = "Opening " + (mode === "gallery" ? "Swing components" : "sandbox and imported data") + "…";
    const mainClass = mode === "gallery" ? manifest.mainClass : manifest.sandboxClass;
    const exitCode = await cheerpjRunMain(mainClass, classPath, ...(mode === "gallery" ? [] : ["/str/import.json"]));
    if (!failed) status.textContent = "The " + mode + " closed" + (exitCode ? " with code " + exitCode : "") + ". Start a new session to open it again.";
    reload.hidden = false;
  } catch (error) {
    database?.close();
    status.textContent = error.message || String(error);
    reload.hidden = false;
    console.error(error);
  }
}
