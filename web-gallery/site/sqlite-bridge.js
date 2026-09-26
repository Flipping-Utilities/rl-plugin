/** JDBC operations against one session-owned sql.js database. */
export class SqliteBridge {
  constructor(database) {
    this.database = database;
    this.inTransaction = false;
    this.closed = false;
    this.database.run("PRAGMA foreign_keys = ON");
  }

  execute(operation, sql, parametersJson) {
    try {
      if (operation === "control" && sql === "CLOSE") {
        this.close();
        return "{}";
      }
      if (this.closed) throw new Error("The SQLite session is closed.");
      if (typeof sql !== "string" || !sql.trim()) throw new Error("Missing SQLite statement.");
      const parameters = JSON.parse(parametersJson);
      if (!Array.isArray(parameters)) throw new Error("SQLite parameters must be an array.");
      let response;
      if (operation === "control") response = this.control(sql, parameters);
      else if (operation === "batch") response = this.batch(sql, parameters);
      else if (operation === "query" || operation === "update") response = this.statement(operation, sql, parameters);
      else throw new Error("Unsupported SQLite operation: " + operation);
      return JSON.stringify(response, (_key, value) => typeof value === "bigint" ? value.toString() : value);
    } catch (error) {
      return JSON.stringify({error: error?.message || String(error)});
    }
  }

  control(sql, parameters) {
    if (parameters.length) throw new Error("Transaction controls do not accept parameters.");
    if (!["BEGIN", "COMMIT", "ROLLBACK"].includes(sql)) throw new Error("Unsupported SQLite transaction control.");
    if (sql === "BEGIN" && this.inTransaction) throw new Error("A SQLite transaction is already active.");
    if (sql !== "BEGIN" && !this.inTransaction) throw new Error("No SQLite transaction is active.");
    this.database.run(sql);
    this.inTransaction = sql === "BEGIN";
    return {};
  }

  checkStatement(sql) {
    if (/^\s*VACUUM\s+INTO\b/i.test(sql)) {
      // Java's filesystem and sql.js's filesystem are separate. MigrationService treats
      // this backup as best effort; the original selected bytes remain unchanged.
      throw new Error("Pre-migration file backup is unavailable in the browser; selected originals are unchanged.");
    }
    if (/^\s*(?:BEGIN|COMMIT|END|ROLLBACK|SAVEPOINT|RELEASE|ATTACH|DETACH)\b/i.test(sql)) {
      throw new Error("Use the SQLite transaction bridge for transaction controls.");
    }
  }

  checkParameters(parameters) {
    if (!Array.isArray(parameters) || parameters.some(value => value !== null && typeof value !== "string"
      && typeof value !== "boolean" && !(typeof value === "number" && Number.isSafeInteger(value)))) {
      throw new Error("SQLite values must be strings, safe integers, booleans or null; encode int64 values as decimal strings.");
    }
  }

  statement(operation, sql, parameters) {
    this.checkStatement(sql);
    this.checkParameters(parameters);
    const statement = this.database.prepare(sql);
    try {
      statement.bind(parameters);
      if (operation === "query") {
        const columns = statement.getColumnNames(), rows = [];
        while (statement.step()) rows.push(statement.get(null, {useBigInt: true}));
        return {columns, rows};
      }
      statement.step();
      const changes = this.database.getRowsModified();
      let lastInsertId = null;
      if (changes > 0 && /^\s*(?:INSERT|REPLACE)\b/i.test(sql)) {
        const key = this.database.prepare("SELECT last_insert_rowid()");
        try {
          if (key.step()) lastInsertId = key.get(null, {useBigInt: true})[0].toString();
        } finally { key.free(); }
      }
      return {changes, lastInsertId};
    } finally { statement.free(); }
  }

  batch(sql, batch) {
    this.checkStatement(sql);
    for (const parameters of batch) this.checkParameters(parameters);
    const statement = this.database.prepare(sql);
    try {
      const counts = [];
      for (const parameters of batch) {
        statement.run(parameters);
        counts.push(this.database.getRowsModified());
      }
      return {counts};
    } finally { statement.free(); }
  }

  snapshot() {
    if (this.closed) throw new Error("The SQLite session is closed.");
    if (this.inTransaction) throw new Error("Finish the SQLite transaction before downloading its snapshot.");
    // sql.js export closes/reopens SQLite, which rolls back an open transaction and
    // resets connection pragmas. Every statement above is freed before returning.
    try { return this.database.export(); }
    finally { this.database.run("PRAGMA foreign_keys = ON"); }
  }

  close() {
    if (this.closed) return;
    this.database.close();
    this.closed = true;
    this.inTransaction = false;
  }
}
