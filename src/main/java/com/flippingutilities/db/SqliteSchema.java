package com.flippingutilities.db;

import java.util.Arrays;
import java.util.List;

/**
 * SQLite schema constants and DDL for the Flipping Utilities storage.
 * <p>
 * This file defines the schema version, all CREATE TABLE statements, index
 * definitions and a PRAGMA-based migration hook. All DDL statements are exposed
 * as string constants and can be retrieved in a defined order to ensure proper
 * table creation order with foreign key dependencies.
 */
public final class SqliteSchema {

    // Schema version for migration tracking
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
        "  event_id INTEGER NULLABLE," +
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
        "  amount_consumed INTEGER" +
        ");";

    public static final String CREATE_TABLE_RECIPE_FLIP_OUTPUTS =
        "CREATE TABLE IF NOT EXISTS recipe_flip_outputs (" +
        "  id INTEGER PRIMARY KEY," +
        "  recipe_flip_id INTEGER," +
        "  item_id INTEGER," +
        "  offer_uuid TEXT," +
        "  amount_consumed INTEGER" +
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

    // INDEX statements for common query patterns
    public static final String INDEX_ACTIVE_SLOTS_ACCOUNT_TIME =
        "CREATE INDEX IF NOT EXISTS idx_active_slots_account_time ON active_slots (account_id, time)";

    public static final String INDEX_TRADES_ACCOUNT_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_trades_account_timestamp ON trades (account_id, timestamp)";

    public static final String INDEX_TRADES_ITEM_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_trades_item_timestamp ON trades (item_id, timestamp)";

    public static final String INDEX_EVENTS_ACCOUNT_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_events_account_timestamp ON events (account_id, timestamp)";

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
            CREATE_TABLE_SLOT_TIMERS
        );
    }

    public static final List<String> getIndexStatements() {
        return Arrays.asList(
            INDEX_ACTIVE_SLOTS_ACCOUNT_TIME,
            INDEX_TRADES_ACCOUNT_TIMESTAMP,
            INDEX_TRADES_ITEM_TIMESTAMP,
            INDEX_EVENTS_ACCOUNT_TIMESTAMP
        );
    }
}
