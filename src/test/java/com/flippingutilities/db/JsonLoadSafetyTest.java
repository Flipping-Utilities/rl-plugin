package com.flippingutilities.db;

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
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class JsonLoadSafetyTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void brokenAccountRejectsTheWholeMigrationUntilAValidSourceIsRestored() throws Exception {
        File directory = folder.newFolder();
        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("Broken.json", "{ invalid JSON");
        originals.put("Broken.backup.json", "null");
        originals.put("Healthy.json", snapshot(42));
        writeAll(directory, originals);
        TradePersister legacy = new TradePersister(new Gson(), directory);
        assertEquals(Collections.singleton("Healthy"), legacy.loadAllAccounts().keySet());
        JsonStorage storage = new JsonStorage(new Gson(), directory);

        expectIOException(storage::load);

        assertNotPublished(directory);
        assertSources(directory, originals);
        originals.put("Broken.json", snapshot(99));
        write(directory, "Broken.json", originals.get("Broken.json"));
        Map<String, AccountData> migrated = storage.load().getAccounts();
        assertEquals(2, migrated.size());
        assertEquals(99, migrated.get("Broken").getAccumulatedSessionTimeMillis());
        assertEquals(42, migrated.get("Healthy").getAccumulatedSessionTimeMillis());
        assertSources(directory, originals);
    }

    @Test
    public void parserErrorsAndMemoryExhaustionCannotPublishEmptyAccounts() throws Exception {
        for (boolean memoryFailure : new boolean[]{false, true}) {
            File directory = folder.newFolder();
            String original = snapshot(123);
            write(directory, "Player.json", original);
            write(directory, "Player.backup.json", original);
            Gson parser = new GsonBuilder().registerTypeAdapter(AccountData.class,
                (JsonDeserializer<AccountData>) (json, type, context) -> {
                    if (memoryFailure) throw new OutOfMemoryError("Injected parser memory exhaustion");
                    throw new IllegalStateException("Injected parser failure");
                }).create();

            expectIOException(() -> new JsonStorage(parser, directory).load());

            assertNotPublished(directory);
            assertEquals(original, read(directory, "Player.json"));
            assertEquals(original, read(directory, "Player.backup.json"));
        }
    }

    @Test
    public void accountWideParserMemoryFailureCannotPublishDefaultSettings() throws Exception {
        File directory = folder.newFolder();
        write(directory, "Player.json", snapshot(11));
        write(directory, "accountwide.json", "{\"enhancedSlots\":false}");
        Gson parser = new GsonBuilder().registerTypeAdapter(AccountWideData.class,
            (JsonDeserializer<AccountWideData>) (json, type, context) -> {
                throw new OutOfMemoryError("Injected settings parser memory exhaustion");
            }).create();

        expectIOException(() -> new JsonStorage(parser, directory).load());

        assertNotPublished(directory);
        assertEquals(snapshot(11), read(directory, "Player.json"));
        assertEquals("{\"enhancedSlots\":false}", read(directory, "accountwide.json"));
    }

    @Test
    public void validBackupAndOrphanedBackupAreImportedWithoutRewritingTheirSources() throws Exception {
        File directory = folder.newFolder();
        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("Player.json", "{ invalid JSON");
        originals.put("Player.backup.json", snapshot(73));
        originals.put("Orphan.backup.json", snapshot(85));
        writeAll(directory, originals);

        Map<String, AccountData> accounts = new JsonStorage(new Gson(), directory).load().getAccounts();

        assertEquals(Set.of("Player", "Orphan"), accounts.keySet());
        assertEquals(73, accounts.get("Player").getAccumulatedSessionTimeMillis());
        assertEquals(85, accounts.get("Orphan").getAccumulatedSessionTimeMillis());
        assertSources(directory, originals);
        assertFalse(new File(directory, "Orphan.json").exists());
    }

    @Test
    public void aValidPrimaryRemainsAuthoritativeWhenABackupAlsoExists() throws Exception {
        File directory = folder.newFolder();
        write(directory, "Player.json", snapshot(99));
        write(directory, "Player.backup.json", snapshot(12));

        Map<String, AccountData> accounts = new JsonStorage(new Gson(), directory).load().getAccounts();

        assertEquals(Collections.singleton("Player"), accounts.keySet());
        assertEquals(99, accounts.get("Player").getAccumulatedSessionTimeMillis());
        assertEquals(snapshot(99), read(directory, "Player.json"));
        assertEquals(snapshot(12), read(directory, "Player.backup.json"));
    }

    @Test
    public void nullEmptyAndTrailingContentAreRejectedInsteadOfImportedAsEmptyAccounts() throws Exception {
        for (String invalid : new String[]{"null", "", "{} {}", "[]"}) {
            File directory = folder.newFolder();
            write(directory, "Player.json", invalid);
            TradePersister legacy = new TradePersister(new Gson(), directory);
            try {
                legacy.loadAccount("Player");
                fail("Invalid legacy input must fail to load");
            } catch (IllegalStateException expected) {
                assertNotNull(expected.getMessage());
            }

            expectIOException(() -> new JsonStorage(new Gson(), directory).load());

            assertNotPublished(directory);
            assertEquals(invalid, read(directory, "Player.json"));
            assertFalse(new File(directory, "Player.backup.json").exists());
        }
    }

    @Test
    public void everyHistoricalInstantEncodingMigratesToIsoWithoutLosingPrecision() throws Exception {
        Map<String, Instant> values = new LinkedHashMap<>();
        Instant whole = Instant.parse("2020-09-13T12:26:40Z");
        Instant precise = whole.plusNanos(123456789);
        values.put("1600000000000", whole);
        values.put("\"1600000000000\"", whole);
        values.put("\"2020-09-13T12:26:40Z\"", whole);
        values.put("\"2020-09-13T12:26:40.123456789Z\"", precise);
        values.put("{\"seconds\":1600000000,\"nanos\":123456789}", precise);
        values.put("{\"epochSecond\":1600000000,\"nano\":123456789}", precise);
        for (Map.Entry<String, Instant> value : values.entrySet()) {
            File directory = folder.newFolder();
            String original = "{\"lastStoredAt\":" + value.getKey() + "}";
            write(directory, "Player.json", original);
            AccountData legacy = new TradePersister(new Gson(), directory).loadAccount("Player");
            assertEquals(value.getValue(), legacy.getLastStoredAt());

            AccountData migrated = new JsonStorage(new Gson(), directory).load().getAccounts().get("Player");

            assertEquals(value.getValue(), migrated.getLastStoredAt());
            assertEquals(value.getValue(), new JsonStorage(new Gson(), directory).load()
                .getAccounts().get("Player").getLastStoredAt());
            String checkpoint = read(directory, "json-v2/checkpoint.json");
            assertTrue(checkpoint.contains("\"lastStoredAt\":\"" + value.getValue() + "\""));
            assertEquals(original, read(directory, "Player.json"));
        }
    }

    @Test
    public void malformedAccountWideDataRejectsMigrationUntilValidSettingsAreRestored() throws Exception {
        for (String invalid : Arrays.asList("null", "{ invalid", "{} {}")) {
            File directory = folder.newFolder();
            write(directory, "Player.json", snapshot(55));
            write(directory, "accountwide.json", invalid);
            JsonStorage storage = new JsonStorage(new Gson(), directory);

            expectIOException(storage::load);

            assertNotPublished(directory);
            assertEquals(invalid, read(directory, "accountwide.json"));
            assertEquals(snapshot(55), read(directory, "Player.json"));
            write(directory, "accountwide.json", "{\"enhancedSlots\":false}");
            JsonStorage.LoadedData restored = storage.load();
            assertFalse(restored.getAccountWideData().isEnhancedSlots());
            assertEquals(55, restored.getAccounts().get("Player").getAccumulatedSessionTimeMillis());
            assertEquals("{\"enhancedSlots\":false}", read(directory, "accountwide.json"));
        }
    }

    @Test
    public void temporaryPreMigrationAndSpecialFilesAreExcludedAndPreserved() throws Exception {
        File directory = folder.newFolder();
        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("Player.json", snapshot(10));
        originals.put("In progress.json.tmp", "{ incomplete");
        originals.put("Old.json.pre-migration", "{ historical");
        originals.put("Player.json.pre-migration", "{ first snapshot");
        originals.put("trades.json", "{ legacy");
        originals.put("backupcheckpoints.special.json", "{ obsolete metadata");
        writeAll(directory, originals);
        TradePersister legacy = new TradePersister(new Gson(), directory);
        assertEquals(Collections.singleton("Player"), legacy.loadAllAccounts().keySet());
        assertEquals(Collections.singleton("Player"), legacy.loadAllAccountsForMigration().keySet());

        Map<String, AccountData> migrated = new JsonStorage(new Gson(), directory).load().getAccounts();

        assertEquals(Collections.singleton("Player"), migrated.keySet());
        assertSources(directory, originals);
    }

    @Test
    public void aFreshInstallHasNoLegacyFilesToRewrite() throws Exception {
        File directory = folder.newFolder();
        assertTrue(new TradePersister(new Gson(), directory).loadAccount("New player").getTrades().isEmpty());

        assertTrue(new JsonStorage(new Gson(), directory).load().getAccounts().isEmpty());

        assertFalse(new File(directory, "New player.json").exists());
        assertFalse(new File(directory, "accountwide.json").exists());
    }

    private String snapshot(long sessionMillis) {
        return "{\"version\":1,\"accumulatedSessionTimeMillis\":" + sessionMillis + "}";
    }

    private void assertNotPublished(File directory) {
        for (String name : new String[]{"checkpoint.json", "checkpoint.previous.json", "journal.jsonl"}) {
            assertFalse("Failed migration must not publish " + name,
                directory.toPath().resolve("json-v2").resolve(name).toFile().exists());
        }
    }

    private void assertSources(File directory, Map<String, String> originals) throws IOException {
        for (Map.Entry<String, String> original : originals.entrySet()) {
            assertEquals(original.getKey(), original.getValue(), read(directory, original.getKey()));
        }
    }

    private void writeAll(File directory, Map<String, String> contents) throws IOException {
        for (Map.Entry<String, String> entry : contents.entrySet()) write(directory, entry.getKey(), entry.getValue());
    }

    private void write(File directory, String name, String contents) throws IOException {
        Files.writeString(directory.toPath().resolve(name), contents);
    }

    private String read(File directory, String name) throws IOException {
        return Files.readString(directory.toPath().resolve(name));
    }

    private void expectIOException(IoAction action) throws Exception {
        try {
            action.run();
            fail("Expected unsafe legacy input to reject migration");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
