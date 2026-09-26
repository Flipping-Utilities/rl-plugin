package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.ui.uiutilities.BrowserSqliteSession;
import com.google.gson.Gson;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.Assert.*;

public class BrowserSqliteSessionTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void conversionReadsBackRealSqliteAndExistingDatabaseDoesNotRerunMigration() throws Exception {
        java.io.File source = folder.newFolder("saves");
        Files.write(source.toPath().resolve("Player.json"), ("{\"trades\":[{\"id\":4151,\"name\":\"Abyssal whip\","
            + "\"h\":{\"sO\":[{\"uuid\":\"source-offer\",\"b\":true,\"id\":4151,\"cQIT\":7,\"p\":100,"
            + "\"t\":1577836800000,\"s\":0,\"st\":\"BOUGHT\",\"tAA\":7,\"tQIT\":7}]}}]}").getBytes(StandardCharsets.UTF_8));
        TradePersister persister = new TradePersister(new Gson(), source);
        SqliteStorage storage = new SqliteStorage(folder.getRoot().toPath().resolve("converted.db").toFile());
        try {
            Map<String, AccountData> loaded = BrowserSqliteSession.prepare(storage, persister,
                persister.loadAllAccountsForMigration(), message -> {});
            assertEquals(1, loaded.size());
            assertEquals("source-offer", loaded.get("Player").getTrades().get(0).getHistory().getCompressedOfferEvents().get(0).getUuid());
            assertEquals("true", storage.getSetting("migration_completed"));
            storage.clearSetting("migration_completed");
            Files.write(source.toPath().resolve("Player.json"), "{".getBytes(StandardCharsets.UTF_8));
            Map<String, AccountData> reopened = BrowserSqliteSession.prepare(storage, persister, null, message -> {});
            assertEquals(1, reopened.size());
            assertNull("Opening an existing database must not migrate unrelated JSON", storage.getSetting("migration_completed"));
        } finally { storage.close(); }
    }

    @Test
    public void incompleteMigrationNeverBecomesASuccessfulSession() throws Exception {
        java.io.File source = folder.newFolder("saves");
        Files.write(source.toPath().resolve("Player.json"), "{\"trades\":[]}".getBytes(StandardCharsets.UTF_8));
        TradePersister persister = new TradePersister(new Gson(), source);
        SqliteStorage storage = new SqliteStorage(folder.getRoot().toPath().resolve("failed.db").toFile());
        try {
            storage.initializeSchema();
            try (java.sql.Statement statement = storage.getConnection().createStatement()) {
                statement.execute("CREATE TRIGGER reject_account BEFORE INSERT ON accounts BEGIN SELECT RAISE(FAIL, 'write failed'); END");
            }
            try {
                BrowserSqliteSession.prepare(storage, persister, persister.loadAllAccountsForMigration(), message -> {});
                fail("An uncommitted conversion must not be offered for download");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().contains("did not complete"));
            }
        } finally { storage.close(); }
    }
}
