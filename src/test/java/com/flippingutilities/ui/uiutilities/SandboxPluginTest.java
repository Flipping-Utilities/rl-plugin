package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.DataSource;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.MasterPanel;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import static org.junit.Assert.*;

/** Each host needs a fresh JVM because RuneLite captures user.home in static fields. */
public class SandboxPluginTest {
    private static final String ACCOUNT = "Sandbox player";
    private static final String DELETED_ACCOUNT = "Delete me";
    private static final int ITEM = 4151;
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void folderJsonEditsAndAccountDeletionStayInsideTheDisposableCopy() throws Exception {
        Path source = folder.newFolder("runelite").toPath();
        Path flipping = Files.createDirectory(source.resolve("flipping"));
        write(flipping.resolve(ACCOUNT + ".json"), legacyAccount(ACCOUNT));
        write(flipping.resolve(DELETED_ACCOUNT + ".json"), legacyAccount(DELETED_ACCOUNT));
        write(source.resolve("settings.properties"), "flipping.dataSource=JSON\n");
        // A stale database must not override the user's configured JSON backend.
        createDatabase(flipping.resolve("flipping.db"));
        assertIsolatedAndRestorable(source, DataSource.JSON);
    }

    @Test
    public void standaloneDatabaseLoadsWithoutMigrationMarkerAndDiscardsEdits() throws Exception {
        Path database = folder.getRoot().toPath().resolve("saved account #1.db");
        createDatabase(database);
        assertIsolatedAndRestorable(database, DataSource.SQLITE);
    }

    @Test
    public void rejectsRuneLiteInitializedOutsideTheSandboxBeforeLoadingOrWriting() throws Exception {
        Path source = folder.newFolder("wrong-home-source").toPath();
        write(source.resolve(ACCOUNT + ".json"), legacyAccount(ACCOUNT));
        Map<String, String> before = hashes(source);
        probe(source, DataSource.JSON, "reject");
        assertEquals(before, hashes(source));
    }

    @Test
    public void rejectsDatabaseAccountNamesThatCouldEscapeTheTemporaryDirectory() throws Exception {
        Path source = folder.newFolder("unsafe-account-source").toPath();
        Path database = source.resolve("account.db");
        createDatabase(database);
        write(source.resolve("outside.json"), "sentinel: do not overwrite");
        Map<String, String> before = hashes(source);
        probe(database, DataSource.SQLITE, "reject-account");
        assertSourceUnchanged(source, before);
    }

    @Test
    public void nativeSidebarLoadsSavedHistoryAndSupportsFavoriteClicks() throws Exception {
        Assume.assumeTrue("Set FLIPPING_SANDBOX_UI_TEST=true on a desktop to exercise real Swing windows",
            "true".equals(System.getenv("FLIPPING_SANDBOX_UI_TEST")));
        Path source = folder.newFolder("native-sidebar-source").toPath();
        write(source.resolve(ACCOUNT + ".json"), legacyAccount(ACCOUNT));
        Map<String, String> before = hashes(source);
        probe(source, DataSource.JSON, "ui");
        assertEquals(before, hashes(source));
    }

    private void assertIsolatedAndRestorable(Path source, DataSource backend) throws Exception {
        Path sourceDirectory = Files.isDirectory(source) ? source : source.getParent();
        Map<String, String> before = hashes(sourceDirectory);
        probe(source, backend, "mutate");
        assertSourceUnchanged(sourceDirectory, before);
        probe(source, backend, "verify");
        assertSourceUnchanged(sourceDirectory, before);
    }

