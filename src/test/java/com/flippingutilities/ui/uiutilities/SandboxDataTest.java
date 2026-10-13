package com.flippingutilities.ui.uiutilities;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class SandboxDataTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void copiesPluginSavesAndSettingsWithoutUnrelatedRuneLiteFiles() throws Exception {
        Path source = temporaryFolder.newFolder("runelite").toPath();
        Path plugin = Files.createDirectory(source.resolve("flipping"));
        write(plugin.resolve("Alice.json"), "original account");
        write(plugin.resolve("Alice.backup.json"), "backup");
        write(plugin.resolve("Alice.json.pre-migration"), "migration backup");
        write(plugin.resolve("accountwide.json"), "account-wide settings");
        write(plugin.resolve("flipping.db.needs-resync"), "");
        write(source.resolve("settings.properties"), "flipping.useSqliteStorage=false");
        write(source.resolve("session"), "private session");
        Files.createDirectory(source.resolve("cache"));
        write(plugin.resolve("unrelated.txt"), "excluded");
        byte[] originalHash = hash(plugin.resolve("Alice.json"));

        Path sandboxHome;
        SandboxData snapshot = SandboxData.copyOf(source);
        try {
            assertEquals(source.toAbsolutePath(), snapshot.getSource());
            assertFalse(snapshot.isDatabaseSource());
            Path directory = snapshot.getRuneLiteDirectory();
            sandboxHome = directory.getParent();
            assertEquals("original account", read(directory.resolve("flipping/Alice.json")));
            assertEquals("backup", read(directory.resolve("flipping/Alice.backup.json")));
            assertEquals("migration backup", read(directory.resolve("flipping/Alice.json.pre-migration")));
            assertEquals("account-wide settings", read(directory.resolve("flipping/accountwide.json")));
            assertTrue(Files.exists(directory.resolve("flipping/flipping.db.needs-resync")));
            assertEquals("flipping.useSqliteStorage=false", read(directory.resolve("settings.properties")));
            assertFalse(Files.exists(directory.resolve("session")));
            assertFalse(Files.exists(directory.resolve("cache")));
            assertFalse(Files.exists(directory.resolve("flipping/unrelated.txt")));
            write(directory.resolve("flipping/Alice.json"), "edited in sandbox");
            Files.delete(directory.resolve("flipping/Alice.backup.json"));
        } finally {
            snapshot.close();
        }
        snapshot.close();
        assertFalse(Files.exists(sandboxHome));
        assertArrayEquals(originalHash, hash(plugin.resolve("Alice.json")));
        assertEquals("backup", read(plugin.resolve("Alice.backup.json")));
        assertEquals("private session", read(source.resolve("session")));
    }

    @Test
    public void acceptsPluginDirectoriesAndEmptyDirectories() throws Exception {
        Path plugin = temporaryFolder.newFolder("flipping").toPath();
        write(plugin.resolve("Bob.json"), "account");
        try (SandboxData snapshot = SandboxData.copyOf(plugin)) {
            assertEquals("account", read(snapshot.getRuneLiteDirectory().resolve("flipping/Bob.json")));
        }
        Path empty = temporaryFolder.newFolder("empty").toPath();
        try (SandboxData snapshot = SandboxData.copyOf(empty)) {
            assertTrue(Files.isDirectory(snapshot.getRuneLiteDirectory().resolve("flipping")));
        }
    }

    @Test
    public void snapshotsCommittedWalDataAndKeepsSourceUnchanged() throws Exception {
        Path source = temporaryFolder.getRoot().toPath().resolve("account copy #1.sqlite");
        try (Connection writer = open(source); Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE records (value TEXT)");
            statement.execute("INSERT INTO records VALUES ('committed')");
            writer.setAutoCommit(false);
            statement.execute("INSERT INTO records VALUES ('uncommitted')");
            Path wal = Paths.get(source + "-wal");
            assertTrue(Files.size(wal) > 0);
            byte[] databaseHash = hash(source);
            byte[] walHash = hash(wal);

            try (SandboxData snapshot = SandboxData.copyOf(source)) {
                assertTrue(snapshot.isDatabaseSource());
                Path copy = snapshot.getRuneLiteDirectory().resolve("flipping/flipping.db");
                try (Connection sandbox = open(copy); Statement sandboxStatement = sandbox.createStatement()) {
                    assertEquals(1, count(sandbox));
                    try (ResultSet rows = sandboxStatement.executeQuery("SELECT value FROM records")) {
                        assertTrue(rows.next());
                        assertEquals("committed", rows.getString(1));
                    }
                    sandboxStatement.execute("DELETE FROM records");
                    sandboxStatement.execute("INSERT INTO records VALUES ('sandbox only')");
                }
            }
            assertArrayEquals(databaseHash, hash(source));
            assertArrayEquals(walHash, hash(wal));
            assertEquals(2, count(writer));
            writer.rollback();
            assertEquals(1, count(writer));
        }
    }

    @Test
    public void snapshotsDatabaseInsidePluginDirectory() throws Exception {
        Path plugin = temporaryFolder.newFolder("database-plugin").toPath();
        Path database = plugin.resolve("flipping.db");
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records (value TEXT)");
            statement.execute("INSERT INTO records VALUES ('saved')");
        }
        byte[] before = hash(database);
        try (SandboxData snapshot = SandboxData.copyOf(plugin)) {
            assertFalse(snapshot.isDatabaseSource());
            try (Connection connection = open(snapshot.getRuneLiteDirectory().resolve("flipping/flipping.db"))) {
                assertEquals(1, count(connection));
            }
        }
        assertArrayEquals(before, hash(database));
    }

    @Test
    public void ignoresCorruptDatabaseWhenSettingsSelectJson() throws Exception {
        Path source = temporaryFolder.newFolder("json-selected").toPath();
        Path plugin = Files.createDirectory(source.resolve("flipping"));
        Path settings = source.resolve("settings.properties");
        write(settings, "flipping.dataSource=JSON\n");
        byte[] settingsHash = hash(settings);

        assertJsonSnapshotSkipsCorruptDatabase(source, plugin);

        assertFalse(Files.exists(plugin.resolve("flipping.db.needs-resync")));
        assertArrayEquals(settingsHash, hash(settings));
    }

    @Test
    public void ignoresCorruptDatabaseWhenResyncMarkerSelectsJson() throws Exception {
        Path plugin = temporaryFolder.newFolder("json-resync").toPath();
        Path marker = plugin.resolve("flipping.db.needs-resync");
        write(marker, "resync required");
        byte[] markerHash = hash(marker);

        assertJsonSnapshotSkipsCorruptDatabase(plugin, plugin);

        assertFalse(Files.exists(plugin.resolve("settings.properties")));
        assertArrayEquals(markerHash, hash(marker));
    }

    private static void assertJsonSnapshotSkipsCorruptDatabase(Path source, Path plugin) throws Exception {
        Path database = plugin.resolve("flipping.db");
        Path account = plugin.resolve("Alice.json");
        write(database, "Damaged inactive SQLite database.");
        write(account, "{\"trades\":[]}");
        byte[] databaseHash = hash(database);
        byte[] accountHash = hash(account);

        try (SandboxData snapshot = SandboxData.copyOf(source)) {
            Path directory = snapshot.getRuneLiteDirectory();
            assertFalse(snapshot.isDatabaseSource());
            assertFalse(Files.exists(directory.resolve("flipping/flipping.db")));
            assertEquals("{\"trades\":[]}", read(directory.resolve("flipping/Alice.json")));
            if (Files.exists(source.resolve("settings.properties"))) {
                assertEquals(read(source.resolve("settings.properties")), read(directory.resolve("settings.properties")));
            }
            if (Files.exists(plugin.resolve("flipping.db.needs-resync"))) {
                assertEquals("resync required", read(directory.resolve("flipping/flipping.db.needs-resync")));
            }
            write(directory.resolve("flipping/Alice.json"), "{\"edited\":true}");
        }
        assertArrayEquals(databaseHash, hash(database));
        assertArrayEquals(accountHash, hash(account));
        assertFalse(Files.exists(plugin.resolve("flipping.db-wal")));
        assertFalse(Files.exists(plugin.resolve("flipping.db-shm")));
    }

    @Test
    public void reportsMissingSourcesAndCleansUpInvalidDatabaseCopies() throws Exception {
        Path missing = temporaryFolder.getRoot().toPath().resolve("missing");
        try {
            SandboxData.copyOf(missing);
            fail("Expected missing source to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("--source"));
        }
        Path invalid = temporaryFolder.newFile("not-a-database.db").toPath();
        write(invalid, "This is not a SQLite database.");
        Set<Path> before = sandboxDirectories();
        try {
            SandboxData.copyOf(invalid);
            fail("Expected invalid database to fail");
        } catch (java.sql.SQLException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
        assertEquals(before, sandboxDirectories());
        assertEquals("This is not a SQLite database.", read(invalid));
    }

    @Test
    public void rejectsSourceLinksAndNeverFollowsLinksDuringCleanup() throws Exception {
        Path source = temporaryFolder.newFolder("linked-source").toPath();
        Path original = temporaryFolder.newFile("external.json").toPath();
        write(original, "preserved");
        Path link = source.resolve("Alice.json");
        Files.createSymbolicLink(link, original);
        Set<Path> before = sandboxDirectories();
        try {
            SandboxData.copyOf(source);
            fail("Expected relevant symbolic link to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Symbolic links"));
        }
        assertEquals(before, sandboxDirectories());
        Files.delete(link);

        try (SandboxData snapshot = SandboxData.copyOf(source)) {
            Files.createSymbolicLink(snapshot.getRuneLiteDirectory().resolve("outside"), original.getParent());
        }
        assertEquals("preserved", read(original));
    }

    private static Set<Path> sandboxDirectories() throws IOException {
        Set<Path> result = new HashSet<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(Paths.get(System.getProperty("java.io.tmpdir")), "flipping-sandbox-*")) {
            for (Path path : paths) result.add(path);
        }
        return result;
    }

    private static Connection open(Path path) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    private static int count(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT count(*) FROM records")) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static byte[] hash(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }

    private static void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
