package com.flippingutilities.db;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.ResultSet;
import java.sql.Statement;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccountUpsertTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void updatesExistingAccountInPlaceAndPreservesKnownMetadataAndChildren() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.newFile("accounts.db"));
        try {
            storage.initializeSchema();
            storage.upsertAccount("Account", "original-player");
            int accountId = storage.getAccountId("Account");
            storage.updateAccountSessionTime("Account", 123_456L);
            storage.recordTrade("Account", complete("Account", 4151, "trade", 1700000000000L, 1, 100, true));
            storage.upsertFavorite("Account", 4151, true, "whip");
            try (Statement sql = storage.getConnection().createStatement()) {
                sql.executeUpdate("UPDATE accounts SET session_start = 1700000000000 WHERE id = " + accountId);
                sql.execute("CREATE TABLE account_child (account_id INTEGER REFERENCES accounts(id) ON DELETE CASCADE)");
                sql.executeUpdate("INSERT INTO account_child (account_id) VALUES (" + accountId + ")");
            }

            storage.upsertAccount("Account", "new-player");
            storage.upsertAccount("Account", null);
            assertEquals(accountId, storage.getAccountId("Account").intValue());
            try (Statement sql = storage.getConnection().createStatement();
                 ResultSet rows = sql.executeQuery("SELECT player_id, session_start, accumulated_time FROM accounts")) {
                assertTrue(rows.next());
                assertEquals("original-player", rows.getString("player_id"));
                assertEquals(1700000000000L, rows.getLong("session_start"));
                assertEquals(123_456L, rows.getLong("accumulated_time"));
                assertFalse(rows.next());
            }
            try (Statement sql = storage.getConnection().createStatement();
                 ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM account_child")) {
                assertTrue(rows.next());
                assertEquals("Updating an account must not trigger delete cascades", 1, rows.getInt(1));
            }
            assertEquals("trade", storage.loadAccount("Account").getTrades().get(0)
                .getHistory().getCompressedOfferEvents().get(0).getUuid());
            assertEquals("whip", storage.loadAllFavorites("Account").get(4151).get(SqliteItemStateStore.FAVORITE_CODE));
        } finally {
            storage.close();
        }
    }

    @Test
    public void fillsMissingAccountMetadataWithoutChangingIdentity() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.newFile("missing-metadata.db"));
        try {
            storage.initializeSchema();
            storage.upsertAccount("Account", null);
            int accountId = storage.getAccountId("Account");
            try (Statement sql = storage.getConnection().createStatement()) {
                sql.executeUpdate("UPDATE accounts SET session_start = NULL, accumulated_time = NULL");
            }
            long before = System.currentTimeMillis();
            storage.upsertAccount("Account", "known-player");
            long after = System.currentTimeMillis();
            assertEquals(accountId, storage.getAccountId("Account").intValue());
            try (Statement sql = storage.getConnection().createStatement();
                 ResultSet rows = sql.executeQuery("SELECT player_id, session_start, accumulated_time FROM accounts")) {
                assertTrue(rows.next());
                assertEquals("known-player", rows.getString("player_id"));
                assertTrue(rows.getLong("session_start") >= before);
                assertTrue(rows.getLong("session_start") <= after);
                assertEquals(0, rows.getLong("accumulated_time"));
                assertFalse(rows.wasNull());
            }
        } finally {
            storage.close();
        }
    }
}
