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
import java.io.ByteArrayOutputStream;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import static org.junit.Assert.*;

/** Each host needs a fresh JVM because RuneLite captures user.home in static fields. */
public class SandboxPluginTest {
    private static final String ACCOUNT = "Sandbox player";
    private static final String DELETED_ACCOUNT = "Delete me";
    private static final String OTHER_ACCOUNT = "Other account";
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

    @Test
    public void wikiMetadataIconsAndPriceWidgetsWorkInsideTheDisposableHost() throws Exception {
        Assume.assumeTrue("Set FLIPPING_SANDBOX_UI_TEST=true to exercise Wiki data in the real sidebar",
            "true".equals(System.getenv("FLIPPING_SANDBOX_UI_TEST")));
        Path source = folder.newFolder("wiki-sandbox-source").toPath();
        write(source.resolve(ACCOUNT + ".json"), legacyAccount(ACCOUNT).replace("Abyssal whip", "Saved item name"));
        Map<String, String> before = hashes(source);
        probe(source, DataSource.JSON, "wiki");
        assertEquals("Metadata and prices must not change source saves", before, hashes(source));
    }

    @Test
    public void simulatedExchangeUpdatesRealJsonHistoryAndKeepsAccountsIsolated() throws Exception {
        Assume.assumeTrue("Set FLIPPING_SANDBOX_UI_TEST=true to exercise the exchange with real sidebar panels",
            "true".equals(System.getenv("FLIPPING_SANDBOX_UI_TEST")));
        Path source = folder.newFolder("exchange-json-source").toPath();
        write(source.resolve(ACCOUNT + ".json"), accountWithActiveOffer());
        write(source.resolve(OTHER_ACCOUNT + ".json"), legacyAccount(OTHER_ACCOUNT));
        Map<String, String> before = hashes(source);
        probe(source, DataSource.JSON, "exchange");
        assertEquals("Simulated offers must never persist to source saves", before, hashes(source));
    }

    @Test
    public void simulatedExchangeUpdatesCopiedSqliteHistoryAndContinuesSavedSlots() throws Exception {
        Assume.assumeTrue("Set FLIPPING_SANDBOX_UI_TEST=true to exercise the exchange with real sidebar panels",
            "true".equals(System.getenv("FLIPPING_SANDBOX_UI_TEST")));
        Path source = folder.newFolder("exchange-database-source").toPath();
        Path database = source.resolve("saved-offers.db");
        SqliteStorage storage = new SqliteStorage(database.toFile());
        try {
            storage.initializeSchema();
            OfferEvent partial = new OfferEvent("saved-partial", true, ITEM, 2, 100,
                Instant.parse("2020-01-01T00:01:30Z"), 3, GrandExchangeOfferState.BUYING,
                50, 4, 10, null, false, ACCOUNT, "Abyssal whip", 100, 200);
            storage.recordTrade(ACCOUNT, partial);
            storage.upsertSlot(ACCOUNT, 3, partial, true);
            storage.upsertSlot(ACCOUNT, 4, new OfferEvent("saved-unfilled", true, 554, 0, 0,
                Instant.parse("2020-01-01T00:01:31Z"), 4, GrandExchangeOfferState.BUYING,
                51, 0, 5, null, false, ACCOUNT, "Fire rune", 20, 0), false);
            storage.recordTrade(OTHER_ACCOUNT, new OfferEvent("other-history", true, ITEM, 10, 100,
                Instant.parse("2020-01-01T00:00:00Z"), 0, GrandExchangeOfferState.BOUGHT,
                0, 10, 10, null, false, OTHER_ACCOUNT, "Abyssal whip", 100, 1000));
        } finally {
            storage.close();
        }
        Map<String, String> before = hashes(source);
        probe(database, DataSource.SQLITE, "exchange");
        assertSourceUnchanged(source, before);
    }

