package com.flippingutilities.db;

/**
 * Defines all the database table structures for SQLite storage.
 * This replaces the old system where everything was saved as one big JSON file.
 * With SQLite, each trade is saved individually (like adding a row to a spreadsheet)
 * instead of rewriting the entire file every time.
 */
public class SqliteSchema {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * All the CREATE TABLE statements that set up the database.
     * Run once when the database is first created.
     */
    public static final String[] CREATE_TABLES = {
        // Tracks which version of the database structure we're using
        // so we can upgrade it in the future without losing data
        "CREATE TABLE IF NOT EXISTS schema_version (" +
            "version INTEGER NOT NULL" +
        ")",

        // One row per OSRS account (e.g. your in-game character name)
        "CREATE TABLE IF NOT EXISTS accounts (" +
            "display_name TEXT PRIMARY KEY, " +
            "session_start_time INTEGER, " +
            "accumulated_session_time_millis INTEGER DEFAULT 0, " +
            "last_session_time_update INTEGER, " +
            "last_stored_at INTEGER DEFAULT 0, " +
            "last_modified_at INTEGER" +
        ")",

        // Each item you've ever traded, linked to an account
        // (e.g. "Rune platebody" traded by account "PlayerName")
        "CREATE TABLE IF NOT EXISTS flipping_items (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "account_display_name TEXT NOT NULL, " +
            "item_id INTEGER NOT NULL, " +
            "item_name TEXT NOT NULL, " +
            "total_ge_limit INTEGER DEFAULT 0, " +
            "flipped_by TEXT, " +
            "valid_flipping_panel_item INTEGER DEFAULT 1, " +
            "favorite INTEGER DEFAULT 0, " +
            "favorite_code TEXT DEFAULT '1', " +
            "UNIQUE(account_display_name, item_id), " +
            "FOREIGN KEY (account_display_name) REFERENCES accounts(display_name)" +
        ")",

        // Individual buy/sell events — the core trading data.
        // Each row is one completed or in-progress GE offer.
        // This is the key improvement: instead of saving ALL offers at once in a JSON file,
        // each new offer is saved as a single row the instant it happens.
        "CREATE TABLE IF NOT EXISTS offer_events (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "flipping_item_id INTEGER NOT NULL, " +
            "uuid TEXT NOT NULL UNIQUE, " +
            "buy INTEGER NOT NULL, " +
            "item_id INTEGER NOT NULL, " +
            "current_quantity_in_trade INTEGER NOT NULL, " +
            "price INTEGER NOT NULL, " +
            "time INTEGER NOT NULL, " +
            "slot INTEGER NOT NULL, " +
            "state TEXT NOT NULL, " +
            "tick_arrived_at INTEGER DEFAULT 0, " +
            "ticks_since_first_offer INTEGER DEFAULT 0, " +
            "total_quantity_in_trade INTEGER NOT NULL, " +
            "trade_started_at INTEGER, " +
            "before_login INTEGER DEFAULT 0, " +
            "FOREIGN KEY (flipping_item_id) REFERENCES flipping_items(id) ON DELETE CASCADE" +
        ")",

        // Remembers the most recent offer in each GE slot (8 slots total)
        // so the plugin can detect duplicate events and track trade progress
        "CREATE TABLE IF NOT EXISTS last_offers (" +
            "account_display_name TEXT NOT NULL, " +
            "slot INTEGER NOT NULL, " +
            "offer_json TEXT NOT NULL, " +
            "PRIMARY KEY (account_display_name, slot), " +
            "FOREIGN KEY (account_display_name) REFERENCES accounts(display_name)" +
        ")",

        // Tracks GE buy limits per item (how many you can buy in a 4-hour window)
        "CREATE TABLE IF NOT EXISTS ge_limit_state (" +
            "flipping_item_id INTEGER PRIMARY KEY, " +
            "next_ge_limit_refresh INTEGER, " +
            "items_bought_this_limit_window INTEGER DEFAULT 0, " +
            "items_bought_through_complete INTEGER DEFAULT 0, " +
            "FOREIGN KEY (flipping_item_id) REFERENCES flipping_items(id) ON DELETE CASCADE" +
        ")",

        // Recipe flip groups — for tracking profits from combining items
        // (e.g. buying herbs + vials and selling potions)
        "CREATE TABLE IF NOT EXISTS recipe_flip_groups (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "account_display_name TEXT NOT NULL, " +
            "recipe_key TEXT NOT NULL, " +
            "UNIQUE(account_display_name, recipe_key), " +
            "FOREIGN KEY (account_display_name) REFERENCES accounts(display_name)" +
        ")",

        // Individual recipe flips within a group.
        // coin_cost stores the full RecipeFlip as JSON because the nested map structure
        // (inputs/outputs are Map<Integer, Map<String, PartialOffer>>) is too complex
        // to normalize into relational tables for minimal benefit.
        "CREATE TABLE IF NOT EXISTS recipe_flips (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "recipe_flip_group_id INTEGER NOT NULL, " +
            "time_of_creation INTEGER NOT NULL, " +
            "coin_cost TEXT, " +
            "FOREIGN KEY (recipe_flip_group_id) REFERENCES recipe_flip_groups(id) ON DELETE CASCADE" +
        ")",

        // GE slot timers per account (tracks activity in each of 8 GE slots)
        "CREATE TABLE IF NOT EXISTS slot_timers (" +
            "account_display_name TEXT NOT NULL, " +
            "slot_index INTEGER NOT NULL, " +
            "current_offer_json TEXT, " +
            "PRIMARY KEY (account_display_name, slot_index), " +
            "FOREIGN KEY (account_display_name) REFERENCES accounts(display_name)" +
        ")",

        // Plugin-wide settings that apply across all accounts
        // (keybinds, UI sections, local recipes, etc.)
        // Stored as a single JSON blob since it's small and accessed infrequently.
        "CREATE TABLE IF NOT EXISTS account_wide_data (" +
            "id INTEGER PRIMARY KEY DEFAULT 1, " +
            "data_json TEXT NOT NULL" +
        ")"
    };

    /**
     * Indexes speed up common lookups (like "find all trades for this account").
     */
    public static final String[] CREATE_INDEXES = {
        "CREATE INDEX IF NOT EXISTS idx_fi_account ON flipping_items(account_display_name)",
        "CREATE INDEX IF NOT EXISTS idx_fi_item_id ON flipping_items(item_id)",
        "CREATE INDEX IF NOT EXISTS idx_oe_fi ON offer_events(flipping_item_id)",
        "CREATE INDEX IF NOT EXISTS idx_oe_time ON offer_events(time)"
    };
}
