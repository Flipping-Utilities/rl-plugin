package com.flippingutilities.db;


import java.util.Arrays;
import java.util.List;

public final class SqliteSchema {

    // The initial released schema; every SQLite database starts here.
    public static final int SCHEMA_VERSION = 1;

    // PRAGMA for reading current version and for migrating to the current version
    public static final String PRAGMA_GET_USER_VERSION = "PRAGMA user_version";
    public static String getMigrationStatement() {
        return String.format("PRAGMA user_version = %d", SCHEMA_VERSION);
    }

    // CREATE TABLE statements (IF NOT EXISTS) in dependency order
    public static final String CREATE_TABLE_ACCOUNTS =
        "CREATE TABLE IF NOT EXISTS accounts (" +
        "  id INTEGER PRIMARY KEY," +
        "  display_name TEXT NOT NULL UNIQUE," +
        "  player_id TEXT," +
        "  session_start INTEGER," +
        "  accumulated_time INTEGER" +
        ");";

    public static final String CREATE_TABLE_ACTIVE_SLOTS =
        "CREATE TABLE IF NOT EXISTS active_slots (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER NOT NULL," +
        "  slot_index INTEGER NOT NULL," +
        "  offer_uuid TEXT," +
        "  item_id INTEGER," +
        "  is_buy INTEGER," +
        "  price INTEGER," +
        "  qty INTEGER," +
        "  total_qty INTEGER," +
        "  state TEXT," +
        "  time INTEGER," +
        "  trade_started_at INTEGER," +
        "  offer_json TEXT NOT NULL," +
        "  history_visible INTEGER NOT NULL DEFAULT 0," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    public static final String CREATE_TABLE_TRADES =
        "CREATE TABLE IF NOT EXISTS trades (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER NOT NULL," +
        "  item_id INTEGER NOT NULL," +
        "  uuid TEXT," +
        "  timestamp INTEGER NOT NULL," +
        "  qty INTEGER NOT NULL," +
        "  price INTEGER NOT NULL," +
        "  is_buy INTEGER NOT NULL," +
        "  tax INTEGER DEFAULT 0," +
        "  offer_json TEXT NOT NULL," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)," +
        "  UNIQUE(account_id, uuid)" +
        ");";

    public static final String CREATE_TABLE_EVENTS =
        "CREATE TABLE IF NOT EXISTS events (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER NOT NULL," +
        "  timestamp INTEGER NOT NULL," +
        "  type TEXT NOT NULL," +
        "  cost INTEGER," +
        "  profit INTEGER," +
        "  note TEXT," +
        "  natural_key TEXT," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    // event_id is nullable: a row with event_id=NULL represents a "voided" trade
    // (consumed by the user themselves, not by any flip/recipe event).
    public static final String CREATE_TABLE_CONSUMED_TRADE =
        "CREATE TABLE IF NOT EXISTS consumed_trade (" +
        "  trade_id INTEGER NOT NULL," +
        "  event_id INTEGER," +
        "  qty INTEGER NOT NULL," +
        "  PRIMARY KEY (trade_id, event_id)," +
        "  FOREIGN KEY(trade_id) REFERENCES trades(id)," +
        "  FOREIGN KEY(event_id) REFERENCES events(id)" +
        ");";

    public static final String CREATE_TABLE_RECIPE_FLIPS =
        "CREATE TABLE IF NOT EXISTS recipe_flips (" +
        "  id INTEGER PRIMARY KEY," +
        "  event_id INTEGER," +
        "  recipe_key TEXT," +
        "  coin_cost INTEGER," +
        "  FOREIGN KEY(event_id) REFERENCES events(id)" +
        ");";

    public static final String CREATE_TABLE_RECIPE_FLIP_INPUTS =
        "CREATE TABLE IF NOT EXISTS recipe_flip_inputs (" +
        "  id INTEGER PRIMARY KEY," +
        "  recipe_flip_id INTEGER," +
        "  item_id INTEGER," +
        "  offer_uuid TEXT," +
        "  amount_consumed INTEGER," +
        "  FOREIGN KEY(recipe_flip_id) REFERENCES recipe_flips(id)" +
        ");";

    public static final String CREATE_TABLE_RECIPE_FLIP_OUTPUTS =
        "CREATE TABLE IF NOT EXISTS recipe_flip_outputs (" +
        "  id INTEGER PRIMARY KEY," +
        "  recipe_flip_id INTEGER," +
        "  item_id INTEGER," +
        "  offer_uuid TEXT," +
        "  amount_consumed INTEGER," +
        "  FOREIGN KEY(recipe_flip_id) REFERENCES recipe_flips(id)" +
        ");";

    public static final String CREATE_TABLE_SETTINGS =
        "CREATE TABLE IF NOT EXISTS settings (" +
        "  key TEXT PRIMARY KEY," +
        "  value TEXT" +
        ");";

    public static final String CREATE_TABLE_GE_LIMIT_STATE =
        "CREATE TABLE IF NOT EXISTS ge_limit_state (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER NOT NULL," +
        "  item_id INTEGER NOT NULL," +
        "  next_refresh INTEGER," +
        "  items_bought INTEGER," +
        "  items_bought_complete INTEGER DEFAULT 0," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    public static final String CREATE_TABLE_ITEM_FAVORITES =
        "CREATE TABLE IF NOT EXISTS item_favorites (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER NOT NULL," +
        "  item_id INTEGER NOT NULL," +
        "  is_favorite INTEGER DEFAULT 0," +
        "  favorite_code TEXT DEFAULT '1'," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)," +
        "  UNIQUE(account_id, item_id)" +
        ");";

    public static final String CREATE_TABLE_ITEM_VISIBILITY =
        "CREATE TABLE IF NOT EXISTS item_visibility (" +
        "  account_id INTEGER NOT NULL," +
        "  item_id INTEGER NOT NULL," +
        "  is_visible INTEGER NOT NULL," +
        "  PRIMARY KEY(account_id, item_id)," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    // INDEX statements for common query patterns
    public static final String INDEX_ACTIVE_SLOTS_ACCOUNT_TIME =
        "CREATE INDEX IF NOT EXISTS idx_active_slots_account_time ON active_slots (account_id, time)";

    public static final String INDEX_TRADES_ACCOUNT_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_trades_account_timestamp ON trades (account_id, timestamp)";

    public static final String INDEX_TRADES_ITEM_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_trades_item_timestamp ON trades (item_id, timestamp)";

    public static final String INDEX_TRADES_ACCOUNT_ITEM_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_trades_account_item_timestamp ON trades (account_id, item_id, timestamp)";

    public static final String INDEX_CONSUMED_TRADE_TRADE_ID =
        "CREATE INDEX IF NOT EXISTS idx_consumed_trade_trade_id ON consumed_trade (trade_id)";

    public static final String INDEX_CONSUMED_TRADE_EVENT_ID =
        "CREATE INDEX IF NOT EXISTS idx_consumed_trade_event_id ON consumed_trade (event_id)";

    public static final String INDEX_EVENTS_ACCOUNT_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_events_account_timestamp ON events (account_id, timestamp)";

    public static final String INDEX_TRADES_UUID =
        "CREATE INDEX IF NOT EXISTS idx_trades_uuid ON trades (uuid)";

    public static final String INDEX_ITEM_FAVORITES_ACCOUNT_ITEM =
        "CREATE INDEX IF NOT EXISTS idx_item_favorites_account_item ON item_favorites (account_id, item_id)";

    public static final String INDEX_RECIPE_FLIPS_RECIPE_KEY =
        "CREATE INDEX IF NOT EXISTS idx_recipe_flips_recipe_key ON recipe_flips (recipe_key)";

    public static final String INDEX_RECIPE_FLIP_INPUTS_FLIP =
        "CREATE INDEX IF NOT EXISTS idx_recipe_flip_inputs_flip ON recipe_flip_inputs (recipe_flip_id)";

    public static final String INDEX_RECIPE_FLIP_OUTPUTS_FLIP =
        "CREATE INDEX IF NOT EXISTS idx_recipe_flip_outputs_flip ON recipe_flip_outputs (recipe_flip_id)";

    // Partial unique index: at most one void (event_id IS NULL) per trade.
    public static final String INDEX_CONSUMED_TRADE_VOID =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_consumed_trade_void ON consumed_trade (trade_id) WHERE event_id IS NULL";

    // Partial unique index on events.natural_key for idempotent migration inserts.
    public static final String INDEX_EVENTS_NATURAL_KEY =
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_events_natural_key ON events (natural_key) WHERE natural_key IS NOT NULL";

    public static final List<String> getCreateStatementsInOrder() {
        return Arrays.asList(
            CREATE_TABLE_ACCOUNTS,
            CREATE_TABLE_ACTIVE_SLOTS,
            CREATE_TABLE_TRADES,
            CREATE_TABLE_EVENTS,
            CREATE_TABLE_CONSUMED_TRADE,
            CREATE_TABLE_RECIPE_FLIPS,
            CREATE_TABLE_RECIPE_FLIP_INPUTS,
            CREATE_TABLE_RECIPE_FLIP_OUTPUTS,
            CREATE_TABLE_SETTINGS,
            CREATE_TABLE_GE_LIMIT_STATE,
            CREATE_TABLE_ITEM_FAVORITES,
            CREATE_TABLE_ITEM_VISIBILITY
        );
    }

    public static final List<String> getIndexStatements() {
        return Arrays.asList(
            INDEX_ACTIVE_SLOTS_ACCOUNT_TIME,
            INDEX_TRADES_ACCOUNT_TIMESTAMP,
            INDEX_TRADES_ITEM_TIMESTAMP,
            INDEX_TRADES_ACCOUNT_ITEM_TIMESTAMP,
            INDEX_TRADES_UUID,
            INDEX_RECIPE_FLIPS_RECIPE_KEY,
            INDEX_RECIPE_FLIP_INPUTS_FLIP,
            INDEX_RECIPE_FLIP_OUTPUTS_FLIP,
            INDEX_CONSUMED_TRADE_TRADE_ID,
            INDEX_CONSUMED_TRADE_EVENT_ID,
            INDEX_EVENTS_ACCOUNT_TIMESTAMP,
            INDEX_ITEM_FAVORITES_ACCOUNT_ITEM,
            INDEX_CONSUMED_TRADE_VOID,
            INDEX_EVENTS_NATURAL_KEY
        );
    }

}