    private static void assertSourceUnchanged(Path directory, Map<String, String> before) throws Exception {
        Map<String, String> after = hashes(directory);
        for (String added : new ArrayList<>(after.keySet())) {
            if (before.containsKey(added)) continue;
            // SQLite may create coordination files for a read-only connection to a closed WAL database.
            assertTrue("Unexpected source file created: " + added, added.endsWith("-wal") || added.endsWith("-shm"));
            assertTrue("Sidecar must belong to an existing database", before.containsKey(added.substring(0, added.length() - 4)));
            if (added.endsWith("-wal")) assertEquals("No writes may reach the source WAL", 0, Files.size(directory.resolve(added)));
            after.remove(added);
        }
        assertEquals("Sandbox writes must not alter or delete any existing source file", before, after);
    }

    private void probe(Path source, DataSource backend, String action) throws Exception {
        Path output = Files.createTempFile("sandbox-plugin-probe-", ".log");
        Process child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=" + !"ui".equals(action), "-cp", testClasspath(), Probe.class.getName(),
            source.toString(), backend.name(), action).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            boolean completed = child.waitFor(45, TimeUnit.SECONDS);
            if (!completed) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            String log = Files.readString(output);
            if ("ui".equals(action)) System.out.print(log);
            assertTrue("Sandbox probe timed out:\n" + log, completed);
            assertEquals("Sandbox probe failed:\n" + log, 0, child.exitValue());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    private static String testClasspath() throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        Collections.addAll(entries, System.getProperty("java.class.path").split(File.pathSeparator));
        // Gradle workers keep test dependencies in their application classloader, not java.class.path.
        for (ClassLoader loader = SandboxPluginTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) loader).getURLs()) entries.add(Paths.get(url.toURI()).toString());
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void createDatabase(Path database) {
        SqliteStorage storage = new SqliteStorage(database.toFile());
        try {
            storage.initializeSchema();
            OfferEvent offer = new OfferEvent("sandbox-original", true, ITEM, 10, 100,
                Instant.parse("2020-01-01T00:00:00Z"), 0, GrandExchangeOfferState.BOUGHT,
                0, 10, 10, null, false, ACCOUNT, "Abyssal whip", 100, 1000);
            storage.recordTrade(ACCOUNT, offer);
            assertNull(storage.getSetting("migration_completed"));
        } finally {
            storage.close();
        }
    }

    private static String legacyAccount(String account) {
        return "{\"trades\":[{\"id\":4151,\"name\":\"Abyssal whip\",\"tGL\":70,\"fB\":\"" + account
            + "\",\"h\":{\"sO\":[{\"b\":true,\"id\":4151,\"cQIT\":10,\"p\":100,"
            + "\"t\":1577836800000,\"s\":0,\"st\":\"BOUGHT\",\"tAA\":10,\"tQIT\":10}]}}]}";
    }

    private static void write(Path file, String content) throws Exception {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> hashes(Path directory) throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                hashes.put(directory.relativize(file).toString(), Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        }
        return hashes;
    }

    public static final class Probe {
        public static void main(String[] args) {
            try {
                run(Paths.get(args[0]), DataSource.valueOf(args[1]), args[2]);
                System.exit(0);
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
        }

        private static void run(Path source, DataSource backend, String action) throws Exception {
            Path temporaryHome;
            try (SandboxData data = SandboxData.copyOf(source)) {
                temporaryHome = data.getRuneLiteDirectory().getParent();
                if ("reject".equals(action)) {
                    System.setProperty("user.home", temporaryHome.resolve("wrong-home").toString());
                    assertNotEquals(data.getRuneLiteDirectory(), RuneLite.RUNELITE_DIR.toPath());
                }
                System.setProperty("user.home", temporaryHome.toString());
                if ("reject".equals(action)) {
                    try (SandboxPlugin ignored = SandboxPlugin.load(data)) {
                        fail("An already initialized RuneLite directory must fail closed");
                    } catch (IllegalStateException expected) {
                        assertTrue(expected.getMessage().contains("fresh JVM"));
                    }
                } else if ("reject-account".equals(action)) {
                    rejectUnsafeAccounts(data, source);
                } else {
                    try (SandboxPlugin host = SandboxPlugin.load(data)) {
                        FlippingPlugin plugin = host.plugin;
                        assertEquals(backend, plugin.getConfig().dataSource());
                        AccountData account = plugin.getDataHandler().viewAccountData(ACCOUNT);
                        assertNotNull("The real saved account must load", account);
                        assertEquals(1, account.getTrades().size());
                        FlippingItem item = account.getTrades().get(0);
                        assertEquals(ITEM, item.getItemId());
                        assertEquals(backend == DataSource.JSON ? "Abyssal whip" : "Item 4151", item.getItemName());
                        assertEquals(1, item.getHistory().getCompressedOfferEvents().size());
                        assertFalse(item.isFavorite());
                        if (backend == DataSource.SQLITE) {
                            assertNotNull(plugin.getSqliteStorage());
                            assertNull(plugin.getSqliteStorage().getSetting("migration_completed"));
                        } else {
                            assertNull(plugin.getSqliteStorage());
                            assertEquals(70, item.getTotalGELimit());
                        }
                        if ("mutate".equals(action)) mutate(plugin, item, data, backend);
                        if ("ui".equals(action)) mountAndClick(host);
                    }
                }
            }
            assertFalse("Closing the sandbox must remove its temporary home", Files.exists(temporaryHome));
        }

        private static void rejectUnsafeAccounts(SandboxData data, Path source) throws Exception {
            Path pluginDirectory = data.getRuneLiteDirectory().resolve("flipping");
            Path outside = source.getParent().toRealPath().resolve("outside");
            Path sentinel = source.getParent().resolve("outside.json");
            String original = Files.readString(sentinel);
            String escapingName = pluginDirectory.toRealPath().relativize(outside).toString();
            // Only the disposable database is modified; all potential escape targets belong to this test.
            for (String accountName : new String[]{escapingName, "..\\outside", "unsafe:name", "unsafe\0name", ""}) {
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + pluginDirectory.resolve("flipping.db"));
                    PreparedStatement update = connection.prepareStatement("UPDATE accounts SET display_name = ?")) {
                    update.setString(1, accountName);
                    assertEquals(1, update.executeUpdate());
                }
                try (SandboxPlugin ignored = SandboxPlugin.load(data)) {
                    fail("Unsafe account names must be rejected before startup saves: " + accountName);
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("Unsafe account name"));
                }
                assertEquals("Startup must not overwrite files outside the sandbox", original, Files.readString(sentinel));
                assertFalse("Startup must not create escaped backup files", Files.exists(source.getParent().resolve("outside.backup.json")));
                assertFalse("Startup must not create escaped migration backups", Files.exists(source.getParent().resolve("outside.json.pre-migration")));
            }
        }

        private static void mountAndClick(SandboxPlugin host) throws Exception {
            AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> asynchronousFailure.compareAndSet(null, failure));
            MasterPanel[] panel = new MasterPanel[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    RuneLiteLAF.setup();
                    try {
                        panel[0] = host.mount();
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    JFrame frame = new JFrame("Sandbox integration test");
                    frame.setContentPane(panel[0]);
                    frame.setSize(300, 900);
                    frame.setVisible(true);
                    panel[0].getAccountSelector().setSelectedItem(ACCOUNT);
                });
                SwingUtilities.invokeAndWait(() -> {});
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals(ACCOUNT, host.plugin.getAccountCurrentlyViewed());
                    JLabel star = find(host.plugin.getFlippingPanel(), JLabel.class,
                        label -> label.getIcon() == Icons.STAR_OFF_ICON);
                    assertNotNull("Saved history must render a favorite control", star);
                    star.dispatchEvent(new MouseEvent(star, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                        0, 10, 10, 1, false, MouseEvent.BUTTON1));
                    assertTrue(host.plugin.getDataHandler().viewAccountData(ACCOUNT).getTrades().get(0).isFavorite());
                    FastTabGroup tabs = find(panel[0], FastTabGroup.class, group -> true);
                    MaterialTab statistics = find(tabs, MaterialTab.class, tab -> "stats".equals(tab.getText()));
                    assertTrue(tabs.select(statistics));
                    assertTrue(host.plugin.getStatPanel().isVisible());
                });
                SwingUtilities.invokeAndWait(() -> {});
                Path screenshot = Files.createTempFile("sandbox-sidebar-", ".png");
                SwingUtilities.invokeAndWait(() -> {
                    assertNotNull("Statistics must contain the saved item name", find(host.plugin.getStatPanel(),
                        JLabel.class, label -> label.getText() != null && label.getText().contains("Abyssal whip")));
                    BufferedImage image = UiGallery.capture(panel[0], 300, 900);
                    assertEquals(300, image.getWidth());
                    assertEquals(900, image.getHeight());
                    try {
                        assertTrue(ImageIO.write(image, "png", screenshot.toFile()));
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    FastTabGroup tabs = find(panel[0], FastTabGroup.class, group -> true);
                    assertTrue(tabs.select(find(tabs, MaterialTab.class, tab -> "flipping".equals(tab.getText()))));
                    assertTrue(host.plugin.getFlippingPanel().isVisible());
                });
                System.out.println("Native sidebar screenshot: " + screenshot);
                assertTrue(host.plugin.getDataHandler().storeData());
                assertTrue(host.plugin.tradePersister.loadAccount(ACCOUNT).getTrades().get(0).isFavorite());
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if (panel[0] != null) panel[0].dispose();
                    for (Window window : Window.getWindows()) window.dispose();
                });
                Thread.setDefaultUncaughtExceptionHandler(previous);
            }
            if (asynchronousFailure.get() != null) throw new AssertionError("Queued Swing callback failed", asynchronousFailure.get());
        }

        private static <T extends Component> T find(Container parent, Class<T> type, Predicate<T> matches) {
            for (Component child : parent.getComponents()) {
                if (type.isInstance(child) && matches.test(type.cast(child))) return type.cast(child);
                if (child instanceof Container) {
                    T match = find((Container) child, type, matches);
                    if (match != null) return match;
                }
            }
            return null;
        }

        private static void mutate(FlippingPlugin plugin, FlippingItem item, SandboxData data, DataSource backend)
            throws Exception {
            plugin.setFavoriteOnAllAccounts(item, true);
            plugin.deleteOffers(new ArrayList<>(item.getHistory().getCompressedOfferEvents()), item);
            assertTrue(item.isFavorite());
            assertTrue(item.getHistory().getCompressedOfferEvents().isEmpty());
            assertTrue(plugin.getDataHandler().storeData());
            AccountData saved = plugin.tradePersister.loadAccount(ACCOUNT);
            assertTrue(saved.getTrades().get(0).isFavorite());
            assertTrue(saved.getTrades().get(0).getHistory().getCompressedOfferEvents().isEmpty());
            if (backend == DataSource.SQLITE) {
                CountDownLatch stored = new CountDownLatch(1);
                plugin.submitStorageTask(storage -> stored.countDown());
                assertTrue("Queued mutations must finish", stored.await(10, TimeUnit.SECONDS));
                AccountData fromDatabase = plugin.getSqliteStorage().loadAccount(ACCOUNT);
                assertTrue(fromDatabase.getTrades().get(0).isFavorite());
                assertTrue(fromDatabase.getTrades().get(0).getHistory().getCompressedOfferEvents().isEmpty());
            } else {
                Path deletedCopy = data.getRuneLiteDirectory().resolve("flipping/" + DELETED_ACCOUNT + ".json");
                assertTrue(Files.exists(deletedCopy));
                plugin.getDataHandler().deleteAccount(DELETED_ACCOUNT);
                assertFalse("Account deletion must remove only the sandbox copy", Files.exists(deletedCopy));
            }
        }
    }
}
