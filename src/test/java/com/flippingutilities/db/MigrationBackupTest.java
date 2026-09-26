package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

public class MigrationBackupTest {
    private static final String ACCOUNT = "Backup account";
    private static final long TIME = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SqliteStorage storage;
    private MigrationService migration;

    @Before
    public void setUp() throws Exception {
        storage = new SqliteStorage(folder.newFile("backup-test.db"));
        storage.initializeSchema();
        storage.recordTrade(ACCOUNT, complete(ACCOUNT, 4151, "old-offer", TIME, 3, 100, true));
        storage.setSetting("migrated_" + ACCOUNT, "previous-import");
        storage.setSetting("migration_completed", "true");
        migration = new MigrationService(storage, new TradePersister(new Gson()));
    }

    @After
    public void tearDown() {
        storage.close();
    }

    @Test
    public void rebuildBacksUpPopulatedDatabaseBeforeReplacingAccounts() throws Exception {
        assertEquals(1, migration.rebuild(snapshot(false)));
        assertReplacementSucceeded();
        assertOnlyBackupContainsPreviousData();
    }

    @Test
    public void regenerateBacksUpPopulatedDatabaseBeforeRecreatingFiles() throws Exception {
        assertEquals(1, migration.regenerate(snapshot(false)));
        assertReplacementSucceeded();
        assertOnlyBackupContainsPreviousData();
    }

    @Test
    public void failedRebuildLeavesPreviousDataInItsBackup() throws Exception {
        assertEquals(0, migration.rebuild(snapshot(true)));
        assertFailedImportRolledBack();
        assertOnlyBackupContainsPreviousData();
    }

    @Test
    public void failedRegenerationLeavesPreviousDataInItsBackup() throws Exception {
        assertEquals(0, migration.regenerate(snapshot(true)));
        assertFailedImportRolledBack();
        assertOnlyBackupContainsPreviousData();
    }

    @Test
    public void failedBackupAbortsRebuildBeforeDeletingOrImportingData() {
        rejectBackupStatements();
        assertBackupFailureRetainsOriginal(() -> migration.rebuild(snapshot(false)));
    }

    @Test
    public void failedBackupAbortsRegenerationBeforeDeletingDatabaseFiles() {
        rejectBackupStatements();
        assertBackupFailureRetainsOriginal(() -> migration.regenerate(snapshot(false)));
    }

    @Test
    public void failedBackupStillAllowsAdditiveImport() {
        storage.clearSetting("migration_completed");
        storage.clearSetting("migrated_" + ACCOUNT);
        rejectBackupStatements();

        assertEquals(1, migration.migrate(snapshot(false)));
        storage.close();
        assertEquals("Additive import must retain the original trade as well as the new one", 2,
            storage.loadAccount(ACCOUNT).getTrades().get(0).getHistory().getCompressedOfferEvents().size());
        assertEquals("true", storage.getSetting("migration_completed"));
    }

    private void assertBackupFailureRetainsOriginal(Runnable reset) {
        try {
            reset.run();
            fail("A destructive reset must abort when its backup fails");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("backup"));
            assertTrue(expected.getCause() instanceof SQLException);
        }
        storage.close();
        assertEquals("old-offer", onlyOffer(storage.loadAccount(ACCOUNT)).getUuid());
        assertEquals("previous-import", storage.getSetting("migrated_" + ACCOUNT));
        assertEquals("true", storage.getSetting("migration_completed"));
        assertTrue("Recovery remains pending until a later successful rebuild", storage.requiresFullResync());
        File[] backups = folder.getRoot().listFiles((directory, name) -> name.contains(".pre-migration-"));
        assertNotNull(backups);
        assertEquals(0, backups.length);
    }

    /** Fail only VACUUM INTO; ordinary writes/deletes still use the real writable database. */
    private void rejectBackupStatements() {
        File database = storage.getDbFile();
        storage.close();
        storage = new SqliteStorage(database) {
            @Override
            public synchronized Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        Object result = invoke(connection, method, arguments);
                        if (!method.getName().equals("createStatement")) {
                            return result;
                        }
                        Statement statement = (Statement) result;
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                            new Class<?>[]{Statement.class}, (statementProxy, statementMethod, statementArguments) -> {
                                if (statementMethod.getName().equals("execute") && statementArguments != null
                                    && statementArguments[0] instanceof String
                                    && ((String) statementArguments[0]).startsWith("VACUUM INTO")) {
                                    throw new SQLException("Injected backup failure");
                                }
                                return invoke(statement, statementMethod, statementArguments);
                            });
                    });
            }
        };
        migration = new MigrationService(storage, new TradePersister(new Gson()));
    }

    private Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Map<String, AccountData> snapshot(boolean invalidRecipe) {
        AccountData account = new AccountData();
        FlippingItem item = new FlippingItem(4151, "Whip", 70, ACCOUNT);
        item.getHistory().getCompressedOfferEvents()
            .add(complete(ACCOUNT, 4151, "new-offer", TIME + 1000, 5, 200, true));
        account.getTrades().add(item);
        if (invalidRecipe) {
            // Fail after the trade insert, through the public migration's real transaction.
            OfferEvent invalid = complete(ACCOUNT, 4587, "missing-time", TIME, 1, 100, true);
            invalid.setTime(null);
            RecipeFlipGroup group = new RecipeFlipGroup("backup-test");
            group.getRecipeFlips().add(new RecipeFlip(Instant.ofEpochMilli(TIME), Collections.emptyMap(),
                Collections.singletonMap(4587, Collections.singletonMap(invalid.getUuid(), new PartialOffer(invalid, 1))), 0));
            account.getRecipeFlipGroups().add(group);
        }
        return Collections.singletonMap(ACCOUNT, account);
    }

    private void assertReplacementSucceeded() {
        storage.close();
        assertEquals("new-offer", onlyOffer(storage.loadAccount(ACCOUNT)).getUuid());
        assertEquals("true", storage.getSetting("migration_completed"));
        assertNotEquals("previous-import", storage.getSetting("migrated_" + ACCOUNT));
    }

    private void assertFailedImportRolledBack() throws Exception {
        assertNull("The failed account's new trades must roll back", storage.loadAccount(ACCOUNT));
        assertNull(storage.getSetting("migration_completed"));
        assertNull(storage.getSetting("migrated_" + ACCOUNT));
        assertTrue("The controller must keep recovery active after a failed rebuild", storage.requiresFullResync());
        assertTrue(storage.getConnection().getAutoCommit());
    }

    private void assertOnlyBackupContainsPreviousData() {
        File[] backups = folder.getRoot().listFiles((directory, name) ->
            name.startsWith(storage.getDbFile().getName() + ".pre-migration-") && name.endsWith(".db"));
        assertNotNull(backups);
        assertEquals("A rebuild must not create a second backup of the emptied database", 1, backups.length);
        SqliteStorage backup = new SqliteStorage(backups[0]);
        try {
            assertEquals("old-offer", onlyOffer(backup.loadAccount(ACCOUNT)).getUuid());
            assertEquals("previous-import", backup.getSetting("migrated_" + ACCOUNT));
            assertEquals("true", backup.getSetting("migration_completed"));
        } finally {
            backup.close();
        }
    }

    private OfferEvent onlyOffer(AccountData account) {
        assertNotNull(account);
        assertEquals(1, account.getTrades().size());
        assertEquals(1, account.getTrades().get(0).getHistory().getCompressedOfferEvents().size());
        return account.getTrades().get(0).getHistory().getCompressedOfferEvents().get(0);
    }
}
