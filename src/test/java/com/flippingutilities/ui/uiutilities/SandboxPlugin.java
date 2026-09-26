package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.DataSource;
import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.*;
import com.flippingutilities.db.SqliteStorage;
import com.flippingutilities.db.TradePersister;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.model.AccountData;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.ui.MasterPanel;
import com.flippingutilities.ui.flipping.FlippingPanel;
import com.flippingutilities.ui.gehistorytab.GeHistoryTabPanel;
import com.flippingutilities.ui.login.LoginPanel;
import com.flippingutilities.ui.slots.SlotsPanel;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.google.gson.Gson;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.util.AsyncBufferedImage;
import okhttp3.*;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        Properties settings = new Properties();
        if (Files.isRegularFile(directory.resolve("settings.properties"))) {
            try (InputStream input = Files.newInputStream(directory.resolve("settings.properties"))) { settings.load(input); }
        }
        Path database = directory.resolve("flipping/flipping.db");
        String configured = settings.getProperty("flipping.dataSource");
        boolean sqlite = data.isDatabaseSource() || Files.isRegularFile(database)
            && !"JSON".equalsIgnoreCase(configured)
            && !Files.exists(directory.resolve("flipping/flipping.db.needs-resync"));
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
        Client client = mock(Client.class);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        when(client.isClientThread()).thenAnswer(call -> SwingUtilities.isEventDispatchThread());
        inject("client", client);
        plugin.gson = new Gson();
        plugin.tradePersister = new TradePersister(plugin.gson);
        inject("httpClient", http);
        inject("recipeHandler", new RecipeHandler(plugin.gson, http, null));
        java.lang.reflect.Constructor<FlippingItemHandler> itemHandler =
            FlippingItemHandler.class.getDeclaredConstructor(FlippingPlugin.class);
        itemHandler.setAccessible(true);
        inject("flippingItemHandler", itemHandler.newInstance(plugin));
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
        Map<Integer, FlippingItem> items = new HashMap<>();
        for (AccountData account : accounts.values()) {
            if (account != null) for (FlippingItem item : account.getTrades()) {
                // Retain metadata only; large histories are loaded and owned by DataHandler below.
                items.put(item.getItemId(), new FlippingItem(item.getItemId(), item.getItemName(), item.getTotalGELimit(), null));
            }
        }
        accounts.clear();
        inject("itemManager", itemManager(items, clientThread));
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
        inject("flippingPanel", new FlippingPanel(plugin));
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

    private static ItemManager itemManager(Map<Integer, FlippingItem> items, ClientThread thread) {
        ItemManager manager = mock(ItemManager.class);
        when(manager.getItemStats(anyInt())).thenAnswer(call -> {
            FlippingItem item = items.get(call.getArgument(0));
            return new ItemStats(false, 0, item == null ? 0 : item.getTotalGELimit(), null);
        });
        when(manager.getItemComposition(anyInt())).thenAnswer(call -> {
            int id = call.getArgument(0);
            FlippingItem item = items.get(id);
            ItemComposition definition = mock(ItemComposition.class);
            when(definition.getId()).thenReturn(id);
            when(definition.getName()).thenReturn(item == null ? "Item " + id : item.getItemName());
            when(definition.getNote()).thenReturn(-1);
            when(definition.getLinkedNoteId()).thenReturn(-1);
            return definition;
        });
        AsyncBufferedImage icon = new AsyncBufferedImage(thread, 36, 32, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = icon.createGraphics();
        graphics.setColor(java.awt.Color.GRAY);
        graphics.drawRect(6, 5, 23, 22);
        graphics.drawString("?", 14, 21);
        graphics.dispose();
        icon.loaded();
        when(manager.getImage(anyInt())).thenReturn(icon);
        when(manager.getImage(anyInt(), anyInt(), anyBoolean())).thenReturn(icon);
        when(manager.canonicalize(anyInt())).thenAnswer(call -> call.getArgument(0));
        return manager;
    }

    private void inject(String name, Object value) throws Exception {
        Field field = FlippingPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    @Override public void close() throws Exception {
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
