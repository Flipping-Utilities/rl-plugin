package com.flippingutilities.db;

import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.google.gson.Gson;
import net.runelite.client.util.Filepath;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AccountIdentityPersistenceTest {
    private static final String ID = "1234567890123456789";
    private static final String SECOND_ID = "2345678901234567890";
    private static final Instant TRADE_TIME = Instant.ofEpochSecond(1_600_000_000L);
    private static final Gson GSON = new Gson();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path directory;
    private TradePersister persister;

    @Before
    public void setUp() throws IOException {
        directory = folder.newFolder("plugin-data").toPath();
        persister = newPersister();
    }

    @Test
    public void duplicateIdentityFilesAreNotSilentlyCombined() throws Exception {
        AccountData account = account("First", 123456L);
        persister.bindAccount(ID, "First", account);
        Path original = directory.resolve(ID + "_First.json");
        Path duplicate = directory.resolve(ID + "_Second.json");
        byte[] bytes = Files.readAllBytes(original);
        Files.writeString(duplicate, new String(bytes, StandardCharsets.UTF_8).replace("First", "Second"));

        try {
            newPersister().loadAllAccounts();
            fail("Duplicate identity files must require recovery, not silently hide one history");
        } catch (IOException expected) {
            assertArrayEquals(bytes, Files.readAllBytes(original));
            assertTrue(Files.isRegularFile(duplicate));
        }
    }

    @Test
    public void legacyMigrationPreservesHistoryAndEverySupportingFile() throws Exception {
        String name = "Legacy trader";
        write(name + ".json", legacyJson(name, 123456L));
        Map<String, byte[]> sidecars = new LinkedHashMap<>();
        sidecars.put(".backup.json", legacyJson(name, 654321L).getBytes(StandardCharsets.UTF_8));
        sidecars.put(".json.pre-migration", "original pre-migration bytes".getBytes(StandardCharsets.UTF_8));
        sidecars.put(".json.tmp", "interrupted primary save".getBytes(StandardCharsets.UTF_8));
        sidecars.put(".backup.json.tmp", "interrupted backup save".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> sidecar : sidecars.entrySet()) {
            Files.write(directory.resolve(name + sidecar.getKey()), sidecar.getValue());
        }
        AccountData account = loadIndexed().get(name);

        persister.bindAccount(ID, name, account);

        assertFalse(Files.exists(directory.resolve(name + ".json")));
        assertTrue(Files.isRegularFile(directory.resolve(ID + "_" + name + ".json")));
        for (Map.Entry<String, byte[]> sidecar : sidecars.entrySet()) {
            assertFalse(Files.exists(directory.resolve(name + sidecar.getKey())));
            assertArrayEquals(sidecar.getValue(),
                Files.readAllBytes(directory.resolve(ID + "_" + name + sidecar.getKey())));
        }
        AccountData reloaded = loadIndexed().get(name);
        assertEquals(ID, reloaded.getAccountId());
        assertEquals(name, reloaded.getDisplayName());
        assertSavedTrade(reloaded, name, 123456L);
        String json = Files.readString(directory.resolve(ID + "_" + name + ".json"));
        assertFalse(json.contains("storageFileName"));
        assertFalse(json.contains("persistenceReadFailed"));
    }

    @Test
    public void nameChangeRenamesPrimaryAndBackupsWithoutChangingTheIdentity() throws Exception {
        AccountData account = account("Old name", 123456L);
        persister.bindAccount(ID, "Old name", account);
        persister.setAccountIndex(Collections.singletonMap("Old name", account));
        persister.writeBackup("Old name", account);
        persister.createPreMigrationBackup("Old name");
        byte[] backup = Files.readAllBytes(directory.resolve(ID + "_Old name.backup.json"));
        byte[] migrationBackup = Files.readAllBytes(directory.resolve(ID + "_Old name.json.pre-migration"));

        persister.bindAccount(ID, "New name", account);

        assertFalse(Files.exists(directory.resolve(ID + "_Old name.json")));
        assertFalse(Files.exists(directory.resolve(ID + "_Old name.backup.json")));
        assertFalse(Files.exists(directory.resolve(ID + "_Old name.json.pre-migration")));
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve(ID + "_New name.backup.json")));
        assertArrayEquals(migrationBackup, Files.readAllBytes(directory.resolve(ID + "_New name.json.pre-migration")));
        Map<String, AccountData> reloaded = loadIndexed();
        assertEquals(Collections.singleton("New name"), reloaded.keySet());
        assertEquals(ID, reloaded.get("New name").getAccountId());
        assertEquals("New name", reloaded.get("New name").getDisplayName());
        assertSavedTrade(reloaded.get("New name"), "New name", 123456L);

        persister.deletePreMigrationBackup("New name");
        assertFalse(Files.exists(directory.resolve(ID + "_New name.json.pre-migration")));
        persister.deleteAccount("New name");
        assertFalse(Files.exists(directory.resolve(ID + "_New name.json")));
    }

    @Test
    public void caseOnlyNameChangeUpdatesExactPrimaryAndBackupFilenames() throws Exception {
        AccountData account = account("Case name", 123456L);
        persister.bindAccount(ID, "Case name", account);
        persister.setAccountIndex(Collections.singletonMap("Case name", account));
        persister.writeBackup("Case name", account);
        byte[] backup = Files.readAllBytes(directory.resolve(ID + "_Case name.backup.json"));

        persister.bindAccount(ID, "CASE name", account);

        Set<String> filenames;
        try (Stream<Path> files = Files.list(directory)) {
            filenames = files.map(file -> file.getFileName().toString()).collect(Collectors.toSet());
        }
        // exists(oldPath) would also find the new spelling on case-insensitive filesystems.
        assertTrue(filenames.contains(ID + "_CASE name.json"));
        assertTrue(filenames.contains(ID + "_CASE name.backup.json"));
        assertFalse(filenames.contains(ID + "_Case name.json"));
        assertFalse(filenames.contains(ID + "_Case name.backup.json"));
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve(ID + "_CASE name.backup.json")));
        Map<String, AccountData> accounts = loadIndexed();
        assertEquals(Collections.singleton("CASE name"), accounts.keySet());
        assertSavedTrade(accounts.get("CASE name"), "CASE name", 123456L);
    }

    @Test
    public void anotherClientsLockBlocksMigrationAndSaveWithoutChangingHistory() throws Exception {
        String original = legacyJson("Existing", 123456L);
        write("Existing.json", original);
        AccountData account = loadIndexed().get("Existing");
        Path lockFile = directory.resolve("accounts.lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            try {
                persister.bindAccount(ID, "Existing", account);
                fail("A different client's lock must prevent migration");
            } catch (IOException expected) {
                assertEquals(original, Files.readString(directory.resolve("Existing.json")));
                assertFalse(Files.exists(directory.resolve(ID + "_Existing.json")));
                assertNull(account.getAccountId());
            }
        }
        persister.bindAccount(ID, "Existing", account);
        byte[] bound = Files.readAllBytes(directory.resolve(ID + "_Existing.json"));
        account.setAccumulatedSessionTimeMillis(777L);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            try {
                persister.writeToFile("Existing", account);
                fail("A different client's lock must prevent saving");
            } catch (IOException expected) {
                assertArrayEquals(bound, Files.readAllBytes(directory.resolve(ID + "_Existing.json")));
            }
        }
        persister.writeToFile("Existing", account);
        assertSavedTrade(loadIndexed().get("Existing"), "Existing", 777L);
    }

    @Test
    public void freshAccountCannotOverwriteExistingDiskHistoryForTheSameId() throws Exception {
        AccountData saved = account("Existing", 123456L);
        persister.bindAccount(ID, "Existing", saved);
        byte[] original = Files.readAllBytes(directory.resolve(ID + "_Existing.json"));
        TradePersister otherClient = newPersister();

        try {
            otherClient.bindAccount(ID, "New name", new AccountData());
            fail("Binding must load existing history instead of replacing it with a fresh account");
        } catch (IOException expected) {
            assertArrayEquals(original, Files.readAllBytes(directory.resolve(ID + "_Existing.json")));
            assertFalse(Files.exists(directory.resolve(ID + "_New name.json")));
            assertSavedTrade(loadIndexed().get("Existing"), "Existing", 123456L);
        }
    }

    @Test
    public void staleClientSaveAndBackupFollowTheCurrentFilenameById() throws Exception {
        AccountData account = account("Old name", 123456L);
        persister.bindAccount(ID, "Old name", account);
        TradePersister otherClient = newPersister();
        Map<String, AccountData> staleAccounts = otherClient.loadAllAccounts();
        otherClient.setAccountIndex(staleAccounts);
        AccountData stale = staleAccounts.get("Old name");
        persister.bindAccount(ID, "New name", account);

        stale.setAccumulatedSessionTimeMillis(777L);
        otherClient.writeToFile("Old name", stale);
        otherClient.writeBackup("Old name", stale);

        assertFalse(Files.exists(directory.resolve(ID + "_Old name.json")));
        assertFalse(Files.exists(directory.resolve(ID + "_Old name.backup.json")));
        assertTrue(Files.isRegularFile(directory.resolve(ID + "_New name.backup.json")));
        Map<String, AccountData> reloaded = loadIndexed();
        assertEquals(Collections.singleton("New name"), reloaded.keySet());
        assertEquals("New name", reloaded.get("New name").getDisplayName());
        assertSavedTrade(reloaded.get("New name"), "New name", 777L);
    }

    @Test
    public void delayedNotificationForRenamedOrRemovedFileCannotCreateEmptyAccount() throws Exception {
        AccountData account = account("Old name", 123456L);
        persister.bindAccount(ID, "Old name", account);
        TradePersister otherClient = newPersister();
        otherClient.setAccountIndex(otherClient.loadAllAccounts());
        persister.bindAccount(ID, "New name", account);

        AccountData notificationResult = otherClient.loadAccountFile(ID + "_Old name.json");
        if (notificationResult != null) {
            assertEquals(ID, notificationResult.getAccountId());
            assertEquals("New name", notificationResult.getDisplayName());
            assertSavedTrade(notificationResult, "New name", 123456L);
        }
        assertNull(otherClient.loadAccountFile("Never existed.json"));
        assertEquals(Collections.singleton("New name"), loadIndexed().keySet());
    }

    @Test
    public void recycledNameKeepsDifferentPlayersAndWritesSeparate() throws Exception {
        AccountData first = account("Shared name", 111L);
        AccountData second = account("Shared name", 222L);
        persister.bindAccount(ID, "Shared name", first);
        persister.bindAccount(SECOND_ID, "Shared name", second);

        Map<String, AccountData> accounts = loadIndexed();
        assertEquals(new HashSet<>(Arrays.asList("Shared name [" + ID + "]",
            "Shared name [" + SECOND_ID + "]")), accounts.keySet());
        assertEquals(111L, accounts.get("Shared name [" + ID + "]").getAccumulatedSessionTimeMillis());
        assertEquals(222L, accounts.get("Shared name [" + SECOND_ID + "]").getAccumulatedSessionTimeMillis());

        String firstLabel = "Shared name [" + ID + "]";
        accounts.get(firstLabel).setAccumulatedSessionTimeMillis(333L);
        persister.writeToFile(firstLabel, accounts.get(firstLabel));
        persister.writeBackup(firstLabel, accounts.get(firstLabel));
        persister.createPreMigrationBackup(firstLabel);
        assertTrue(Files.isRegularFile(directory.resolve(ID + "_Shared name.backup.json")));
        assertTrue(Files.isRegularFile(directory.resolve(ID + "_Shared name.json.pre-migration")));
        assertFalse(Files.exists(directory.resolve(firstLabel + ".json")));
        assertEquals(222L, loadIndexed().get("Shared name [" + SECOND_ID + "]").getAccumulatedSessionTimeMillis());
        persister.deleteAccount(firstLabel);
        assertFalse(Files.exists(directory.resolve(ID + "_Shared name.json")));
        assertTrue(Files.isRegularFile(directory.resolve(SECOND_ID + "_Shared name.json")));
    }

    @Test
    public void legacyAndIdentifiedPlayersWithSameNameAreBothKept() throws Exception {
        write("Shared name.json", legacyJson("Shared name", 111L));
        write(ID + "_Shared name.json", identifiedJson(ID, "Shared name", 222L));

        Map<String, AccountData> accounts = loadIndexed();

        assertEquals(new HashSet<>(Arrays.asList("Shared name [legacy]", "Shared name [" + ID + "]")),
            accounts.keySet());
        assertNull(accounts.get("Shared name [legacy]").getAccountId());
        assertEquals(111L, accounts.get("Shared name [legacy]").getAccumulatedSessionTimeMillis());
        assertEquals(ID, accounts.get("Shared name [" + ID + "]").getAccountId());
        assertEquals(222L, accounts.get("Shared name [" + ID + "]").getAccumulatedSessionTimeMillis());
    }

    @Test
    public void unmatchedLegacyFilesStayUntouchedAndAvailable() throws Exception {
        String unmatched = legacyJson("Forgotten old", 987654L);
        write("Forgotten old.json", unmatched);
        write("Current name.json", legacyJson("Current name", 123456L));
        persister.setupFlippingFolder();
        Map<String, AccountData> accounts = loadIndexed();

        persister.bindAccount(ID, "Current name", accounts.get("Current name"));
        persister.setupFlippingFolder();

        assertEquals(unmatched, Files.readString(directory.resolve("Forgotten old.json")));
        assertEquals(new HashSet<>(Arrays.asList("Forgotten old", "Current name")), loadIndexed().keySet());
        assertNull(loadIndexed().get("Forgotten old").getAccountId());
    }

    @Test
    public void numericPrefixInAnUnmigratedNameIsNotInventedIdentity() throws Exception {
        write("123_Alice.json", legacyJson("123_Alice", 123456L));

        Map<String, AccountData> accounts = loadIndexed();

        assertEquals(Collections.singleton("123_Alice"), accounts.keySet());
        assertNull(accounts.get("123_Alice").getAccountId());
        assertSavedTrade(accounts.get("123_Alice"), "123_Alice", 123456L);
    }

    @Test
    public void literalEncodedLookingLegacyNameIsNeverDecoded() throws Exception {
        write("@436f6e.json", legacyJson("@436f6e", 123456L));
        persister.setupFlippingFolder();
        AccountData literal = loadIndexed().get("@436f6e");
        assertNotNull(literal);
        assertSavedTrade(literal, "@436f6e", 123456L);

        persister.bindAccount(ID, "@436f6e", literal);

        assertEquals(Collections.singleton("@436f6e"), loadIndexed().keySet());
        assertEquals("@436f6e", loadIndexed().get("@436f6e").getDisplayName());
        assertFalse(Files.exists(directory.resolve("@436f6e.json")));
        assertSavedTrade(loadIndexed().get("@436f6e"), "@436f6e", 123456L);
    }

    @Test
    public void rawReservedLegacyNameMigratesWithoutConfusingLiteralAtName() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"));
        write("Con.json", legacyJson("Con", 111L));
        write("@436f6e.json", legacyJson("@436f6e", 222L));
        persister.setupFlippingFolder();
        Map<String, AccountData> accounts = loadIndexed();
        assertEquals(new HashSet<>(Arrays.asList("Con", "@436f6e")), accounts.keySet());

        persister.bindAccount(ID, "Con", accounts.get("Con"));

        assertTrue(Files.isRegularFile(directory.resolve(ID + "_Con.json")));
        assertFalse(Files.exists(directory.resolve("Con.json")));
        assertEquals(222L, loadIndexed().get("@436f6e").getAccumulatedSessionTimeMillis());
        assertSavedTrade(loadIndexed().get("Con"), "Con", 111L);
    }

    @Test
    public void unsignedIdsPreserveAllBitsAndAvoidReservedNames() throws Exception {
        String unsigned = Long.toUnsignedString(Long.MIN_VALUE);
        AccountData account = account("Con", 123456L);

        persister.bindAccount(unsigned, "Con", account);

        assertEquals("9223372036854775808", unsigned);
        assertTrue(Files.isRegularFile(directory.resolve(unsigned + "_Con.json")));
        assertEquals(unsigned, loadIndexed().get("Con").getAccountId());
        assertSavedTrade(loadIndexed().get("Con"), "Con", 123456L);
    }

    @Test
    public void unsupportedFilenameCharactersRetainTheExactDisplayName() throws Exception {
        String name = "Odd: name";
        AccountData account = account(name, 123456L);

        persister.bindAccount(ID, name, account);

        Map<String, AccountData> accounts = loadIndexed();
        assertEquals(Collections.singleton(name), accounts.keySet());
        AccountData reloaded = accounts.get(name);
        assertEquals(name, reloaded.getDisplayName());
        assertTrue(reloaded.getStorageFileName().startsWith(ID + "_"));
        assertFalse(reloaded.getStorageFileName().contains(":"));
        assertTrue(Files.isRegularFile(directory.resolve(reloaded.getStorageFileName())));
        assertSavedTrade(reloaded, name, 123456L);
        persister.writeBackup(name, reloaded);
        persister.createPreMigrationBackup(name);
        persister.deletePreMigrationBackup(name);
        persister.deleteAccount(name);
        assertTrue(loadIndexed().isEmpty());
    }

    @Test
    public void recoveredBackupAfterRenameRetainsTheCurrentNameAndStableId() throws Exception {
        AccountData account = account("Old name", 123456L);
        persister.bindAccount(ID, "Old name", account);
        persister.setAccountIndex(Collections.singletonMap("Old name", account));
        persister.writeBackup("Old name", account);
        persister.bindAccount(ID, "New name", account);
        write(ID + "_New name.json", "{ corrupt primary");

        TradePersister restarted = newPersister();
        Map<String, AccountData> accounts = restarted.loadAllAccounts();

        assertEquals(Collections.singleton("New name"), accounts.keySet());
        assertEquals(ID, accounts.get("New name").getAccountId());
        assertSavedTrade(accounts.get("New name"), "New name", 123456L);
    }

    @Test
    public void anIdentifiedAccountCannotBeReboundToAnotherPlayer() throws Exception {
        AccountData account = account("Existing", 123456L);
        persister.bindAccount(ID, "Existing", account);
        byte[] original = Files.readAllBytes(directory.resolve(ID + "_Existing.json"));

        try {
            persister.bindAccount(SECOND_ID, "Existing", account);
            fail("Existing identified history must not be assigned to a different player");
        } catch (IOException expected) {
            assertEquals(ID, account.getAccountId());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve(ID + "_Existing.json")));
            assertFalse(Files.exists(directory.resolve(SECOND_ID + "_Existing.json")));
        }
    }

    @Test
    public void unavailableOrMalformedIdNeverMovesLegacyData() throws Exception {
        String original = legacyJson("Existing", 123456L);
        write("Existing.json", original);
        for (String invalid : new String[]{Long.toUnsignedString(-1L), "-1", "not-an-id",
            "18446744073709551616", "../outside"}) {
            AccountData account = loadIndexed().get("Existing");
            try {
                persister.bindAccount(invalid, "Existing", account);
                fail("Expected invalid account ID to be rejected: " + invalid);
            } catch (IllegalArgumentException | IOException expected) {
                assertEquals(original, Files.readString(directory.resolve("Existing.json")));
                assertNull(account.getAccountId());
            }
        }
        assertEquals(Collections.singleton("Existing"), loadIndexed().keySet());
    }

    @Test
    public void occupiedMigrationDestinationNeverOverwritesEitherAccount() throws Exception {
        String original = legacyJson("Existing", 123456L);
        write("Existing.json", original);
        AccountData account = loadIndexed().get("Existing");
        String occupied = "occupied destination bytes";
        write(ID + "_Existing.json", occupied);

        try {
            persister.bindAccount(ID, "Existing", account);
            fail("An existing destination must not be overwritten during migration");
        } catch (IOException expected) {
            assertEquals(original, Files.readString(directory.resolve("Existing.json")));
            assertEquals(occupied, Files.readString(directory.resolve(ID + "_Existing.json")));
        }
    }

    @Test
    public void corruptLegacyFileCannotBeBoundAsEmptyHistory() throws Exception {
        String corrupt = "{ corrupt saved account";
        write("Existing.json", corrupt);
        AccountData failedLoad = loadIndexed().get("Existing");
        assertNotNull(failedLoad);
        assertTrue(failedLoad.isPersistenceReadFailed());

        try {
            persister.bindAccount(ID, "Existing", failedLoad);
            fail("Failed loads must not become newly identified empty accounts");
        } catch (IOException expected) {
            assertEquals(corrupt, Files.readString(directory.resolve("Existing.json")));
            assertFalse(Files.exists(directory.resolve(ID + "_Existing.json")));
        }
    }

    private TradePersister newPersister() {
        return new TradePersister(GSON, Filepath.Unchecked.getRooted(directory));
    }

    private Map<String, AccountData> loadIndexed() throws IOException {
        Map<String, AccountData> accounts = persister.loadAllAccounts();
        persister.setAccountIndex(accounts);
        return accounts;
    }

    private void write(String name, String json) throws IOException {
        Files.writeString(directory.resolve(name), json, StandardCharsets.UTF_8);
    }

    private static AccountData account(String name, long sessionMillis) {
        return GSON.fromJson(legacyJson(name, sessionMillis), AccountData.class);
    }

    // Fixture written independently of the production writer, including values beyond int range.
    private static String legacyJson(String name, long sessionMillis) {
        return "{\"version\":1,\"trades\":[{\"id\":4151,\"name\":\"Abyssal whip — saved\","
            + "\"fB\":" + GSON.toJson(name) + ",\"favorite\":true,\"h\":{\"sO\":[{"
            + "\"uuid\":\"saved-offer\",\"b\":true,\"id\":4151,\"cQIT\":3,\"p\":3000000001,"
            + "\"t\":{\"seconds\":1600000000,\"nanos\":0},\"st\":\"BOUGHT\"}]}}],"
            + "\"accumulatedSessionTimeMillis\":" + sessionMillis + ","
            + "\"lastStoredAt\":{\"seconds\":1600000000,\"nanos\":0}}";
    }

    private static String identifiedJson(String id, String name, long sessionMillis) {
        return "{\"accountId\":" + GSON.toJson(id) + ",\"displayName\":" + GSON.toJson(name)
            + "," + legacyJson(name, sessionMillis).substring(1);
    }

    private static void assertSavedTrade(AccountData account, String name, long sessionMillis) {
        assertNotNull(account);
        assertEquals(sessionMillis, account.getAccumulatedSessionTimeMillis());
        assertEquals(TRADE_TIME, account.getLastStoredAt());
        assertEquals(1, account.getTrades().size());
        FlippingItem item = account.getTrades().get(0);
        assertEquals(4151, item.getItemId());
        assertEquals("Abyssal whip — saved", item.getItemName());
        assertEquals(name, item.getFlippedBy());
        assertTrue(item.isFavorite());
        assertEquals(1, item.getHistory().getCompressedOfferEvents().size());
        OfferEvent offer = item.getHistory().getCompressedOfferEvents().get(0);
        assertEquals("saved-offer", offer.getUuid());
        assertEquals(3, offer.getCurrentQuantityInTrade());
        assertEquals(3_000_000_001L, offer.getPreTaxPrice());
        assertEquals(TRADE_TIME, offer.getTime());
    }
}
