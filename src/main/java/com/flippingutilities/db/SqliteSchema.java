package com.flippingutilities.db;

import com.flippingutilities.model.FlippingItem;

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
        "  account_id INTEGER NOT NULL," +
        "  slot_index INTEGER NOT NULL," +
        "  offer_uuid TEXT," +
        "  offer_json TEXT NOT NULL," +
        "  history_visible INTEGER NOT NULL DEFAULT 0," +
        "  PRIMARY KEY(account_id, slot_index)," +
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
        "  offer_json TEXT NOT NULL," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)," +
        "  UNIQUE(account_id, uuid)" +
        ");";

    public static final String CREATE_TABLE_RECIPE_FLIPS =
        "CREATE TABLE IF NOT EXISTS recipe_flips (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER NOT NULL," +
        "  timestamp INTEGER NOT NULL," +
        "  recipe_key TEXT," +
        "  coin_cost INTEGER," +
        "  natural_key TEXT NOT NULL UNIQUE," +
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)" +
        ");";

    public static final String CREATE_TABLE_RECIPE_FLIP_INPUTS =
        "CREATE TABLE IF NOT EXISTS recipe_flip_inputs (" +
        "  id INTEGER PRIMARY KEY," +
        "  recipe_flip_id INTEGER," +
        "  item_id INTEGER," +
        "  offer_uuid TEXT," +
        "  amount_consumed INTEGER," +
        "  offer_json TEXT NOT NULL," +
        "  FOREIGN KEY(recipe_flip_id) REFERENCES recipe_flips(id)" +
        ");";

    public static final String CREATE_TABLE_RECIPE_FLIP_OUTPUTS =
        "CREATE TABLE IF NOT EXISTS recipe_flip_outputs (" +
        "  id INTEGER PRIMARY KEY," +
        "  recipe_flip_id INTEGER," +
        "  item_id INTEGER," +
        "  offer_uuid TEXT," +
        "  amount_consumed INTEGER," +
        "  offer_json TEXT NOT NULL," +
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
        "  FOREIGN KEY(account_id) REFERENCES accounts(id)," +
        "  UNIQUE(account_id, item_id)" +
        ");";

    public static final String CREATE_TABLE_ITEM_FAVORITES =
        "CREATE TABLE IF NOT EXISTS item_favorites (" +
        "  id INTEGER PRIMARY KEY," +
        "  account_id INTEGER NOT NULL," +
        "  item_id INTEGER NOT NULL," +
        "  is_favorite INTEGER DEFAULT 0," +
        "  favorite_code TEXT DEFAULT '" + FlippingItem.DEFAULT_FAVORITE_CODE + "'," +
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
    public static final String INDEX_TRADES_ACCOUNT_ITEM_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_trades_account_item_timestamp ON trades (account_id, item_id, timestamp)";

    public static final String INDEX_RECIPE_FLIPS_ACCOUNT_KEY_TIMESTAMP =
        "CREATE INDEX IF NOT EXISTS idx_recipe_flips_account_key_timestamp ON recipe_flips (account_id, recipe_key, timestamp)";

    public static final String INDEX_RECIPE_FLIP_INPUTS_FLIP =
        "CREATE INDEX IF NOT EXISTS idx_recipe_flip_inputs_flip ON recipe_flip_inputs (recipe_flip_id)";

    public static final String INDEX_RECIPE_FLIP_OUTPUTS_FLIP =
        "CREATE INDEX IF NOT EXISTS idx_recipe_flip_outputs_flip ON recipe_flip_outputs (recipe_flip_id)";

    public static final String INDEX_RECIPE_FLIP_INPUTS_OFFER_UUID =
        "CREATE INDEX IF NOT EXISTS idx_recipe_flip_inputs_offer_uuid ON recipe_flip_inputs (offer_uuid)";

    public static final String INDEX_RECIPE_FLIP_OUTPUTS_OFFER_UUID =
        "CREATE INDEX IF NOT EXISTS idx_recipe_flip_outputs_offer_uuid ON recipe_flip_outputs (offer_uuid)";

    public static final List<String> getCreateStatementsInOrder() {
        return Arrays.asList(
            CREATE_TABLE_ACCOUNTS,
            CREATE_TABLE_ACTIVE_SLOTS,
            CREATE_TABLE_TRADES,
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
            INDEX_TRADES_ACCOUNT_ITEM_TIMESTAMP,
            INDEX_RECIPE_FLIPS_ACCOUNT_KEY_TIMESTAMP,
            INDEX_RECIPE_FLIP_INPUTS_FLIP,
            INDEX_RECIPE_FLIP_OUTPUTS_FLIP,
            INDEX_RECIPE_FLIP_INPUTS_OFFER_UUID,
            INDEX_RECIPE_FLIP_OUTPUTS_OFFER_UUID
        );
    }

}
