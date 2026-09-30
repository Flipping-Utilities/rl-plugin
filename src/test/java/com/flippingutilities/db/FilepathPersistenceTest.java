package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.flippingutilities.model.BackupCheckpoints;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.Option;
import com.google.gson.Gson;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FilepathPersistenceTest {
    private static final String ACCOUNT = "Legacy trader";
    private static final Instant TRADE_TIME = Instant.ofEpochSecond(1_600_000_000L);
    // An existing on-disk document, independent of the writer under test.
    private static final String LEGACY_ACCOUNT_JSON = "{"
        + "\"trades\":[{\"id\":4151,\"name\":\"Abyssal whip — saved\",\"tGL\":70,"
        + "\"fB\":\"Legacy trader\",\"favorite\":true,\"h\":{\"sO\":[{"
        + "\"uuid\":\"saved-offer\",\"b\":true,\"id\":4151,\"cQIT\":3,"
        + "\"p\":3000000001,\"t\":{\"seconds\":1600000000,\"nanos\":0},\"st\":\"BOUGHT\"}]}}],"
        + "\"accumulatedSessionTimeMillis\":123456,"
        + "\"lastStoredAt\":{\"seconds\":1600000000,\"nanos\":0}}";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path directory;
    private TradePersister persister;

    @Before
    public void setUp() throws IOException {
        directory = folder.newFolder("plugin-data").toPath();
        persister = new TradePersister(new Gson(), Filepath.Unchecked.getRooted(directory));
    }

    @Test
    public void existingAccountSurvivesSetupAndReplacement() throws Exception {
        writeFixture(ACCOUNT + ".json", LEGACY_ACCOUNT_JSON);

        persister.setupFlippingFolder();
        AccountData account = persister.loadAccount(ACCOUNT);
        assertSavedTrade(account);
        assertTrue(account.needsMigration());
        assertEquals(123456L, account.getAccumulatedSessionTimeMillis());

        account.setVersion(AccountData.CURRENT_VERSION);
        account.setAccumulatedSessionTimeMillis(654321L);
        persister.writeToFile(ACCOUNT, account);

        AccountData reloaded = persister.loadAccount(ACCOUNT);
        assertSavedTrade(reloaded);
        assertEquals(Integer.valueOf(AccountData.CURRENT_VERSION), reloaded.getVersion());
        assertEquals(654321L, reloaded.getAccumulatedSessionTimeMillis());
        assertFalse(Files.exists(directory.resolve(ACCOUNT + ".json.tmp")));
    }

    @Test
    public void corruptOrMissingPrimaryRecoversSavedBackup() throws Exception {
        writeFixture(ACCOUNT + ".backup.json", LEGACY_ACCOUNT_JSON);
        writeFixture(ACCOUNT + ".json", "{ corrupt account data");

        assertSavedTrade(persister.loadAccount(ACCOUNT));
        assertEquals("{ corrupt account data", Files.readString(directory.resolve(ACCOUNT + ".json")));

        Files.delete(directory.resolve(ACCOUNT + ".json"));
        assertSavedTrade(persister.loadAccount(ACCOUNT));
        assertEquals(LEGACY_ACCOUNT_JSON, Files.readString(directory.resolve(ACCOUNT + ".backup.json")));
    }

    @Test
    public void accountWideSettingsAndBackupCheckpointsSurviveReload() throws Exception {
        AccountWideData settings = new AccountWideData();
        settings.setEnhancedSlots(false);
        settings.setShouldMakeNewAdditions(false);
        settings.setOptions(Collections.singletonList(new Option("j", Option.WIKI_BUY, "+17", false)));
        persister.writeToFile("accountwide", settings);

        BackupCheckpoints checkpoints = new BackupCheckpoints();
        checkpoints.getAccountToBackupTime().put(ACCOUNT, TRADE_TIME);
        persister.writeToFile("backupcheckpoints.special", checkpoints);

        AccountWideData reloaded = persister.loadAccountWideData();
        assertFalse(reloaded.isEnhancedSlots());
        assertFalse(reloaded.isShouldMakeNewAdditions());
        assertEquals(settings.getOptions(), reloaded.getOptions());
        assertEquals(checkpoints.getAccountToBackupTime(), persister.fetchBackupCheckpoints().getAccountToBackupTime());
        assertFalse(persister.fetchBackupCheckpoints().shouldBackup(ACCOUNT, TRADE_TIME));
        assertTrue(persister.fetchBackupCheckpoints().shouldBackup(ACCOUNT, TRADE_TIME.plusSeconds(1)));
    }

    @Test
    public void preMigrationBackupPreservesOriginalAcrossRepeatedAttempts() throws Exception {
        writeFixture(ACCOUNT + ".json", LEGACY_ACCOUNT_JSON);
        byte[] original = Files.readAllBytes(directory.resolve(ACCOUNT + ".json"));

        persister.createPreMigrationBackup(ACCOUNT);
        AccountData updated = persister.loadAccount(ACCOUNT);
        updated.setAccumulatedSessionTimeMillis(999L);
        persister.writeToFile(ACCOUNT, updated);
        persister.createPreMigrationBackup(ACCOUNT);

        Path backup = directory.resolve(ACCOUNT + ".json.pre-migration");
        assertArrayEquals(original, Files.readAllBytes(backup));
        persister.deletePreMigrationBackup(ACCOUNT);
        persister.deletePreMigrationBackup(ACCOUNT);
        assertFalse(Files.exists(backup));
        assertEquals(999L, persister.loadAccount(ACCOUNT).getAccumulatedSessionTimeMillis());
        assertSavedTrade(persister.loadAccount(ACCOUNT));
    }

    @Test
    public void accountDiscoveryIgnoresDirectoriesAndSupportingFiles() throws Exception {
        writeFixture(ACCOUNT + ".json", LEGACY_ACCOUNT_JSON);
        for (String name : new String[]{"accountwide.json", "Other.backup.json", "backupcheckpoints.special.json",
            "Interrupted.json.tmp", "Previous.json.pre-migration", "notes.txt"}) {
            writeFixture(name, LEGACY_ACCOUNT_JSON);
        }
        Path nested = Files.createDirectory(directory.resolve("Directory.json"));
        Files.writeString(nested.resolve("Nested.json"), LEGACY_ACCOUNT_JSON, StandardCharsets.UTF_8);

        Map<String, AccountData> accounts = persister.loadAllAccounts();

        assertEquals(Collections.singleton(ACCOUNT), accounts.keySet());
        assertSavedTrade(accounts.get(ACCOUNT));
    }

    @Test
    public void failedSaveLeavesPreviouslySavedAccountIntact() throws Exception {
        writeFixture(ACCOUNT + ".json", LEGACY_ACCOUNT_JSON);
        byte[] original = Files.readAllBytes(directory.resolve(ACCOUNT + ".json"));
        AccountData updated = persister.loadAccount(ACCOUNT);
        updated.setAccumulatedSessionTimeMillis(999L);
        // A real filesystem conflict rejects the temporary write on every platform.
        Path blockedTemp = Files.createDirectory(directory.resolve(ACCOUNT + ".json.tmp"));
        Path blocker = Files.writeString(blockedTemp.resolve("keep.txt"), "occupied", StandardCharsets.UTF_8);

        try {
            persister.writeToFile(ACCOUNT, updated);
            fail("Saving must fail while the temporary file is a directory");
        } catch (IOException expected) {
            assertArrayEquals(original, Files.readAllBytes(directory.resolve(ACCOUNT + ".json")));
            assertEquals("occupied", Files.readString(blocker));
        }

        Files.delete(blocker);
        Files.delete(blockedTemp);
        persister.writeToFile(ACCOUNT, updated);
        assertSavedTrade(persister.loadAccount(ACCOUNT));
        assertEquals(999L, persister.loadAccount(ACCOUNT).getAccumulatedSessionTimeMillis());
        assertFalse(Files.exists(blockedTemp));
    }

    @Test
    public void accountNamesCannotWriteOutsideTheDataDirectory() throws Exception {
        Path outside = folder.getRoot().toPath().resolve("outside.json");
        Files.writeString(outside, "unrelated saved data", StandardCharsets.UTF_8);

        for (String accountName : new String[]{"../outside", "nested/../../outside",
            folder.getRoot().toPath().resolve("outside").toString()}) {
            try {
                persister.writeToFile(accountName, new AccountData());
                fail("Account names must not escape the data directory: " + accountName);
            } catch (IllegalArgumentException expected) {
                assertEquals("unrelated saved data", Files.readString(outside));
            }
        }
        assertTrue(persister.loadAllAccounts().isEmpty());
        assertFalse(Files.exists(folder.getRoot().toPath().resolve("outside.json.tmp")));
    }

    @Test
    public void unsupportedLegacyFilenameStopsSetupWithoutChangingSavedFiles() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));
        writeFixture("Con.json", LEGACY_ACCOUNT_JSON);
        writeFixture("trades.json", "obsolete combined trades");

        try {
            persister.setupFlippingFolder();
            fail("Setup must report an existing account that Filepath cannot save");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Con.json"));
            assertEquals(LEGACY_ACCOUNT_JSON, Files.readString(directory.resolve("Con.json")));
            assertEquals("obsolete combined trades", Files.readString(directory.resolve("trades.json")));
        }
    }

    private void writeFixture(String filename, String contents) throws IOException {
        Files.writeString(directory.resolve(filename), contents, StandardCharsets.UTF_8);
    }

    private static void assertSavedTrade(AccountData account) {
        assertNotNull(account);
        assertEquals(1, account.getTrades().size());
        FlippingItem item = account.getTrades().get(0);
        assertEquals(4151, item.getItemId());
        assertEquals("Abyssal whip — saved", item.getItemName());
        assertEquals(ACCOUNT, item.getFlippedBy());
        assertTrue(item.isFavorite());
        assertEquals(1, item.getHistory().getCompressedOfferEvents().size());
        OfferEvent offer = item.getHistory().getCompressedOfferEvents().get(0);
        assertEquals("saved-offer", offer.getUuid());
        assertEquals(3, offer.getCurrentQuantityInTrade());
        assertEquals(3_000_000_001L, offer.getPreTaxPrice());
        assertEquals(TRADE_TIME, offer.getTime());
        assertEquals(TRADE_TIME, account.getLastStoredAt());
    }
}