    @Test
    public void emptySourceCreatesUsableExchangeAndClosingStopsFurtherFills() throws Exception {
        Assume.assumeTrue("Set FLIPPING_SANDBOX_UI_TEST=true to exercise the exchange with real sidebar panels",
            "true".equals(System.getenv("FLIPPING_SANDBOX_UI_TEST")));
        Path source = folder.newFolder("empty-exchange-source").toPath();
        probe(source, DataSource.JSON, "exchange-empty");
        assertTrue("An empty source must stay empty", hashes(source).isEmpty());
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
        boolean headful = "ui".equals(action) || "wiki".equals(action) || action.startsWith("exchange");
        Process child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=" + !headful, "-cp", testClasspath(), Probe.class.getName(),
            source.toString(), backend.name(), action).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            boolean completed = child.waitFor(45, TimeUnit.SECONDS);
            if (!completed) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            String log = Files.readString(output);
            if ("ui".equals(action) || "wiki".equals(action)) System.out.print(log);
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

    private static String accountWithActiveOffer() {
        String active = "{\"uuid\":\"saved-partial\",\"b\":true,\"id\":4151,\"cQIT\":2,\"p\":100,"
            + "\"t\":1577836890000,\"s\":3,\"st\":\"BUYING\",\"tAA\":50,\"tSFO\":4,\"tQIT\":10}";
        String unfilled = "{\"uuid\":\"saved-unfilled\",\"b\":true,\"id\":554,\"cQIT\":0,\"p\":0,"
            + "\"t\":1577836891000,\"s\":4,\"st\":\"BUYING\",\"tAA\":51,\"tSFO\":0,\"tQIT\":5}";
        return "{\"lastOffers\":{\"3\":" + active + ",\"4\":" + unfilled + "},\"trades\":[{\"id\":4151,\"name\":\"Abyssal whip\","
            + "\"tGL\":70,\"fB\":\"" + ACCOUNT + "\",\"h\":{\"sO\":[" + active + "]}}]}";
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
            try (SandboxData data = "ui".equals(action) ? chooseThroughDialog(source) : SandboxData.copyOf(source)) {
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
                } else if ("wiki".equals(action)) {
                    try (SandboxPlugin host = SandboxPlugin.load(data)) { exerciseWiki(host, data); }
                } else if (action.startsWith("exchange")) {
                    try (SandboxPlugin host = SandboxPlugin.load(data)) {
                        assertEquals(backend, host.plugin.getConfig().dataSource());
                        exerciseExchange(host, "exchange-empty".equals(action));
                    }
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
                        if ("ui".equals(action)) mountAndClick(host, data);
                    }
                }
            }
            assertFalse("Closing the sandbox must remove its temporary home", Files.exists(temporaryHome));
        }

        private static void exerciseWiki(SandboxPlugin host, SandboxData data) throws Exception {
            BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D paint = icon.createGraphics();
            paint.setColor(java.awt.Color.MAGENTA);
            paint.fillRect(0, 0, 16, 16);
            paint.dispose();
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            ImageIO.write(icon, "png", encoded);
            long now = Instant.now().getEpochSecond();
            CountDownLatch releaseIcons = new CountDownLatch(1);
            OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
                String path = chain.request().url().encodedPath();
                if (path.startsWith("/images/")) try {
                    if (!releaseIcons.await(10, TimeUnit.SECONDS)) throw new IOException("Icon test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(error);
                }
                String json;
                if (path.endsWith("mapping")) json = "[{\"id\":4151,\"name\":\"Wiki whip\",\"limit\":70,\"icon\":\"Abyssal whip.png\"},"
                    + "{\"id\":554,\"name\":\"Fire rune\",\"limit\":50000,\"icon\":\"Fire rune.png\"}]";
                else if (path.endsWith("latest")) json = "{\"data\":{\"4151\":{\"high\":150,\"low\":130,\"highTime\":" + now + ",\"lowTime\":" + now + "}}}";
                else json = "{\"data\":[{\"timestamp\":" + (now - 600) + ",\"avgHighPrice\":145,\"avgLowPrice\":125},"
                    + "{\"timestamp\":" + now + ",\"avgHighPrice\":150,\"avgLowPrice\":130}]}";
                ResponseBody body = path.startsWith("/images/")
                    ? ResponseBody.create(MediaType.parse("image/png"), encoded.toByteArray())
                    : ResponseBody.create(MediaType.parse("application/json"), json);
                return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Controlled Wiki").body(body).build();
            }).build();
            SandboxGrandExchangePanel[] game = new SandboxGrandExchangePanel[1];
            JSplitPane[] shell = new JSplitPane[1];
            AtomicReference<Integer> completedIconPixel = new AtomicReference<>();
            AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> asynchronousFailure.compareAndSet(null, error));
            try {
                SwingUtilities.invokeAndWait(() -> {
                    RuneLiteLAF.setup();
                    try { host.mount(); } catch (Exception error) { throw new RuntimeException(error); }
                    host.startWikiData(new SandboxWikiData(http));
                    net.runelite.client.util.AsyncBufferedImage pendingIcon = host.plugin.getItemManager().getImage(ITEM);
                    pendingIcon.onLoaded(() -> completedIconPixel.set(pendingIcon.getRGB(18, 16)));
                    game[0] = new SandboxGrandExchangePanel(host, data);
                    host.exchange().place(0, true, ITEM, 10, 140);
                    shell[0] = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, game[0], host.plugin.getMasterPanel());
                    shell[0].setDividerLocation(665);
                    JFrame frame = new JFrame("Wiki sandbox integration");
                    frame.setContentPane(shell[0]);
                    frame.setSize(1000, 850);
                    frame.setVisible(true);
                });
                SwingUtilities.invokeAndWait(releaseIcons::countDown);
                awaitSwing(() -> host.wikiStatus().contains("Wiki items: 2") && host.wikiStatus().contains("Wiki prices loaded")
                    && Integer.valueOf(java.awt.Color.MAGENTA.getRGB()).equals(completedIconPixel.get())
                    && named(game[0], JLabel.class, "Wiki data status").getText().contains("Wiki prices loaded")
                    && named(game[0], JToggleButton.class, "Slot 1").getText().contains("Buy"));
                SwingUtilities.invokeAndWait(() -> {
                    assertEquals("Wiki whip", host.plugin.getDataHandler().viewAccountData(ACCOUNT).getTrades().get(0).getItemName());
                    assertEquals(50000, host.plugin.getItemManager().getItemStats(554).getGeLimit());
                    JLabel value = named(host.plugin.getFlippingPanel(), JLabel.class, "Wiki price chart 4151");
                    assertEquals("150 gp", value.getText());
                    value.dispatchEvent(new MouseEvent(value, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                        0, 2, 2, 1, false, MouseEvent.BUTTON1));
                });
                awaitSwing(() -> chartDialog() != null && find(chartDialog(), JLabel.class,
                    label -> "Chart status".equals(label.getName()) && label.getText().startsWith("Wiki Insta Buy")) != null);
                Path screenshot = Files.createTempFile("sandbox-wiki-prices-", ".png");
                Path shellScreenshot = Files.createTempFile("sandbox-wiki-items-", ".png");
                SwingUtilities.invokeAndWait(() -> {
                    JDialog chart = chartDialog();
                    assertEquals("Wiki whip (4151)", named(chart, JLabel.class, "Price item").getText());
                    try {
                        ImageIO.write(UiGallery.capture((javax.swing.JComponent) chart.getContentPane(), 720, 700), "png", screenshot.toFile());
                        ImageIO.write(UiGallery.capture(shell[0], 1000, 850), "png", shellScreenshot.toFile());
                    } catch (IOException error) { throw new RuntimeException(error); }
                    named(game[0], JButton.class, "Prices / chart").doClick();
                    assertEquals("Buy offer: 140 gp", named(chart, JLabel.class, "Offer reference").getText());
                });
                System.out.println("Native Wiki chart screenshot: " + screenshot);
                System.out.println("Native Wiki items screenshot: " + shellScreenshot);
                assertTrue(host.plugin.getDataHandler().storeData());
                assertEquals("Wiki whip", host.plugin.tradePersister.loadAccount(ACCOUNT).getTrades().get(0).getItemName());
            } finally {
                releaseIcons.countDown();
                SwingUtilities.invokeAndWait(() -> {
                    if (game[0] != null) game[0].close();
                    for (Window window : Window.getWindows()) window.dispose();
                });
                Thread.setDefaultUncaughtExceptionHandler(previous);
            }
            if (asynchronousFailure.get() != null) throw new AssertionError("Wiki Swing callback failed", asynchronousFailure.get());
        }

        private static JDialog chartDialog() {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && window.isShowing() && "Sandbox price chart".equals(window.getName())) return (JDialog) window;
            }
            return null;
        }

        private static void awaitSwing(java.util.function.BooleanSupplier ready) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
                SwingUtilities.invokeAndWait(() -> done.set(ready.getAsBoolean()));
                if (done.get()) return;
                Thread.sleep(20);
            }
            fail("Timed out waiting for Wiki data in the Swing UI");
        }

        private static void exerciseExchange(SandboxPlugin host, boolean emptySource) throws Exception {
            AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> asynchronousFailure.compareAndSet(null, failure));
            try {
                SwingUtilities.invokeAndWait(() -> {
                    RuneLiteLAF.setup();
                    try { host.mount(); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                    SandboxExchange exchange = host.exchange();
                    if (emptySource) {
                        assertEquals(Collections.singletonList(ACCOUNT), exchange.accounts());
                        assertEquals(ACCOUNT, exchange.account());
                        assertEquals(8, host.plugin.getClient().getGrandExchangeOffers().length);
                        for (int slot = 0; slot < 8; slot++) assertNull(exchange.offer(slot));
                        assertRejected(() -> exchange.place(-1, true, ITEM, 3, 100));
                        assertRejected(() -> exchange.place(8, true, ITEM, 3, 100));
                        assertRejected(() -> exchange.place(0, true, 0, 3, 100));
                        exchange.place(0, true, ITEM, 3, 100);
                        exchange.fill(0, 1);
                        assertEquals(1, history(host.plugin, ACCOUNT).size());
                        assertEquals(1, history(host.plugin, ACCOUNT).get(0).getCurrentQuantityInTrade());
                        exchange.close();
                        assertRejected(() -> exchange.fill(0, 1));
                        assertRejected(exchange::advanceSecond);
                        return;
                    }
                    exchange.selectAccount(ACCOUNT);
                    assertEquals(2, exchange.offer(3).getCurrentQuantityInTrade());
                    assertEquals(100, exchange.fillPrice(3));
                    assertEquals(0, exchange.rate(3));
                    exchange.advanceSecond();
                    assertEquals("Restored offers start paused", 2, exchange.offer(3).getCurrentQuantityInTrade());
                    assertRejected(() -> exchange.collect(3));
                    assertRejected(() -> exchange.place(3, false, ITEM, 10, 150));
                    assertRejected(() -> exchange.setRate(3, -1));
                    assertRejected(() -> exchange.fill(3, -1));
                    assertRejected(() -> exchange.place(0, true, ITEM, Integer.MAX_VALUE, 2));

                    exchange.setRate(3, 3);
                    exchange.advanceSecond();
                    assertEquals(5, exchange.offer(3).getCurrentQuantityInTrade());
                    exchange.setRate(3, 0);
                    exchange.advanceSecond();
                    assertEquals("Zero rate pauses fulfillment", 5, exchange.offer(3).getCurrentQuantityInTrade());
                    exchange.setFillPrice(3, 90);
                    exchange.fill(3, 2);
                    assertEquals(7, exchange.offer(3).getCurrentQuantityInTrade());
                    assertEquals(680, exchange.offer(3).getSpent());
                    exchange.selectAccount(OTHER_ACCOUNT);
                    exchange.selectAccount(ACCOUNT);
                    assertEquals("Account switches retain the chosen execution price", 90, exchange.fillPrice(3));
                    exchange.fill(3, 1);
                    assertEquals("Account switches must not round away cumulative value", 770, exchange.offer(3).getSpent());
                    assertEquals(96, exchange.offer(3).getPreTaxPrice());
                    exchange.cancel(3);
                    assertEquals(GrandExchangeOfferState.CANCELLED_BUY, exchange.offer(3).getState());
                    assertEquals("Partial fills replace earlier snapshots", 1, history(host.plugin, ACCOUNT).size());
                    assertEquals(8, history(host.plugin, ACCOUNT).get(0).getCurrentQuantityInTrade());
                    exchange.collect(3);
                    assertNull(exchange.offer(3));

                    exchange.place(3, false, ITEM, 8, 150);
                    assertEquals(GrandExchangeOfferState.SELLING, exchange.offer(3).getState());
                    assertEquals("Placing an unfilled offer must not create history", 1, history(host.plugin, ACCOUNT).size());
                    exchange.fill(3, 50);
                    assertEquals("A fill is capped at remaining quantity", 8, exchange.offer(3).getCurrentQuantityInTrade());
                    assertEquals(GrandExchangeOfferState.SOLD, exchange.offer(3).getState());
                    assertEquals(2, history(host.plugin, ACCOUNT).size());
                    assertEquals("Real statistics apply sell tax", 408, FlippingItem.getProfit(history(host.plugin, ACCOUNT)));
                    exchange.collect(3);
                    assertNull(exchange.offer(3));

                    assertEquals("Persisted zero-fill offers have no known execution price", 0, exchange.fillPrice(4));
                    assertRejected(() -> exchange.fill(4, 1));
                    assertRejected(() -> exchange.setRate(4, 1));
                    assertEquals(0, exchange.offer(4).getCurrentQuantityInTrade());
                    exchange.setFillPrice(4, 20);
                    exchange.fill(4, 3);
                    assertEquals(3, exchange.offer(4).getCurrentQuantityInTrade());
                    exchange.cancel(4);
                    exchange.collect(4);

                    exchange.selectAccount(OTHER_ACCOUNT);
                    assertNull(exchange.offer(3));
                    assertEquals(10, history(host.plugin, OTHER_ACCOUNT).get(0).getCurrentQuantityInTrade());
                    exchange.place(0, true, ITEM, 5, 120);
                    exchange.setRate(0, 2);
                    exchange.advanceSecond();
                    assertEquals(2, exchange.offer(0).getCurrentQuantityInTrade());
                    exchange.selectAccount(ACCOUNT);
                    assertNull(exchange.offer(0));
                    assertEquals(2, history(host.plugin, ACCOUNT).size());
                    exchange.selectAccount(OTHER_ACCOUNT);
                    assertEquals(0, exchange.rate(0));
                    exchange.advanceSecond();
                    assertEquals("Switching accounts pauses existing offers", 2, exchange.offer(0).getCurrentQuantityInTrade());
                });
                SwingUtilities.invokeAndWait(() -> {});
                assertTrue(host.plugin.getDataHandler().storeData());
                assertEquals(emptySource ? 1 : 2, itemHistory(host.plugin.tradePersister.loadAccount(ACCOUNT)).size());
                if (host.plugin.getSqliteStorage() != null) {
                    CountDownLatch stored = new CountDownLatch(1);
                    host.plugin.submitStorageTask(storage -> stored.countDown());
                    assertTrue("Exchange events must reach the copied database", stored.await(10, TimeUnit.SECONDS));
                    AccountData reloaded = host.plugin.getSqliteStorage().loadAccount(ACCOUNT);
                    assertEquals(2, itemHistory(reloaded).size());
                    assertEquals(408, FlippingItem.getProfit(itemHistory(reloaded)));
                    assertTrue(reloaded.getLastOffers().isEmpty());
                    AccountData other = host.plugin.getSqliteStorage().loadAccount(OTHER_ACCOUNT);
                    assertEquals(2, other.getLastOffers().get(0).getCurrentQuantityInTrade());
                }
            } finally {
                SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); });
                Thread.setDefaultUncaughtExceptionHandler(previous);
            }
            if (asynchronousFailure.get() != null) throw new AssertionError("Queued exchange/sidebar callback failed", asynchronousFailure.get());
        }

        private static java.util.List<OfferEvent> history(FlippingPlugin plugin, String account) {
            return itemHistory(plugin.getDataHandler().viewAccountData(account));
        }

        private static java.util.List<OfferEvent> itemHistory(AccountData account) {
            return account.getTrades().stream().filter(item -> item.getItemId() == ITEM).findFirst().get()
                .getHistory().getCompressedOfferEvents();
        }

        private static void assertRejected(Runnable action) {
            try {
                action.run();
                fail("Invalid exchange action must be rejected");
            } catch (IllegalArgumentException | IllegalStateException expected) {
                // The real offer state must remain unchanged after an invalid request.
            }
        }

        private static SandboxData chooseThroughDialog(Path source) throws Exception {
            AtomicReference<JDialog> openedDialog = new AtomicReference<>();
            CountDownLatch opened = new CountDownLatch(1);
            AWTEventListener listener = event -> {
                if (event.getID() == WindowEvent.WINDOW_OPENED && event.getSource() instanceof JDialog) {
                    JDialog dialog = (JDialog) event.getSource();
                    if ("Open RuneLite sandbox".equals(dialog.getTitle())) {
                        openedDialog.set(dialog);
                        opened.countDown();
                    }
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_EVENT_MASK);
            FutureTask<SandboxData> selection = new FutureTask<>(() -> SandboxSourceChooser.choose(source));
            new Thread(selection, "sandbox-chooser-test").start();
            boolean selected = false;
            try {
                assertTrue("Source chooser must appear before loading RuneLite", opened.await(10, TimeUnit.SECONDS));
                Path screenshot = Files.createTempFile("sandbox-source-chooser-", ".png");
                SwingUtilities.invokeAndWait(() -> {
                    JDialog dialog = openedDialog.get();
                    assertTrue(dialog.isShowing());
                    Container contents = dialog.getContentPane();
                    // Keep this capture plain Swing too: RuneLite must still see the temporary user.home later.
                    BufferedImage image = new BufferedImage(contents.getWidth(), contents.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    try {
                        contents.printAll(graphics);
                    } finally {
                        graphics.dispose();
                    }
                    try {
                        assertTrue(ImageIO.write(image, "png", screenshot.toFile()));
                    } catch (IOException failure) {
                        throw new RuntimeException(failure);
                    }
                    JButton openDefault = find(contents, JButton.class, button -> "Open default".equals(button.getText()));
                    assertNotNull("Default source must be available in the actual chooser", openDefault);
                    openDefault.doClick();
                });
                SandboxData data = selection.get(10, TimeUnit.SECONDS);
                assertNotNull("Opening the default must return a disposable copy", data);
                assertEquals(source.toAbsolutePath().normalize(), data.getSource());
                selected = true;
                System.out.println("Native source chooser screenshot: " + screenshot);
                return data;
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
                if (!selected && openedDialog.get() != null) {
                    SwingUtilities.invokeAndWait(() -> openedDialog.get().dispatchEvent(
                        new WindowEvent(openedDialog.get(), WindowEvent.WINDOW_CLOSING)));
                }
            }
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

        private static void mountAndClick(SandboxPlugin host, SandboxData data) throws Exception {
            AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> asynchronousFailure.compareAndSet(null, failure));
            MasterPanel[] panel = new MasterPanel[1];
            SandboxGrandExchangePanel[] game = new SandboxGrandExchangePanel[1];
            JSplitPane[] layout = new JSplitPane[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    RuneLiteLAF.setup();
                    try {
                        panel[0] = host.mount();
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    game[0] = new SandboxGrandExchangePanel(host, data);
                    layout[0] = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, game[0], panel[0]);
                    layout[0].setDividerLocation(665);
                    JFrame frame = new JFrame("Sandbox integration test");
                    frame.setContentPane(layout[0]);
                    frame.setSize(1000, 850);
                    frame.setVisible(true);
                    panel[0].getAccountSelector().setSelectedItem(ACCOUNT);
                });
                SwingUtilities.invokeAndWait(() -> {});
                SwingUtilities.invokeAndWait(() -> {
                    named(game[0], JComboBox.class, "Offer item").getEditor().setItem("4151");
                    named(game[0], JSpinner.class, "Offer quantity").setValue(4);
                    named(game[0], JSpinner.class, "Offer unit price").setValue(100);
                    named(game[0], JButton.class, "Place offer").doClick();
                    assertEquals("The visible form must place an offer through the plugin", 0,
                        host.exchange().offer(0).getCurrentQuantityInTrade());
                    named(game[0], JSpinner.class, "Fill chunk quantity").setValue(2);
                    named(game[0], JButton.class, "Fill chunk").doClick();
                    assertEquals(2, host.exchange().offer(0).getCurrentQuantityInTrade());
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
                Path exchangeScreenshot = Files.createTempFile("sandbox-grand-exchange-", ".png");
                SwingUtilities.invokeAndWait(() -> {
                    assertNotNull("Statistics must contain the saved item name", find(host.plugin.getStatPanel(),
                        JLabel.class, label -> label.getText() != null && label.getText().contains("Abyssal whip")));
                    BufferedImage image = UiGallery.capture(panel[0], 300, 850);
                    assertEquals(300, image.getWidth());
                    assertEquals(850, image.getHeight());
                    try {
                        assertTrue(ImageIO.write(image, "png", screenshot.toFile()));
                        assertTrue(ImageIO.write(UiGallery.capture(layout[0], 1000, 850), "png", exchangeScreenshot.toFile()));
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    FastTabGroup tabs = find(panel[0], FastTabGroup.class, group -> true);
                    assertTrue(tabs.select(find(tabs, MaterialTab.class, tab -> "flipping".equals(tab.getText()))));
                    assertTrue(host.plugin.getFlippingPanel().isVisible());
                    named(game[0], JButton.class, "Fill remaining").doClick();
                    assertEquals(GrandExchangeOfferState.BOUGHT, host.exchange().offer(0).getState());
                    named(game[0], JButton.class, "Collect offer").doClick();
                    assertNull("Collect button must free the slot", host.exchange().offer(0));
                });
                System.out.println("Native sidebar screenshot: " + screenshot);
                System.out.println("Native Grand Exchange screenshot: " + exchangeScreenshot);
                assertTrue(host.plugin.getDataHandler().storeData());
                assertTrue(host.plugin.tradePersister.loadAccount(ACCOUNT).getTrades().get(0).isFavorite());
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if (game[0] != null) game[0].close();
                    if (panel[0] != null) panel[0].dispose();
                    for (Window window : Window.getWindows()) window.dispose();
                });
                Thread.setDefaultUncaughtExceptionHandler(previous);
            }
            if (asynchronousFailure.get() != null) throw new AssertionError("Queued Swing callback failed", asynchronousFailure.get());
        }

        private static <T extends Component> T named(Container parent, Class<T> type, String name) {
            T component = find(parent, type, candidate -> name.equals(candidate.getName()));
            assertNotNull("Expected visible control: " + name, component);
            return component;
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
