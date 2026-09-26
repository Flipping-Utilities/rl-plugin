package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.flippingutilities.db.StorageTestOffers.complete;
import static org.junit.Assert.*;

public class StrictMigrationInputTest {
    private static final String ACCOUNT = "Saved player";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File accountDirectory;
    private SqliteStorage storage;
    private Gson gson;

    @Before
    public void setUp() throws Exception {
        accountDirectory = folder.newFolder("accounts");
        storage = new SqliteStorage(folder.newFile("trades.db"));
        storage.initializeSchema();
        gson = new Gson();
    }

    @After
    public void tearDown() {
        storage.close();
    }

    @Test
    public void unreadableSourceCannotCompleteMigrationOrReplaceStoredHistory() throws Exception {
        storage.recordTrade(ACCOUNT, complete(ACCOUNT, 4151, "persisted", 1600000000000L, 5, 100, true));
        write(ACCOUNT + ".json", "{ invalid JSON");
        MigrationService migration = new MigrationService(storage, new TradePersister(gson, accountDirectory));

        try {
            migration.migrate();
            fail("An unreadable account without a backup must stop migration");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(ACCOUNT));
        }

        assertNull(storage.getSetting("migration_completed"));
        assertNull(storage.getSetting("migrated_" + ACCOUNT));
        assertEquals("persisted", storage.loadAccount(ACCOUNT).getTrades().get(0).getHistory()
            .getCompressedOfferEvents().get(0).getUuid());
    }

    @Test
    public void validBackupIsMigratedWhenPrimaryCannotBeRead() throws Exception {
        AccountData backup = new AccountData();
        FlippingItem item = new FlippingItem(4151, "Abyssal whip", 70, ACCOUNT);
        item.getHistory().getCompressedOfferEvents().add(
            complete(ACCOUNT, 4151, "backup-offer", 1600000000000L, 7, 100, true));
        backup.getTrades().add(item);
        write(ACCOUNT + ".json", "{ invalid JSON");
        write(ACCOUNT + ".backup.json", gson.toJson(backup));
        MigrationService migration = new MigrationService(storage, new TradePersister(gson, accountDirectory));

        assertEquals(1, migration.migrate());

        assertEquals("true", storage.getSetting("migration_completed"));
        AccountData restored = storage.loadAccount(ACCOUNT);
        assertEquals("backup-offer", restored.getTrades().get(0).getHistory()
            .getCompressedOfferEvents().get(0).getUuid());
        assertEquals(7, restored.getTrades().get(0).getHistory()
            .getCompressedOfferEvents().get(0).getCurrentQuantityInTrade());
    }

    @Test
    public void nullPrimaryAndCorruptBackupCannotBecomeAnEmptyAccount() throws Exception {
        write(ACCOUNT + ".json", "null");
        write(ACCOUNT + ".backup.json", "{ invalid JSON");
        try {
            new MigrationService(storage, new TradePersister(gson, accountDirectory)).migrate();
            fail("Neither snapshot contains readable account data");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(ACCOUNT));
        }
        assertNull(storage.getSetting("migration_completed"));
        assertTrue(storage.listAccounts().isEmpty());
    }

    @Test
    public void parserMemoryFailureIsReportedAsRecoverableMigrationFailure() throws Exception {
        write(ACCOUNT + ".json", "{}");
        Gson failingParser = new GsonBuilder().registerTypeAdapter(AccountData.class,
            (JsonDeserializer<AccountData>) (json, type, context) -> {
                throw new OutOfMemoryError("Injected parser memory exhaustion");
            }).create();
        try {
            new MigrationService(storage, new TradePersister(failingParser, accountDirectory)).migrate();
            fail("Parser memory exhaustion must stop migration");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof OutOfMemoryError);
        }
        assertNull(storage.getSetting("migration_completed"));
        assertTrue(storage.listAccounts().isEmpty());
    }

    private void write(String name, String content) throws Exception {
        Files.write(new File(accountDirectory, name).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
