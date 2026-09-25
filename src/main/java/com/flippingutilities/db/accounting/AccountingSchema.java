package com.flippingutilities.db.accounting;

import java.util.Arrays;
import java.util.List;

/** Additive accounting layout for the unreleased initial SQLite schema. */
public final class AccountingSchema {
    public static final String LAYOUT = "persisted-accounting-1";

    private AccountingSchema() { }

    public static List<String> statements() {
        return Arrays.asList(
            "CREATE TABLE IF NOT EXISTS accounting_items (item_id INTEGER PRIMARY KEY, item_name TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS accounting_state (account_id INTEGER PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE, " +
                "source_revision INTEGER NOT NULL DEFAULT 0, reporting_revision INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0, " +
                "active_plan_id TEXT, imported INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS accounting_orders (account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, " +
                "order_id TEXT NOT NULL, suppressed_quantity INTEGER NOT NULL DEFAULT 0, conflicted INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(account_id,order_id))",
            "CREATE TABLE IF NOT EXISTS accounting_dirty_items (account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, " +
                "item_id INTEGER NOT NULL, append_only INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account_id,item_id))",
            "CREATE TABLE IF NOT EXISTS accounting_observations (sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "account_id INTEGER NOT NULL, observation_id TEXT NOT NULL, source_uuid TEXT NOT NULL, order_id TEXT NOT NULL, predecessor_uuid TEXT, " +
                "item_id INTEGER NOT NULL, is_buy INTEGER NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>=0), amount_gp INTEGER, tax_gp INTEGER, " +
                "observed_at INTEGER, margin_eligible INTEGER NOT NULL, estimated INTEGER NOT NULL, restricted INTEGER NOT NULL DEFAULT 0, enriched INTEGER NOT NULL DEFAULT 0, " +
                "payload TEXT NOT NULL, UNIQUE(account_id,observation_id), " +
                "FOREIGN KEY(account_id,order_id) REFERENCES accounting_orders(account_id,order_id) ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS accounting_observations_alias ON accounting_observations(account_id,source_uuid,sequence)",
            "CREATE INDEX IF NOT EXISTS accounting_observations_order ON accounting_observations(account_id,order_id,sequence)",
            "CREATE TABLE IF NOT EXISTS accounting_sources (account_id INTEGER NOT NULL, source_id TEXT NOT NULL, order_id TEXT NOT NULL, " +
                "item_id INTEGER NOT NULL, is_buy INTEGER NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>0), amount_gp INTEGER, tax_gp INTEGER, " +
                "recognized_at INTEGER, sequence INTEGER NOT NULL, margin_eligible INTEGER NOT NULL, estimated INTEGER NOT NULL, " +
                "restricted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account_id,source_id), " +
                "FOREIGN KEY(account_id,order_id) REFERENCES accounting_orders(account_id,order_id) ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS accounting_sources_partition ON accounting_sources(account_id,item_id,recognized_at,sequence)",
            "CREATE INDEX IF NOT EXISTS accounting_sources_sequence ON accounting_sources(account_id,item_id,sequence)",
            "CREATE TABLE IF NOT EXISTS accounting_recipes (account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, " +
                "recipe_id TEXT NOT NULL, storage_recipe_id INTEGER NOT NULL, recipe_key TEXT, recorded_at INTEGER, coin_cost_gp INTEGER, " +
                "definition_json TEXT, execution_count INTEGER, " +
                "deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account_id,recipe_id))",
            "CREATE TABLE IF NOT EXISTS accounting_recipe_components (account_id INTEGER NOT NULL, recipe_id TEXT NOT NULL, " +
                "component_id TEXT NOT NULL, source_uuid TEXT, item_id INTEGER NOT NULL, is_input INTEGER NOT NULL, " +
                "quantity INTEGER NOT NULL CHECK(quantity>0), payload TEXT NOT NULL, PRIMARY KEY(account_id,recipe_id,component_id), " +
                "FOREIGN KEY(account_id,recipe_id) REFERENCES accounting_recipes(account_id,recipe_id) ON DELETE CASCADE)",
            "CREATE INDEX IF NOT EXISTS accounting_components_alias ON accounting_recipe_components(account_id,source_uuid)",
            "CREATE TABLE IF NOT EXISTS accounting_plans (plan_id TEXT PRIMARY KEY, account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, " +
                "mode TEXT NOT NULL, cutover INTEGER, purchase_cutoff INTEGER, source_revision INTEGER NOT NULL, preview_digest TEXT NOT NULL, " +
                "activated INTEGER NOT NULL DEFAULT 0, frozen_account TEXT, algorithm_version TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS accounting_plans_account ON accounting_plans(account_id)",
            "CREATE TABLE IF NOT EXISTS accounting_opening_selections (plan_id TEXT NOT NULL REFERENCES accounting_plans(plan_id) ON DELETE CASCADE, " +
                "source_id TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>0), item_id INTEGER NOT NULL, source_fingerprint TEXT NOT NULL, source_offset INTEGER NOT NULL, PRIMARY KEY(plan_id,source_id))",
            "CREATE TABLE IF NOT EXISTS accounting_checkpoints (plan_id TEXT NOT NULL REFERENCES accounting_plans(plan_id) ON DELETE CASCADE, " +
                "item_id INTEGER NOT NULL, source_sequence INTEGER NOT NULL, latest_at INTEGER, latest_sequence INTEGER NOT NULL, PRIMARY KEY(plan_id,item_id))",
            "CREATE TABLE IF NOT EXISTS accounting_realizations (plan_id TEXT NOT NULL REFERENCES accounting_plans(plan_id) ON DELETE CASCADE, " +
                "realization_id TEXT NOT NULL, flip_id TEXT NOT NULL, kind TEXT NOT NULL, item_id INTEGER NOT NULL, recipe_id TEXT, source_id TEXT NOT NULL, " +
                "recognized_at INTEGER, quantity INTEGER NOT NULL CHECK(quantity>0), matched_quantity INTEGER NOT NULL, " +
                "cost_gp INTEGER, gross_gp INTEGER, tax_gp INTEGER, adjustment_gp INTEGER, profit_gp INTEGER, estimated INTEGER NOT NULL, " +
                "PRIMARY KEY(plan_id,realization_id))",
            "CREATE INDEX IF NOT EXISTS accounting_realizations_time ON accounting_realizations(plan_id,recognized_at,realization_id)",
            "CREATE INDEX IF NOT EXISTS accounting_realizations_item_time ON accounting_realizations(plan_id,item_id,recognized_at,realization_id)",
            "CREATE INDEX IF NOT EXISTS accounting_realizations_flip ON accounting_realizations(plan_id,flip_id,recognized_at,realization_id)",
            "CREATE TABLE IF NOT EXISTS accounting_allocations (plan_id TEXT NOT NULL REFERENCES accounting_plans(plan_id) ON DELETE CASCADE, " +
                "allocation_id INTEGER NOT NULL, realization_id TEXT NOT NULL, source_id TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>0), " +
                "cost_gp INTEGER, is_buy INTEGER NOT NULL, source_offset INTEGER NOT NULL, PRIMARY KEY(plan_id,allocation_id))",
            "CREATE INDEX IF NOT EXISTS accounting_allocations_source ON accounting_allocations(plan_id,source_id)",
            "CREATE INDEX IF NOT EXISTS accounting_allocations_realization ON accounting_allocations(plan_id,realization_id,allocation_id)",
            "CREATE TABLE IF NOT EXISTS accounting_open_lots (plan_id TEXT NOT NULL REFERENCES accounting_plans(plan_id) ON DELETE CASCADE, " +
                "source_id TEXT NOT NULL, item_id INTEGER NOT NULL, quantity INTEGER NOT NULL CHECK(quantity>0), cost_gp INTEGER, " +
                "source_offset INTEGER NOT NULL, acquired_at INTEGER, available_at INTEGER, estimated INTEGER NOT NULL, " +
                "PRIMARY KEY(plan_id,source_id))",
            "CREATE INDEX IF NOT EXISTS accounting_open_lots_item ON accounting_open_lots(plan_id,item_id,acquired_at,source_id)",
            "CREATE TABLE IF NOT EXISTS accounting_warnings (plan_id TEXT NOT NULL REFERENCES accounting_plans(plan_id) ON DELETE CASCADE, " +
                "ordinal INTEGER NOT NULL, entity_id TEXT, message TEXT NOT NULL, PRIMARY KEY(plan_id,ordinal))"
        );
    }
}
