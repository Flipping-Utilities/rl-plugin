package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.DataSource;
import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.*;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.MasterPanel;
import com.flippingutilities.ui.flipping.SandboxFlippingPanel;
import com.flippingutilities.ui.gehistorytab.GeHistoryTabPanel;
import com.flippingutilities.ui.login.LoginPanel;
import com.flippingutilities.ui.slots.SlotsPanel;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.utilities.WikiDataSource;
import com.flippingutilities.utilities.WikiRequestWrapper;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.AsyncBufferedImage;
import okhttp3.*;

import javax.swing.*;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real plugin models and actions, with only the unavailable RuneLite host replaced. */
final class SandboxPlugin implements AutoCloseable {
    final FlippingPlugin plugin = new FlippingPlugin();
    private final SwingExecutor executor = new SwingExecutor();
    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
        // RecipeHandler needs its two bundled-dataset requests to complete. No request leaves this process.
        boolean dataset = chain.request().url().host().equals("raw.githubusercontent.com");
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(dataset ? 200 : 503).message("Offline sandbox")
            .body(ResponseBody.create(MediaType.parse("application/json"), dataset ? "[]" : "{}"))
            .build();
    }).build();
    private SqliteStorage storage;
    private final Map<Integer, FlippingItem> items = new HashMap<>();
    private final AtomicInteger tick = new AtomicInteger(1);
    private final AtomicInteger catalogVersion = new AtomicInteger();
    private final Map<Integer, AsyncBufferedImage> icons = new HashMap<>();
    private final Map<Integer, Long> iconRetries = new HashMap<>();
    private final Set<Integer> pendingIcons = new HashSet<>();
    private SandboxExchange exchange;
    private SandboxWikiData wiki;
    private javax.swing.Timer wikiTimer;
    private String mappingStatus = "Saved item names";
    private String pricesStatus = "Wiki prices disabled";
    private boolean closed;
    private SandboxPricePanel pricePanel;
    private JDialog priceDialog;

    static SandboxPlugin load(SandboxData data) throws Exception {
        // Fail closed if anything initialized RuneLite before the launcher redirected user.home.
        if (!RuneLite.RUNELITE_DIR.toPath().toAbsolutePath().equals(data.getRuneLiteDirectory().toAbsolutePath())) {
            throw new IllegalStateException("Sandbox must run in a fresh JVM with its temporary user.home");
        }
        SandboxPlugin host = new SandboxPlugin();
        try {
            host.initialize(data);
            return host;
        } catch (Exception | Error error) {
            host.close();
            throw error;
        }
    }

    private void initialize(SandboxData data) throws Exception {
        Path directory = data.getRuneLiteDirectory();
        Path database = directory.resolve("flipping/flipping.db");
        // Snapshot creation only includes the selected backend's database.
        boolean sqlite = Files.isRegularFile(database);
        FlippingConfig config = new FlippingConfig() {
            @Override public DataSource dataSource() { return sqlite ? DataSource.SQLITE : DataSource.JSON; }
            @Override public boolean autoSaveEnabled() { return false; }
        };
        inject("config", config);
        inject("executor", executor);
        inject("storageExecutor", storageExecutor);
        ClientThread clientThread = new ClientThread() {
            @Override public void invoke(Runnable task) { executor.execute(task); }
            @Override public void invokeLater(Runnable task) { executor.execute(task); }
            @Override public void invoke(BooleanSupplier task) { executor.execute(() -> task.getAsBoolean()); }
            @Override public void invokeLater(BooleanSupplier task) { executor.execute(() -> task.getAsBoolean()); }
        };
        inject("clientThread", clientThread);
        Client client = SandboxGameApi.client(() -> plugin.getCurrentlyLoggedInAccount() != null,
            tick::get, () -> exchange == null ? null : exchange.clientOffers(), items);
        inject("client", client);
        plugin.gson = new Gson();
        plugin.tradePersister = new TradePersister(plugin.gson);
        inject("httpClient", http);
        inject("recipeHandler", new RecipeHandler(plugin.gson, http, null));
        java.lang.reflect.Constructor<FlippingItemHandler> itemHandler =
            FlippingItemHandler.class.getDeclaredConstructor(FlippingPlugin.class);
        itemHandler.setAccessible(true);
        inject("flippingItemHandler", itemHandler.newInstance(plugin));
        java.lang.reflect.Constructor<NewOfferEventPipelineHandler> offerHandler =
            NewOfferEventPipelineHandler.class.getDeclaredConstructor(FlippingPlugin.class);
        offerHandler.setAccessible(true);
        inject("newOfferEventPipelineHandler", offerHandler.newInstance(plugin));
        inject("apiAuthHandler", new ApiAuthHandler(plugin));
        inject("apiRequestHandler", new ApiRequestHandler(plugin));
        inject("timeseriesFetcher", new TimeseriesFetcher(http, plugin));
        DataHandler handler = new DataHandler(plugin);
        inject("dataHandler", handler);
        Map<String, AccountData> accounts = new HashMap<>();
        if (sqlite) {
            storage = new SqliteStorage(database.toFile());
            storage.initializeSchema();
            inject("sqliteStorage", storage);
            handler.setSqliteStorage(storage);
            for (String account : storage.listAccounts()) accounts.put(account, storage.loadAccount(account));
        } else {
            accounts = plugin.tradePersister.loadAllAccounts();
        }
        for (String account : accounts.keySet()) {
            // Production backups use account names as filenames. Test databases can contain
            // names that the game would never issue, including paths outside the sandbox.
            if (account == null || account.isEmpty() || account.indexOf('/') >= 0
                || account.indexOf('\\') >= 0 || account.indexOf(':') >= 0 || account.indexOf('\0') >= 0) {
                throw new IOException("Unsafe account name in sandbox data: " + account);
            }
        }
        for (AccountData account : accounts.values()) {
            if (account != null) for (FlippingItem item : account.getTrades()) {
                // Retain metadata only; large histories are loaded and owned by DataHandler below.
                items.put(item.getItemId(), new FlippingItem(item.getItemId(), item.getItemName(), item.getTotalGELimit(), null));
            }
        }
        accounts.clear();
        inject("itemManager", SandboxGameApi.itemManager(client, clientThread, items,
            id -> itemImage(id, clientThread)));
        handler.loadData();
        for (String account : handler.getCurrentAccounts()) {
            if (plugin.tradePersister.isAccountProtected(account)) {
                throw new IOException("Could not load sandbox account: " + account + ". See the preceding error.");
            }
        }
    }

    /** Called on Swing after disk loading has completed. */
    MasterPanel mount() throws Exception {
        GalleryFixture.requireEdt();
        inject("flippingPanel", new SandboxFlippingPanel(plugin, item -> {
            if (wiki != null && !closed) showPriceChart(plugin.getMasterPanel(), item.getItemId(), 0, true);
        }));
        inject("statPanel", new StatsPanel(plugin));
        inject("slotsPanel", new SlotsPanel(plugin, plugin.getItemManager()));
        inject("geHistoryTabPanel", new GeHistoryTabPanel(plugin));
        LoginPanel login = new LoginPanel(plugin);
        MasterPanel panel = new MasterPanel(plugin, plugin.getFlippingPanel(), plugin.getStatPanel(), plugin.getSlotsPanel(), login);
        inject("masterPanel", panel);
        panel.addView(plugin.getGeHistoryTabPanel(), "ge history");
        panel.setupAccSelectorDropdown(plugin.getDataHandler().getCurrentAccounts());
        panel.getAccountSelector().setVisible(true);
        // Saved history predates this preview session; make it visible immediately.
        selectAllHistory(plugin.getStatPanel());
        return panel;
    }

    SandboxExchange exchange() {
        GalleryFixture.requireEdt();
        if (plugin.getMasterPanel() == null) throw new IllegalStateException("Mount the sidebar first");
        if (exchange == null) exchange = new SandboxExchange(plugin, items, tick, catalogVersion);
        return exchange;
    }

    /** Opt in at the launcher boundary; fixture/integration hosts remain network-free by default. */
    void startWikiData(SandboxWikiData data) {
        GalleryFixture.requireEdt();
        if (closed || wiki != null) throw new IllegalStateException("Wiki data already started or host closed");
        wiki = data;
        // Offline placeholders are already complete. Rebuilt panels need fresh images whose
        // onLoaded listeners run when the Wiki image arrives (including resized stats icons).
        icons.clear();
        refreshWiki();
        wikiTimer = new javax.swing.Timer(60_000, event -> refreshPrices());
        wikiTimer.start();
    }

    boolean hasWikiData() { return wiki != null; }
    String wikiStatus() { return mappingStatus + " · " + pricesStatus; }

    void refreshWiki() {
        GalleryFixture.requireEdt();
        if (closed || wiki == null) return;
        mappingStatus = "Loading Wiki items…";
        wiki.mapping().whenComplete((mapping, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (error != null) {
                mappingStatus = "Wiki items unavailable; using saved names";
                return;
            }
            for (SandboxWikiData.Item item : mapping) {
                items.put(item.id, new FlippingItem(item.id, item.name, item.limit, null));
            }
            for (AccountData account : plugin.getDataHandler().viewAllAccountData()) {
                for (FlippingItem item : account.getTrades()) {
                    FlippingItem metadata = items.get(item.getItemId());
                    if (metadata != null) {
                        item.setItemName(metadata.getItemName());
                        item.setTotalGELimit(metadata.getTotalGELimit());
                    }
                }
            }
            catalogVersion.incrementAndGet();
            plugin.setUpdateSinceLastItemAccountWideBuild(true);
            plugin.changeView(plugin.getAccountCurrentlyViewed());
            // Rebuild saved slot labels/icons directly, without replaying trade events or pausing rates.
            if (exchange != null) for (int slot = 0; slot < 8; slot++) {
                OfferEvent offer = exchange.offer(slot);
                if (offer == null) continue;
                OfferEvent empty = new OfferEvent();
                empty.setSlot(slot);
                empty.setTime(Instant.now());
                empty.setState(net.runelite.api.GrandExchangeOfferState.EMPTY);
                plugin.getSlotsPanel().update(empty);
                plugin.getSlotsPanel().update(offer);
            }
            mappingStatus = "Wiki items: " + mapping.size();
        }));
        refreshPrices();
    }

    private void refreshPrices() {
        if (closed || wiki == null) return;
        pricesStatus = "Loading prices…";
        wiki.latest().whenComplete((latest, error) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (error != null) {
                pricesStatus = plugin.getLastWikiRequestWrapper() == null ? "Prices unavailable" : "Prices stale; retry available";
                return;
            }
            WikiRequestWrapper wrapper = new WikiRequestWrapper(latest, WikiDataSource.REGULAR);
            Instant now = Instant.now();
            try {
                inject("lastWikiRequestWrapper", wrapper);
                inject("timeOfLastWikiRequest", now);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            plugin.getFlippingPanel().onWikiRequest(wrapper, now);
            plugin.getSlotsPanel().onWikiRequest(latest);
            pricesStatus = "Wiki prices loaded";
        }));
    }

    void showPriceChart(Component owner, int itemId, int price, boolean buy) {
        GalleryFixture.requireEdt();
        if (closed || wiki == null) return;
        if (priceDialog == null) {
            pricePanel = new SandboxPricePanel(wiki);
            priceDialog = new JDialog(SwingUtilities.getWindowAncestor(owner), "Wiki prices", java.awt.Dialog.ModalityType.MODELESS);
            SandboxPricePanel contents = pricePanel;
            JDialog dialog = priceDialog;
            priceDialog.setName("Sandbox price chart");
            priceDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            priceDialog.setContentPane(pricePanel);
            priceDialog.setSize(720, 700);
            priceDialog.setMinimumSize(new java.awt.Dimension(600, 600));
            priceDialog.setLocationRelativeTo(owner);
            priceDialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) {
                    contents.close();
                    if (priceDialog == dialog) {
                        pricePanel = null;
                        priceDialog = null;
                    }
                }
            });
        }
        pricePanel.showItem(itemId, plugin.getItemManager().getItemComposition(itemId).getName(), price, buy);
        priceDialog.setVisible(true);
        priceDialog.toFront();
    }

    private static void selectAllHistory(java.awt.Container parent) {
        for (java.awt.Component child : parent.getComponents()) {
            if (child instanceof JComboBox) {
                JComboBox<?> box = (JComboBox<?>) child;
                for (int i = 0; i < box.getItemCount(); i++) {
                    if ("All".equals(box.getItemAt(i))) box.setSelectedIndex(i);
                }
            }
            if (child instanceof java.awt.Container) selectAllHistory((java.awt.Container) child);
        }
    }

    private AsyncBufferedImage itemImage(int itemId, ClientThread thread) {
        AsyncBufferedImage icon = icons.computeIfAbsent(itemId, ignored -> placeholder(thread, wiki == null));
        if (wiki != null && !closed && !pendingIcons.contains(itemId)
            && System.currentTimeMillis() >= iconRetries.getOrDefault(itemId, 0L)) {
            pendingIcons.add(itemId);
            wiki.icon(itemId).whenComplete((downloaded, error) -> SwingUtilities.invokeLater(() -> {
                if (closed) return;
                pendingIcons.remove(itemId);
                if (error != null) iconRetries.put(itemId, System.currentTimeMillis() + 60_000);
                else {
                    Graphics2D graphics = icon.createGraphics();
                    try {
                        graphics.setComposite(AlphaComposite.Clear);
                        graphics.fillRect(0, 0, icon.getWidth(), icon.getHeight());
                        graphics.setComposite(AlphaComposite.SrcOver);
                        double scale = Math.min(1, Math.min(36.0 / downloaded.getWidth(), 32.0 / downloaded.getHeight()));
                        int width = (int) (downloaded.getWidth() * scale);
                        int height = (int) (downloaded.getHeight() * scale);
                        graphics.drawImage(downloaded, (36 - width) / 2, (32 - height) / 2, width, height, null);
                    } finally { graphics.dispose(); }
                    iconRetries.put(itemId, Long.MAX_VALUE);
                    icon.loaded();
                }
                if (plugin.getMasterPanel() != null) plugin.getMasterPanel().repaint();
            }));
        }
        return icon;
    }

    private static AsyncBufferedImage placeholder(ClientThread thread, boolean complete) {
        AsyncBufferedImage icon = new AsyncBufferedImage(thread, 36, 32, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = icon.createGraphics();
        graphics.setColor(java.awt.Color.GRAY);
        graphics.drawRect(6, 5, 23, 22);
        graphics.drawString("?", 14, 21);
        graphics.dispose();
        if (complete) icon.loaded();
        return icon;
    }

    private void inject(String name, Object value) throws Exception {
        Field field = FlippingPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    @Override public void close() throws Exception {
        Runnable closeUi = () -> {
            closed = true;
            if (wikiTimer != null) wikiTimer.stop();
            if (pricePanel != null) pricePanel.close();
            if (priceDialog != null) priceDialog.dispose();
            if (exchange != null) exchange.close();
        };
        if (SwingUtilities.isEventDispatchThread()) closeUi.run();
        else SwingUtilities.invokeAndWait(closeUi);
        if (wiki != null) wiki.close();
        executor.shutdownNow();
        storageExecutor.shutdown();
        if (!storageExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IOException("Sandbox storage is still busy; temporary files were retained");
        }
        if (storage != null) storage.close();
        http.dispatcher().cancelAll();
        http.dispatcher().executorService().shutdownNow();
        http.connectionPool().evictAll();
    }

    private static final class SwingExecutor extends ScheduledThreadPoolExecutor {
        SwingExecutor() { super(1, task -> { Thread t = new Thread(task, "sandbox-search"); t.setDaemon(true); return t; }); }
        @Override public void execute(Runnable task) {
            if (SwingUtilities.isEventDispatchThread()) { if (!isShutdown()) task.run(); }
            else SwingUtilities.invokeLater(() -> { if (!isShutdown()) task.run(); });
        }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            return super.schedule(() -> execute(task), delay, unit);
        }
    }
}
