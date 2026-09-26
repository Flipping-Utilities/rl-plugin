package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.RecipeFlip;
import com.google.gson.JsonParseException;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AccountIsolationTest {
    private static final String OLD_ACCOUNT = "Old account";
    private static final String NEW_ACCOUNT = "New account";
    private static final int ITEM_ID = 4151;
    private static final long TIME = 1700000000000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private LookupHookStorage first;
    private SqliteStorage second;

    @Before
    public void openClients() throws Exception {
        File database = folder.newFile("accounts.db");
        first = new LookupHookStorage(database);
        second = new SqliteStorage(database);
        first.initializeSchema();
        second.initializeSchema();
    }

    @After
    public void closeClients() {
        first.close();
        second.close();
    }

    @Test
    public void anotherClientReusingAnAccountIdCannotRedirectReadsOrWrites() {
        first.upsertFavorite(OLD_ACCOUNT, ITEM_ID, true, "old");
        int oldId = first.getAccountId(OLD_ACCOUNT);
        replaceAccount();
        assertEquals("The regression must exercise a recycled SQLite rowid", oldId,
            second.getAccountId(NEW_ACCOUNT).intValue());

        assertTrue(first.loadAllFavorites(OLD_ACCOUNT).isEmpty());
        assertNull(first.loadAccount(OLD_ACCOUNT));
        first.recordTrade(OLD_ACCOUNT, offer("old-trade"));
        assertEquals("old-trade", first.loadAccount(OLD_ACCOUNT).getTrades().get(0)
            .getHistory().getCompressedOfferEvents().get(0).getUuid());
        assertNewAccountUntouched();
        first.deleteAccountData(OLD_ACCOUNT);
        assertNewAccountUntouched();
    }

    @Test
    public void callerOwnedDeferredWriteRejectsReplacementWithoutTouchingNewAccount() throws Exception {
        first.upsertFavorite(OLD_ACCOUNT, ITEM_ID, true, "old");
        first.afterNextAccountLookup(this::replaceAccount);

        assertCallerSnapshotWriteRejected(() -> first.recordTrade(OLD_ACCOUNT, offer("racing-trade")));
        assertTrue(first.getConnection().getAutoCommit());
        assertNull(second.loadAccount(OLD_ACCOUNT));
        assertNewAccountUntouched();
        // A later operation resolves the now-missing identity afresh and can succeed.
        first.recordTrade(OLD_ACCOUNT, offer("retry-trade"));
        assertNotNull(first.loadAccount(OLD_ACCOUNT));
        assertNewAccountUntouched();
    }

    @Test
    public void callerOwnedDeferredDeleteCannotRedirectWholeAccountDeletion() throws Exception {
        first.upsertFavorite(OLD_ACCOUNT, ITEM_ID, true, "old");
        first.afterNextAccountLookup(this::replaceAccount);

        assertCallerSnapshotWriteRejected(() -> first.deleteAccountData(OLD_ACCOUNT));
        assertTrue(first.getConnection().getAutoCommit());
        assertNewAccountUntouched();
    }

    @Test
    public void accountLoadUsesOneSnapshotWhenAnotherClientReplacesItsIdentity() {
        first.upsertFavorite(OLD_ACCOUNT, ITEM_ID, true, "old");
        first.recordTrade(OLD_ACCOUNT, offer("old-trade"));
        first.afterNextAccountLookup(this::replaceAccount);

        AccountData snapshot = first.loadAccount(OLD_ACCOUNT);
        assertNotNull(snapshot);
        assertEquals("old", snapshot.getTrades().get(0).getFavoriteCode());
        assertEquals("old-trade", snapshot.getTrades().get(0).getHistory().getCompressedOfferEvents().get(0).getUuid());
        assertNull(first.loadAccount(OLD_ACCOUNT));
        assertNewAccountUntouched();
    }

    @Test
    public void compoundOperationsAndReadsDoNotCommitTheirCallersTransaction() throws Exception {
        first.upsertAccount(OLD_ACCOUNT, null);
        RecipeFlip recipe = new RecipeFlip(Instant.ofEpochMilli(TIME), Collections.emptyMap(), Collections.emptyMap(), 0L);
        assertCallerCanRollback(storage -> storage.recordOfferUpdate(OLD_ACCOUNT, offer("updated"), Collections.emptyList()));
        assertCallerCanRollback(storage -> storage.archiveOfferAndClearSlot(OLD_ACCOUNT, 0, offer("archived")));
        assertCallerCanRollback(storage -> storage.deleteTradesByUuid(OLD_ACCOUNT, Collections.singletonList("absent")));
        assertCallerCanRollback(storage -> storage.insertRecipeFlip(OLD_ACCOUNT, "recipe", recipe));
        first.insertRecipeFlip(OLD_ACCOUNT, "recipe", recipe);
        assertCallerCanRollback(storage -> storage.deleteRecipeFlip(OLD_ACCOUNT, "recipe", recipe.getTimeOfCreation()));
        assertCallerCanRollback(storage -> storage.deleteRecipeFlipsSince(OLD_ACCOUNT, "recipe", Instant.EPOCH));
        assertCallerCanRollback(storage -> storage.deleteAccountData(OLD_ACCOUNT));
        assertCallerCanRollback(storage -> {
            assertNotNull(storage.loadAccount(OLD_ACCOUNT));
            assertTrue(storage.loadAllFavorites(OLD_ACCOUNT).containsKey(999));
            storage.loadAllGeLimitStates(OLD_ACCOUNT);
            storage.listAccounts();
        });
    }

    @Test
    public void failedNestedReadLeavesRollbackToItsCaller() throws Exception {
        first.recordTrade(OLD_ACCOUNT, offer("valid-trade"));
        Connection connection = first.getConnection();
        connection.setAutoCommit(false);
        try {
            first.upsertFavorite(OLD_ACCOUNT, 999, true, "uncommitted");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE trades SET offer_json = 'invalid-json'");
            }
            try {
                first.loadAccount(OLD_ACCOUNT);
                fail("The malformed offer must fail account reconstruction");
            } catch (JsonParseException expected) {
                assertFalse(connection.getAutoCommit());
                assertTrue("The nested read must not roll back its caller's prior writes",
                    first.loadAllFavorites(OLD_ACCOUNT).containsKey(999));
                assertFalse("The nested read must not commit its caller's prior writes",
                    second.loadAllFavorites(OLD_ACCOUNT).containsKey(999));
            }
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        assertFalse(first.loadAllFavorites(OLD_ACCOUNT).containsKey(999));
        assertEquals("valid-trade", first.loadAccount(OLD_ACCOUNT).getTrades().get(0)
            .getHistory().getCompressedOfferEvents().get(0).getUuid());
    }

    private void assertCallerCanRollback(Consumer<SqliteStorage> operation) throws SQLException {
        Connection connection = first.getConnection();
        connection.setAutoCommit(false);
        try {
            first.upsertFavorite(OLD_ACCOUNT, 999, true, "uncommitted");
            operation.accept(first);
            assertFalse("Nested operations must leave autocommit disabled", connection.getAutoCommit());
            assertFalse("Another client must not observe an implicit commit",
                second.loadAllFavorites(OLD_ACCOUNT).containsKey(999));
            connection.rollback();
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        assertNotNull(first.loadAccount(OLD_ACCOUNT));
        assertFalse(first.loadAllFavorites(OLD_ACCOUNT).containsKey(999));
    }

    private void replaceAccount() {
        second.deleteAccountData(OLD_ACCOUNT);
        second.upsertFavorite(NEW_ACCOUNT, ITEM_ID, true, "new");
    }

    private void assertNewAccountUntouched() {
        Map<String, Object> favorite = second.loadAllFavorites(NEW_ACCOUNT).get(ITEM_ID);
        assertEquals("new", favorite.get(SqliteItemStateStore.FAVORITE_CODE));
        AccountData account = second.loadAccount(NEW_ACCOUNT);
        assertEquals(1, account.getTrades().size());
        assertTrue("The replacement account must not receive the original account's history",
            account.getTrades().get(0).getHistory().getCompressedOfferEvents().isEmpty());
    }

    private OfferEvent offer(String uuid) {
        return complete(OLD_ACCOUNT, ITEM_ID, uuid, TIME, 1, 100, true);
    }

    private void assertCallerSnapshotWriteRejected(Runnable operation) throws SQLException {
        Connection connection = first.getConnection();
        connection.setAutoCommit(false);
        try {
            operation.run();
            fail("Writing after a concurrent account replacement must reject the stale snapshot");
        } catch (IllegalStateException failure) {
            assertFalse("A nested write must preserve its caller's transaction", connection.getAutoCommit());
            assertTrue("SQLite must reject the stale write snapshot, not corrupt another account: " + failure,
                failure.getCause() instanceof SQLException && failure.getCause().getMessage().contains("SQLITE_BUSY"));
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    /** Runs a real second-client write after the first client's ID query closes, without timing or threads. */
    private static final class LookupHookStorage extends SqliteStorage {
        private Connection wrappedConnection;
        private Connection delegateConnection;
        private Runnable afterLookup;

        private LookupHookStorage(File file) {
            super(file);
        }

        private void afterNextAccountLookup(Runnable action) {
            afterLookup = action;
        }

        @Override
        public synchronized Connection getConnection() throws SQLException {
            Connection delegate = super.getConnection();
            if (delegate != delegateConnection) {
                delegateConnection = delegate;
                wrappedConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(delegate, method, args);
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String &&
                            ((String) args[0]).startsWith("SELECT id FROM accounts WHERE display_name")) {
                            PreparedStatement statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                    Object value = invoke(statement, statementMethod, statementArgs);
                                    if (statementMethod.getName().equals("close") && afterLookup != null) {
                                        Runnable action = afterLookup;
                                        afterLookup = null;
                                        action.run();
                                    }
                                    return value;
                                });
                        }
                        return result;
                    });
            }
            return wrappedConnection;
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
