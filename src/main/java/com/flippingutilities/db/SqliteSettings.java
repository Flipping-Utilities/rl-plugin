package com.flippingutilities.db;

/** Persisted setting names shared by migration, recovery and account loading. */
public final class SqliteSettings {
    public static final String MIGRATION_COMPLETED = "migration_completed";
    public static final String MIGRATION_COMPLETED_AT = "migration_completed_at";
    public static final String MIGRATION_PENDING = "migration_pending";
    private static final String MIGRATED_ACCOUNT_PREFIX = "migrated_";

    private SqliteSettings() {
    }

    public static String accountMigrationKey(String displayName) {
        return MIGRATED_ACCOUNT_PREFIX + displayName;
    }
}
