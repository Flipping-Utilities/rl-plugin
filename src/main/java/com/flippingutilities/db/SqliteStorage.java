package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig.TransactionMode;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteConnectionConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * SQLite facade owning connection, schema, and recovery lifecycle.
 * Domain stores execute under this instance's monitor so they share one transaction boundary.
 */
public class SqliteStorage {
    private static final Logger logger = LoggerFactory.getLogger(SqliteStorage.class);

    static {
        // Register the SQLite driver explicitly. DriverManager's service discovery uses the
        // thread-context classloader, which in a packed RuneLite plugin does not see the
        // plugin jar (where sqlite-jdbc is embedded), so discovery alone throws
        // "No suitable driver". Class.forName runs the driver's static initializer, which
        // registers it with DriverManager regardless of classloader.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            logger.error("SQLite JDBC driver not found on the plugin classpath", e);
        }
    }

    static String serializeOffer(OfferEvent offer) {
        return OfferJsonCodec.serializeOffer(offer);
    }

    static String serializeRecipeOffer(PartialOffer component) throws SQLException {
        return OfferJsonCodec.serializeRecipeOffer(component);
    }

    private final File dbFile;
    private Connection connection;
    private boolean schemaInitAttempted;
    // Collaborators share this instance's connection and are called under its monitor.
    private final SqliteAccountStore accounts;
    private final SqliteItemStateStore itemState;
    private final SqliteOfferStore offers;
    private final SqliteRecipeStore recipes;

    /**
     * Creates a storage bound to the given database file.
     *
     * @param dbFile Database file location
     */
    public SqliteStorage(File dbFile) {
        this.dbFile = dbFile;
        itemState = new SqliteItemStateStore(this);
        recipes = new SqliteRecipeStore(this);
        offers = new SqliteOfferStore(this, itemState, recipes);
        accounts = new SqliteAccountStore(this, offers, recipes, itemState);
    }

    /**
     * Returns the database file this storage is bound to. Used by MigrationService to
     * create pre-migration backups.
     */
    public File getDbFile() {
        return dbFile;
    }

    private Path recoveryMarker() {
        return new File(dbFile.getAbsolutePath() + ".needs-resync").toPath();
    }

    /** Kept outside SQLite so a failed database write cannot leave the database authoritative. */
    public synchronized void markOutOfSync() {
        try {
            Files.write(recoveryMarker(), new byte[0]);
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist SQLite recovery marker", e);
        }
    }

    public synchronized boolean requiresFullResync() {
        return Files.exists(recoveryMarker());
    }

    /** Called only after the full JSON import has committed successfully. */
    public synchronized void markSynchronized() {
        try {
            Files.deleteIfExists(recoveryMarker());
        } catch (IOException e) {
            throw new IllegalStateException("Could not clear SQLite recovery marker", e);
        }
    }

    /**
     * Returns a valid JDBC Connection to the SQLite database.
     * Creates connection if needed, enables WAL mode and sets busy timeout.
     * @return Active database connection
     * @throws SQLException if connection cannot be established
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(2)) {
                    return connection;
                }
                logger.warn("SQLite connection invalid, reconnecting...");
                try { connection.close(); } catch (SQLException e) { logger.debug("Closing stale connection failed", e); }
                connection = null;
            } catch (SQLException e) {
                connection = null;
            }
        }

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                connection = DriverManager.getConnection(url);
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA journal_mode=WAL;");
                    stmt.execute("PRAGMA busy_timeout=5000;");
                    stmt.execute("PRAGMA foreign_keys=ON;");
                }
                return connection;
            } catch (SQLException e) {
                if (attempts >= 3) throw e;
                logger.warn("SQLite connection attempt {} failed, retrying...", attempts, e);
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted during connection retry", ie);
                }
            }
        }
    }

    /**
     * Close the underlying connection if present.
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.warn("Error while closing SQLite connection", e);
            } finally {
                connection = null;
            }
        }
        // Reset initialization state for a subsequent open on a possibly replaced file.
        schemaInitAttempted = false;
    }

    /**
     * Get a setting value from the settings table.
     * @param key Setting key
     * @return Value or null if not found
     */
    public synchronized String getSetting(String key) {
        try {
            Connection conn = getConnection();
            String sql = "SELECT value FROM settings WHERE key = :key";
            try (NamedStatement ps = NamedStatement.prepare(conn, sql)) {
                ps.bind("key", key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("value");
                    }
                }
            }
        } catch (SQLException e) {
            // If the schema isn't initialized yet, try to initialize and retry exactly once.
            // schemaInitAttempted breaks the recursion if init itself fails.
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table") && !schemaInitAttempted) {
                try {
                    initializeSchema();
                } catch (RuntimeException initFailure) {
                    throw new IllegalStateException("Could not initialize settings", initFailure);
                }
                return getSetting(key);
            }
            throw new IllegalStateException("Could not read setting " + key, e);
        }
        return null;
    }

    public synchronized boolean getBooleanSetting(String key) {
        return Boolean.parseBoolean(getSetting(key));
    }

    public synchronized void setBooleanSetting(String key, boolean value) {
        setSetting(key, Boolean.toString(value));
    }

    /**
     * Set a setting value in the settings table.
     * @param key Setting key
     * @param value Setting value
     */
    public synchronized void setSetting(String key, String value) {
        try {
            Connection conn = getConnection();
            String sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (:key, :value)";
            try (NamedStatement ps = NamedStatement.prepare(conn, sql)) {
                ps.bind("key", key);
                ps.bind("value", value);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table") && !schemaInitAttempted) {
                try {
                    initializeSchema();
                } catch (RuntimeException initFailure) {
                    throw new IllegalStateException("Could not initialize settings", initFailure);
                }
                setSetting(key, value);
            } else {
                throw new IllegalStateException("Could not persist setting " + key, e);
            }
        }
    }

    /**
     * Clear a setting from the settings table.
     * @param key Setting key to delete
     */
    public synchronized void clearSetting(String key) {
        try {
            Connection conn = getConnection();
            String sql = "DELETE FROM settings WHERE key = :key";
            try (NamedStatement ps = NamedStatement.prepare(conn, sql)) {
                ps.bind("key", key);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such table") && !schemaInitAttempted) {
                try {
                    initializeSchema();
                } catch (RuntimeException initFailure) {
                    throw new IllegalStateException("Could not initialize settings", initFailure);
                }
                clearSetting(key);
            } else {
                throw new IllegalStateException("Could not clear setting " + key, e);
            }
        }
    }

    /**
     * Initialize database schema using SqliteSchema DDL.
     * Creates all tables, indexes, and sets user_version.
     */
    public synchronized void initializeSchema() {
        try {
            Connection conn = getConnection();

            // Check current schema version
            int currentVersion = 0;
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(SqliteSchema.PRAGMA_GET_USER_VERSION)) {
                if (rs.next()) {
                    currentVersion = rs.getInt(1);
                }
            }

            if (currentVersion == SqliteSchema.SCHEMA_VERSION) {
                logger.debug("Schema already at version {}, nothing to do", currentVersion);
                return;
            }

            if (currentVersion != 0) {
                throw new IllegalStateException("Unsupported SQLite schema version: " + currentVersion);
            }

            // Create the complete initial schema and its version stamp atomically. There
            // are no historical database versions to upgrade before the first release.
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement stmt = conn.createStatement()) {
                    for (String sql : SqliteSchema.getCreateStatementsInOrder()) {
                        stmt.execute(sql);
                    }
                    for (String sql : SqliteSchema.getIndexStatements()) {
                        stmt.execute(sql);
                    }
                    stmt.execute(SqliteSchema.getMigrationStatement());
                    try (ResultSet violations = stmt.executeQuery("PRAGMA foreign_key_check")) {
                        if (violations.next()) {
                            throw new SQLException("Foreign key violation while initializing SQLite schema");
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(wasAutoCommit);
            }
        } catch (SQLException e) {
            // Rethrow instead of swallowing: callers (notably FlippingPlugin.rebuildSqliteFromJson)
            // catch this and fall back to the JSON backend. Swallowing here left a corrupt/locked
            // DB in SQLite mode with every subsequent operation failing silently.
            logger.error("Error initializing SQLite schema", e);
            throw new IllegalStateException("Failed to initialize SQLite schema", e);
        } finally {
            // Guard against infinite recursion in the setting helpers: once we've attempted init
            // (whether it succeeded or failed), don't try again on the next "no such table" error.
            schemaInitAttempted = true;
        }
    }

    /**
     * Upsert an account record. Creates new or updates existing.
     * @param displayName Account display name
     * @param playerId Player ID from RuneLite API
     */
    public synchronized void upsertAccount(String displayName, String playerId) {
        inAccountTransaction(() -> accounts.upsertAccount(displayName, playerId));
    }

    /**
     * Load account data from SQLite, fully reconstructing trades and flips.
     * @param displayName Account display name
     * @return AccountData object with all trades loaded, or null if the account is unknown
     *         (callers fall back to JSON in that case)
     */
    public synchronized AccountData loadAccount(String displayName) {
        return inAccountTransaction(() -> accounts.loadAccount(displayName));
    }

    public synchronized void upsertItemVisibility(String displayName, int itemId, boolean visible) {
        inAccountTransaction(() -> itemState.upsertItemVisibility(displayName, itemId, visible));
    }

    /**
     * Load GE limit state for every item of an account in one query.
     * @return Map of itemId -> {nextRefresh (Instant, nullable), itemsBought (int)}
     */
    public synchronized Map<Integer, Map<String, Object>> loadAllGeLimitStates(String displayName) {
        return inAccountTransaction(() -> itemState.loadAllGeLimitStates(displayName));
    }

    /**
     * List all account display names.
     * @return List of display names sorted alphabetically
     */
    public synchronized List<String> listAccounts() {
        return inAccountTransaction(() -> accounts.listAccounts());
    }

    /** Records the original offer, preserving classification and continuity across reloads. */
    public synchronized void recordTrade(String displayName, OfferEvent offer) {
        inAccountTransaction(() -> offers.recordTrade(displayName, offer));
    }

    /**
     * Upsert an active slot with an offer event.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     * @param offer The offer event to store, including completed offers awaiting collection
     */
    public synchronized void upsertSlot(String displayName, int slotIndex, OfferEvent offer, boolean historyVisible) {
        inAccountTransaction(() -> offers.upsertSlot(displayName, slotIndex, offer, historyVisible));
    }

    /**
     * Clear (delete) a slot's active offer.
     * @param displayName Account display name
     * @param slotIndex GE slot index (0-7)
     */
    public synchronized void clearSlot(String displayName, int slotIndex) {
        inAccountTransaction(() -> offers.clearSlot(displayName, slotIndex));
    }

    /**
     * Upsert GE limit state for an item.
     * @param displayName Account display name
     * @param itemId Item ID
     * @param nextRefresh Time when GE limit resets
     * @param itemsBought Number of items bought this limit window
     */
    public synchronized void upsertGeLimitState(String displayName, int itemId, Instant nextRefresh, int itemsBought, int itemsBoughtThroughCompleteOffers) {
        inAccountTransaction(() -> itemState.upsertGeLimitState(displayName, itemId, nextRefresh, itemsBought, itemsBoughtThroughCompleteOffers));
    }

    synchronized int getOrCreateAccountId(String displayName) {
        return accounts.getOrCreateAccountId(displayName);
    }

    synchronized Integer getAccountId(String displayName) {
        return accounts.getAccountId(displayName);
    }

    /**
     * Update accumulated session time for an account.
     * @param displayName Account display name
     * @param accumulatedTimeMillis Total accumulated session time in milliseconds
     */
    public synchronized void updateAccountSessionTime(String displayName, long accumulatedTimeMillis) {
        inAccountTransaction(() -> accounts.updateAccountSessionTime(displayName, accumulatedTimeMillis));
    }

    /**
     * Upsert a favorite status for an item under an account.
     * @param displayName Account display name
     * @param itemId Item ID
     * @param isFavorite Whether the item is favorited
     * @param favoriteCode Quick search code
     */
    public synchronized void upsertFavorite(String displayName, int itemId, boolean isFavorite, String favoriteCode) {
        inAccountTransaction(() -> itemState.upsertFavorite(displayName, itemId, isFavorite, favoriteCode));
    }

    /**
     * Load all favorites for an account.
     * @return Map of itemId -> Map with "isFavorite" and "favoriteCode"
     */
    public synchronized Map<Integer, Map<String, Object>> loadAllFavorites(String displayName) {
        return inAccountTransaction(() -> itemState.loadAllFavorites(displayName));
    }

    /**
     * Delete ALL data for an account (trades, recipe flips, slots,
     * favorites, GE limit state, session time, and the migrated_ flag). Mirrors
     * TradePersister.deleteFile for the JSON backend.
     */
    public synchronized void deleteAccountData(String displayName) {
        inAccountTransaction(() -> accounts.deleteAccountData(displayName));
    }

    /** Applies the live model's exact history replacement without deleting recipe snapshots. */
    public synchronized void recordOfferUpdate(String displayName, OfferEvent offer, List<String> replacedUuids) {
        inAccountTransaction(() -> offers.recordOfferUpdate(displayName, offer, replacedUuids));
    }

    /** Retains a collected fill even when the last event was a partial cancellation correction. */
    public synchronized void archiveOfferAndClearSlot(String displayName, int slotIndex, OfferEvent archived) {
        inAccountTransaction(() -> offers.archiveOfferAndClearSlot(displayName, slotIndex, archived));
    }

    /** Deletes offers and every recipe that references them, scoped to one account. */
    public synchronized void deleteTradesByUuid(String displayName, List<String> uuids) {
        inAccountTransaction(() -> offers.deleteTradesByUuid(displayName, uuids));
    }

    /**
     * Delete a single recipe flip (the per-flip delete button in the recipe panel) together
     * with its components. Identified by the same natural key that both
     * the migration and insertRecipeFlip use, so migrated and live-created flips are covered.
     */
    public synchronized void deleteRecipeFlip(String displayName, String recipeKey, Instant timeOfCreation) {
        inAccountTransaction(() -> recipes.deleteRecipeFlip(displayName, recipeKey, timeOfCreation));
    }

    /**
     * Delete a single group's recipe flips created after a timestamp (the interval-reset flow
     * on a recipe group panel). Scoped to the recipe key: deleting one group must not touch
     * other groups' flips in the same interval. Strictly after, mirroring
     * RecipeFlipGroup.deleteFlips' isAfter check.
     */
    public synchronized void deleteRecipeFlipsSince(String displayName, String recipeKey, Instant since) {
        inAccountTransaction(() -> recipes.deleteRecipeFlipsSince(displayName, recipeKey, since));
    }

    /** Persists a live recipe atomically, using the same writer as migration. */
    public synchronized void insertRecipeFlip(String displayName, String recipeKey, RecipeFlip flip) {
        inAccountTransaction(() -> recipes.insertRecipeFlip(displayName, recipeKey, flip));
    }

    /** Caller owns the transaction; returns false when this flip was already persisted. */
    static boolean insertRecipeFlip(Connection conn, int accountId, String recipeKey, RecipeFlip flip) throws SQLException {
        return SqliteRecipeStore.insertRecipeFlip(conn, accountId, recipeKey, flip);
    }

    /**
     * Keeps identity lookup and every dependent statement in one database snapshot.
     * Writers reserve the database before looking up identity, so another client's
     * ordinary writes cannot invalidate a read snapshot that still needs upgrading.
     * Readers use deferred snapshots without blocking writers.
     * Migration may already own a transaction; only the creator commits or rolls it back.
     */
    private <T> T inAccountTransaction(TransactionMode mode, Supplier<T> operation) {
        try {
            Connection conn = getConnection();
            if (!conn.getAutoCommit()) {
                return operation.get();
            }
            beginAccountTransaction(conn, mode);
            try {
                T result = operation.get();
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    // Never let restoring autocommit commit an unsuccessful rollback.
                    try {
                        conn.close();
                    } catch (SQLException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                throw failure;
            } finally {
                if (!conn.isClosed()) {
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not complete SQLite account transaction", e);
        }
    }

    private void beginAccountTransaction(Connection conn, TransactionMode mode) throws SQLException {
        SQLiteConnectionConfig config = conn.unwrap(SQLiteConnection.class).getConnectionConfig();
        TransactionMode previousMode = config.getTransactionMode();
        try {
            config.setTransactionMode(mode);
            conn.setAutoCommit(false);
        } catch (SQLException failure) {
            // Xerial changes its JDBC autocommit flag before BEGIN succeeds. Discard a
            // failed start so the next operation cannot join a transaction that never began.
            try {
                conn.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        } finally {
            // commit()/rollback() start another transaction in Xerial; do not let their
            // cleanup acquire a second write reservation after the real work has finished.
            config.setTransactionMode(previousMode);
        }
    }

    private <T> T inAccountTransaction(Supplier<T> operation) {
        return inAccountTransaction(TransactionMode.DEFERRED, operation);
    }

    private void inAccountTransaction(Runnable operation) {
        inAccountTransaction(TransactionMode.IMMEDIATE, () -> {
            operation.run();
            return null;
        });
    }

}
