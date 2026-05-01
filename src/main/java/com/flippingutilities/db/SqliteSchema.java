package com.flippingutilities.db;



import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SqliteSchema {

    // Schema version for migration tracking
    public static final int SCHEMA_VERSION = 2;

    // PRAGMA for reading current version and for migrating to the current version
    public static final String PRAGMA_GET_USER_VERSION = "PRAGMA user_version";
    public static String getMigrationStatement() {
        return String.format("PRAGMA user_version = %d", SCHEMA_VERSION);
    }

    // CREATE TABLE statements (IF NOT EXISTS) in dependency order
    public static final String CREATE_TABLE_ACCOUNTS =
        "CREATE TABLE IF NOT EXISTS accounts (" +
        "  id INTEGER PRIMARY KEY," +
        "  display_name TEXT UNIQUE," +
        "  player_id TEXT," +
        "  session_start INTEGER," +
        "  accumulated_time INTEGER" +
        ");";

    public static final String CREATE_TABLE_ACTIVE_SLOTS =
        "CREATE TABLE IF NOT EXISTS active_slots (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER," +
        "  slot_index INTEGER," +
        "  offer_uuid TEXT," +
        "  item_id INTEGER," +
        "  is_buy INTEGER," +
        "  price INTEGER," +
        "  qty INTEGER," +
        "  total_qty INTEGER," +
        "  state TEXT," +
        "  time INTEGER," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    public static final String CREATE_TABLE_TRADES =
        "CREATE TABLE IF NOT EXISTS trades (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER," +
        "  item_id INTEGER," +
        "  uuid TEXT," +
        "  timestamp INTEGER," +
        "  qty INTEGER," +
        "  price INTEGER," +
        "  is_buy INTEGER," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    public static final String CREATE_TABLE_EVENTS =
        "CREATE TABLE IF NOT EXISTS events (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER," +
        "  timestamp INTEGER," +
        "  type TEXT," +
        "  cost INTEGER," +
        "  profit INTEGER," +
        "  note TEXT," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    public static final String CREATE_TABLE_CONSUMED_TRADE =
        "CREATE TABLE IF NOT EXISTS consumed_trade (" +
        "  id INTEGER PRIMARY KEY," +
        "  trade_id INTEGER," +
        "  qty INTEGER," +
        "  event_id INTEGER NULL," +
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

    public static final String CREATE_TABLE_RECIPES =
        "CREATE TABLE IF NOT EXISTS recipes (" +
        "  id INTEGER PRIMARY KEY," +
        "  recipe_key TEXT UNIQUE," +
        "  name TEXT," +
        "  inputs_json TEXT," +
        "  outputs_json TEXT" +
        ");";

    public static final String CREATE_TABLE_SETTINGS =
        "CREATE TABLE IF NOT EXISTS settings (" +
        "  key TEXT PRIMARY KEY," +
        "  value TEXT" +
        ");";

    public static final String CREATE_TABLE_GE_LIMIT_STATE =
        "CREATE TABLE IF NOT EXISTS ge_limit_state (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER," +
        "  item_id INTEGER," +
        "  next_refresh INTEGER," +
        "  items_bought INTEGER," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    public static final String CREATE_TABLE_SLOT_TIMERS =
        "CREATE TABLE IF NOT EXISTS slot_timers (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER," +
        "  slot_index INTEGER," +
        "  last_activity INTEGER," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    public static final String CREATE_TABLE_ITEM_FAVORITES =
        "CREATE TABLE IF NOT EXISTS item_favorites (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER," +
        "  item_id INTEGER," +
        "  is_favorite INTEGER DEFAULT 0," +
        "  favorite_code TEXT DEFAULT '1'," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)," +
        "  UNIQUE(account_id, item_id)" +
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
            CREATE_TABLE_RECIPES,
            CREATE_TABLE_SETTINGS,
            CREATE_TABLE_GE_LIMIT_STATE,
            CREATE_TABLE_SLOT_TIMERS,
            CREATE_TABLE_ITEM_FAVORITES
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
            INDEX_CONSUMED_TRADE_TRADE_ID,
            INDEX_CONSUMED_TRADE_EVENT_ID,
            INDEX_EVENTS_ACCOUNT_TIMESTAMP,
            INDEX_ITEM_FAVORITES_ACCOUNT_ITEM
        );
    }

    /**
     * Returns DDL statements to upgrade the schema from the given version to the current version.
     * Each version range (fromVersion &lt; N) produces the necessary DDL.
     */
    public static List<String> getMigrationStatements(int fromVersion) {
        List<String> stmts = new ArrayList<>();
        if (fromVersion < 2) {
            stmts.add(CREATE_TABLE_ITEM_FAVORITES);
            stmts.add(INDEX_ITEM_FAVORITES_ACCOUNT_ITEM);
        }
        // Future: if (fromVersion < 3) { stmts.add(...); }
        return stmts;
    }
}
