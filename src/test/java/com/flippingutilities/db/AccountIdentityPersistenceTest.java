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
import java.security.MessageDigest;
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
    public void corruptIdentityMarkerDoesNotBlockHealthyHistory() throws Exception {
        persister.bindAccount(ID, "Old", account("Old", 123456L));
        write(ID + "_Old.identity.special.json", "{ damaged marker");
        TradePersister restarted = newPersister();
        AccountData saved = restarted.loadAllAccounts().get("Old");

        restarted.bindAccount(ID, "New", saved);

        assertSavedTrade(newPersister().loadAllAccounts().get("New"), "New", 123456L);
        assertFalse(Files.exists(directory.resolve(ID + "_Old.json")));
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
    public void occupiedBackupDestinationBlocksFirstMigrationWithoutChangingFiles() throws Exception {
        String original = legacyJson("Existing", 123456L);
        String unrelated = legacyJson("Unrelated", 987654L);
        write("Existing.json", original);
        AccountData account = loadIndexed().get("Existing");
        write(ID + "_Existing.backup.json", unrelated);

        try {
            persister.bindAccount(ID, "Existing", account);
            fail("An unrelated destination backup must not be adopted during first migration");
        } catch (IOException expected) {
            assertEquals(original, Files.readString(directory.resolve("Existing.json")));
            assertEquals(unrelated, Files.readString(directory.resolve(ID + "_Existing.backup.json")));
            assertFalse(Files.exists(directory.resolve(ID + "_Existing.json")));
            assertNull(account.getAccountId());
        }
    }

    @Test
    public void corruptLegacyPrimaryRecoveredFromBackupCanMigrateAndSave() throws Exception {
        String backup = legacyJson("Existing", 123456L);
        write("Existing.json", "{ corrupt primary");
        write("Existing.backup.json", backup);
        AccountData recovered = loadIndexed().get("Existing");
        assertFalse(recovered.isPersistenceReadFailed());
        assertSavedTrade(recovered, "Existing", 123456L);

        persister.bindAccount(ID, "Existing", recovered);
        recovered.setAccumulatedSessionTimeMillis(777L);
        persister.writeToFile("Existing", recovered);

        assertFalse(Files.exists(directory.resolve("Existing.json")));
        assertFalse(Files.exists(directory.resolve("Existing.backup.json")));
        assertEquals(backup, Files.readString(directory.resolve(ID + "_Existing.backup.json")));
        Map<String, AccountData> accounts = newPersister().loadAllAccounts();
        assertEquals(Collections.singleton("Existing"), accounts.keySet());
        assertEquals(ID, accounts.get("Existing").getAccountId());
        assertSavedTrade(accounts.get("Existing"), "Existing", 777L);
    }

    @Test
    public void restartResumesRenameWhenIdentityMarkerAlreadyMoved() throws Exception {
        String oldStem = ID + "_Old name";
        String newStem = ID + "_New name";
        String primary = identifiedJson(ID, "New name", 123456L);
        String backup = legacyJson("Old name", 987654L);
        String marker = identityMarker(ID, "Old name");
        // A process died after moving the identity marker, before moving the history family.
        write(oldStem + ".json", primary);
        write(oldStem + ".backup.json", backup);
        write(newStem + ".identity.special.json", marker);
        write(newStem + ".rename.special.json", renameJournal(oldStem, newStem, "New name",
            Map.of(".json", primary, ".backup.json", backup, ".identity.special.json", marker), false));
        TradePersister restarted = newPersister();
        Map<String, AccountData> interrupted = restarted.loadAllAccounts();
        assertEquals(Collections.singleton("New name"), interrupted.keySet());

        restarted.bindAccount(ID, "New name", interrupted.get("New name"));

        assertFalse(Files.exists(directory.resolve(oldStem + ".json")));
        assertFalse(Files.exists(directory.resolve(oldStem + ".backup.json")));
        assertFalse(Files.exists(directory.resolve(oldStem + ".identity.special.json")));
        assertFalse(Files.exists(directory.resolve(newStem + ".rename.special.json")));
        assertTrue(Files.isRegularFile(directory.resolve(newStem + ".identity.special.json")));
        assertEquals(backup, Files.readString(directory.resolve(newStem + ".backup.json")));
        Map<String, AccountData> completed = newPersister().loadAllAccounts();
        assertEquals(Collections.singleton("New name"), completed.keySet());
        assertEquals(ID, completed.get("New name").getAccountId());
        assertSavedTrade(completed.get("New name"), "New name", 123456L);
    }

    @Test
    public void restartResumesRenameWithoutReplacingAnAlreadyMovedBackup() throws Exception {
        String oldStem = ID + "_Old name";
        String newStem = ID + "_New name";
        String primary = identifiedJson(ID, "New name", 123456L);
        String backup = legacyJson("Old name", 987654L);
        String marker = identityMarker(ID, "Old name");
        // The destination label was committed before moving files; the old backup has already moved.
        write(oldStem + ".json", primary);
        write(oldStem + ".identity.special.json", marker);
        write(newStem + ".backup.json", backup);
        write(newStem + ".rename.special.json", renameJournal(oldStem, newStem, "New name",
            Map.of(".json", primary, ".backup.json", backup, ".identity.special.json", marker), false));
        TradePersister restarted = newPersister();
        Map<String, AccountData> interrupted = restarted.loadAllAccounts();

        restarted.bindAccount(ID, "New name", interrupted.get("New name"));

        assertFalse(Files.exists(directory.resolve(oldStem + ".json")));
        assertFalse(Files.exists(directory.resolve(oldStem + ".identity.special.json")));
        assertFalse(Files.exists(directory.resolve(newStem + ".rename.special.json")));
        assertEquals(backup, Files.readString(directory.resolve(newStem + ".backup.json")));
        assertTrue(Files.isRegularFile(directory.resolve(newStem + ".identity.special.json")));
        Map<String, AccountData> completed = newPersister().loadAllAccounts();
        assertEquals(Collections.singleton("New name"), completed.keySet());
        assertEquals(ID, completed.get("New name").getAccountId());
        assertSavedTrade(completed.get("New name"), "New name", 123456L);
    }

    @Test
    public void restartRejectsDestinationBackupWhoseBytesDoNotMatchTheJournal() throws Exception {
        String oldStem = ID + "_Old name";
        String newStem = ID + "_New name";
        String primary = identifiedJson(ID, "New name", 123456L);
        String intendedBackup = legacyJson("Old name", 987654L);
        String unrelatedBackup = legacyJson("Unrelated", 999L);
        String marker = identityMarker(ID, "Old name");
        String journal = renameJournal(oldStem, newStem, "New name",
            Map.of(".json", primary, ".backup.json", intendedBackup, ".identity.special.json", marker), false);
        write(oldStem + ".json", primary);
        write(oldStem + ".identity.special.json", marker);
        write(newStem + ".backup.json", unrelatedBackup);
        write(newStem + ".rename.special.json", journal);

        try {
            newPersister().loadAllAccounts();
            fail("An interrupted rename must not adopt a destination whose bytes differ from the journal");
        } catch (IOException expected) {
            assertEquals(primary, Files.readString(directory.resolve(oldStem + ".json")));
            assertEquals(marker, Files.readString(directory.resolve(oldStem + ".identity.special.json")));
            assertEquals(unrelatedBackup, Files.readString(directory.resolve(newStem + ".backup.json")));
            assertEquals(journal, Files.readString(directory.resolve(newStem + ".rename.special.json")));
            assertFalse(Files.exists(directory.resolve(newStem + ".json")));
            assertFalse(Files.exists(directory.resolve(newStem + ".identity.special.json")));
        }
    }

    @Test
    public void restartRecoversPrimaryFromCaseOnlyRenameIntermediate() throws Exception {
        String oldStem = ID + "_Case name";
        String newStem = ID + "_CASE name";
        String primary = identifiedJson(ID, "CASE name", 123456L);
        String backup = legacyJson("Case name", 987654L);
        String marker = identityMarker(ID, "Case name");
        write(newStem + ".json.rename.tmp", primary);
        write(newStem + ".backup.json", backup);
        write(newStem + ".identity.special.json", marker);
        write(newStem + ".rename.special.json", renameJournal(oldStem, newStem, "CASE name",
            Map.of(".json", primary, ".backup.json", backup, ".identity.special.json", marker), false));

        Map<String, AccountData> recovered = newPersister().loadAllAccounts();

        assertEquals(Collections.singleton("CASE name"), recovered.keySet());
        assertEquals(ID, recovered.get("CASE name").getAccountId());
        assertSavedTrade(recovered.get("CASE name"), "CASE name", 123456L);
        assertEquals(primary, Files.readString(directory.resolve(newStem + ".json")));
        assertEquals(backup, Files.readString(directory.resolve(newStem + ".backup.json")));
        assertFalse(Files.exists(directory.resolve(newStem + ".json.rename.tmp")));
        assertFalse(Files.exists(directory.resolve(newStem + ".rename.special.json")));
        try (Stream<Path> files = Files.list(directory)) {
            Set<String> filenames = files.map(file -> file.getFileName().toString()).collect(Collectors.toSet());
            assertTrue(filenames.contains(newStem + ".json"));
            assertFalse(filenames.contains(oldStem + ".json"));
        }
    }

    @Test
    public void completedJournalCleanupIsSafeAfterFinalIdentityWasAlreadyPublished() throws Exception {
        String oldStem = ID + "_Old name";
        String newStem = ID + "_New name";
        String primary = identifiedJson(ID, "New name", 123456L);
        String oldMarker = identityMarker(ID, "Old name");
        write(newStem + ".json", primary);
        write(newStem + ".identity.special.json", identityMarker(ID, "New name"));
        // The move hashes describe the old marker; cleanup has already replaced it with the final marker.
        write(newStem + ".rename.special.json", renameJournal(oldStem, newStem, "New name",
            Map.of(".json", primary, ".identity.special.json", oldMarker), true));

        Map<String, AccountData> recovered = newPersister().loadAllAccounts();
        Map<String, AccountData> reloadedAgain = newPersister().loadAllAccounts();

        assertEquals(Collections.singleton("New name"), recovered.keySet());
        assertEquals(Collections.singleton("New name"), reloadedAgain.keySet());
        assertEquals(ID, recovered.get("New name").getAccountId());
        assertSavedTrade(recovered.get("New name"), "New name", 123456L);
        assertEquals(primary, Files.readString(directory.resolve(newStem + ".json")));
        assertFalse(Files.exists(directory.resolve(newStem + ".rename.special.json")));
        AccountData marker = GSON.fromJson(Files.readString(directory.resolve(newStem + ".identity.special.json")),
            AccountData.class);
        assertEquals(ID, marker.getAccountId());
        assertEquals("New name", marker.getDisplayName());
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

    private static String identityMarker(String id, String name) {
        return "{\"accountId\":" + GSON.toJson(id) + ",\"displayName\":" + GSON.toJson(name) + "}";
    }

    private static String renameJournal(String oldStem, String newStem, String name,
                                        Map<String, String> originalFiles, boolean completed) throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("accountId", ID);
        record.put("displayName", name);
        record.put("renameFrom", oldStem + ".json");
        record.put("renameTo", newStem + ".json");
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : originalFiles.entrySet()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(file.getValue().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            hashes.put(file.getKey(), hex.toString());
        }
        record.put("fileHashes", hashes);
        record.put("completed", completed);
        return GSON.toJson(record);
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
