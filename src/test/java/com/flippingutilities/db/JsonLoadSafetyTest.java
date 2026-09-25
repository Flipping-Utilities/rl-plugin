package com.flippingutilities.db;

import com.flippingutilities.controller.DataHandler;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.AccountWideData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.*;

public class JsonLoadSafetyTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void failedAccountCannotOverwriteEitherSnapshotWhileHealthyAccountsStillSave() throws Exception {
        write("Broken.json", "{ invalid JSON");
        write("Broken.backup.json", "null");
        write("Healthy.json", "{\"version\":1,\"accumulatedSessionTimeMillis\":42}");
        TradePersister persister = persister(new Gson());

        Map<String, AccountData> accounts = persister.loadAllAccounts();
        assertFalse(accounts.containsKey("Broken"));
        assertEquals(42L, accounts.get("Healthy").getAccumulatedSessionTimeMillis());
        persister.writeToFile("Healthy", accounts.get("Healthy"));
        assertWriteBlocked(persister, "Broken", "{ invalid JSON");
        assertWriteBlocked(persister, "Broken.backup", "null");

        // Recovery uses the same persister instance; saves resume after a valid restore is loaded.
        write("Broken.json", "{\"version\":1,\"accumulatedSessionTimeMillis\":99}");
        AccountData restored = persister.loadAccount("Broken");
        assertEquals(99L, restored.getAccumulatedSessionTimeMillis());
        persister.writeToFile("Broken", restored);
        assertEquals(99L, persister.loadAccount("Broken").getAccumulatedSessionTimeMillis());
    }

    @Test
    public void parserMemoryFailureProtectsOriginalFromAutosave() throws Exception {
        write("Player.json", "{}");
        Gson parser = new GsonBuilder().registerTypeAdapter(AccountData.class,
            (JsonDeserializer<AccountData>) (json, type, context) -> {
                throw new OutOfMemoryError("Injected parser memory exhaustion");
            }).create();
        TradePersister persister = persister(parser);
        DataHandler handler = handler(persister);
        handler.loadAccountData("Player");
        handler.markDataAsHavingChanged("Player");
        assertFalse(handler.storeData());
        assertEquals("{}", read("Player.json"));
        assertFalse(new File(folder.getRoot(), "Player.backup.json").exists());
    }

    @Test
    public void preparationFailureKeepsCachedDataAndProtectsOriginalFromAutosave() throws Exception {
        write("Player.json", "{\"version\":1,\"accumulatedSessionTimeMillis\":42}");
        TradePersister persister = persister(new Gson());
        DataHandler handler = handler(persister);
        handler.loadAccountData("Player");
        AccountData cached = handler.viewAccountData("Player");

        String invalid = "{\"trades\":null}";
        write("Player.json", invalid);
        handler.loadAccountData("Player");
        assertSame(cached, handler.getAccountData("Player"));
        assertFalse(handler.storeData());
        assertEquals(invalid, read("Player.json"));
        assertWriteBlocked(persister, "Player.backup", null);

        write("Player.json", "{\"version\":1,\"accumulatedSessionTimeMillis\":99}");
        handler.loadAccountData("Player");
        assertTrue(handler.storeData());
        assertEquals(99L, persister.loadAccount("Player").getAccumulatedSessionTimeMillis());
    }

    @Test
    public void validBackupAndGenuinelyNewAccountCanBeSaved() throws Exception {
        write("Player.json", "{ invalid JSON");
        write("Player.backup.json", "{\"version\":1,\"accumulatedSessionTimeMillis\":73}");
        TradePersister persister = persister(new Gson());
        AccountData recovered = persister.loadAccount("Player");
        assertEquals(73L, recovered.getAccumulatedSessionTimeMillis());
        persister.writeToFile("Player", recovered);
        assertEquals(73L, persister.loadAccount("Player").getAccumulatedSessionTimeMillis());
        persister.writeToFile("New player", persister.loadAccount("New player"));
        assertTrue(new File(folder.getRoot(), "New player.json").isFile());
    }

    @Test
    public void nullOrTrailingContentIsNotAcceptedAsAnEmptyAccount() throws Exception {
        for (String invalid : new String[]{"null", "", "{} {}"}) {
            write("Player.json", invalid);
            TradePersister persister = persister(new Gson());
            try {
                persister.loadAccount("Player");
                fail("Invalid snapshot must fail to load");
            } catch (IllegalStateException expected) {
                assertWriteBlocked(persister, "Player", invalid);
            }
        }
    }

    @Test
    public void historicalInstantEncodingsSurviveLoadingAndSaving() throws Exception {
        for (String encoded : new String[]{"1600000000000", "\"2020-09-13T12:26:40Z\"",
            "{\"seconds\":1600000000,\"nanos\":0}", "{\"epochSecond\":1600000000,\"nano\":0}"}) {
            write("Player.json", "{\"lastStoredAt\":" + encoded + "}");
            TradePersister persister = persister(new Gson());
            AccountData account = persister.loadAccount("Player");
            assertEquals(1600000000000L, account.getLastStoredAt().toEpochMilli());
            persister.writeToFile("Player", account);
            assertEquals(1600000000000L, persister.loadAccount("Player").getLastStoredAt().toEpochMilli());
        }
    }

    @Test
    public void malformedAccountWideDataCannotBeOverwrittenWithDefaults() throws Exception {
        write("accountwide.json", "null");
        TradePersister persister = persister(new Gson());
        try {
            persister.loadAccountWideData();
            fail("Null account-wide data must fail to load");
        } catch (IOException expected) {
            assertWriteBlocked(persister, "accountwide", "null");
        }
        write("accountwide.json", "{}");
        AccountWideData restored = persister.loadAccountWideData();
        restored.setDefaults();
        persister.writeToFile("accountwide", restored);
        assertFalse(persister.loadAccountWideData().getOptions().isEmpty());
    }

    @Test
    public void temporaryAndLegacyFilesAreNotAccountsAndPreMigrationBackupIsRetained() throws Exception {
        write("Player.json", "{\"version\":1}");
        write("In progress.json.tmp", "{ incomplete");
        write("Old.json.pre-migration", "{ historical");
        write("trades.json", "{ legacy");
        TradePersister persister = persister(new Gson());
        assertEquals(1, persister.loadAllAccounts().size());
        assertEquals(1, persister.loadAllAccountsForMigration().size());
        persister.createPreMigrationBackup("Player");
        persister.writeToFile("Player", new AccountData());
        persister.createPreMigrationBackup("Player");
        assertEquals("{\"version\":1}", read("Player.json.pre-migration"));
        assertEquals("{ legacy", read("trades.json"));
    }

    private DataHandler handler(TradePersister persister) throws Exception {
        FlippingPlugin plugin = new FlippingPlugin();
        Field field = FlippingPlugin.class.getDeclaredField("tradePersister");
        field.setAccessible(true);
        field.set(plugin, persister);
        return new DataHandler(plugin);
    }

    private TradePersister persister(Gson gson) {
        return new TradePersister(gson, folder.getRoot());
    }

    private void assertWriteBlocked(TradePersister persister, String name, String expectedContent) throws Exception {
        try {
            persister.writeToFile(name, new AccountData());
            fail("Failed reads must block saving " + name);
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Refusing to overwrite"));
        }
        if (expectedContent == null) {
            assertFalse(new File(folder.getRoot(), name + ".json").exists());
        } else {
            assertEquals(expectedContent, read(name + ".json"));
        }
    }

    private void write(String name, String contents) throws IOException {
        Files.write(new File(folder.getRoot(), name).toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(new File(folder.getRoot(), name).toPath()), StandardCharsets.UTF_8);
    }
}
