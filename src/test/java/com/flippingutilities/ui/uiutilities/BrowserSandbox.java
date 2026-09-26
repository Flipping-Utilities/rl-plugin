package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.db.TradePersister;
import com.flippingutilities.model.AccountData;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.client.ui.laf.RuneLiteLAF;
import okhttp3.*;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.Map;

/** Browser boundaries only; the host, GE, panels and trading pipeline are shared with desktop. */
public final class BrowserSandbox {
    private static SandboxPlugin host;
    private static SandboxData data;
    private static SandboxGrandExchangePanel exchange;
    private static Path temporaryHome;

    private BrowserSandbox() {}

    public static void main(String[] args) throws Throwable {
        String action = "start";
        try {
            start(args);
            action = "close";
            awaitClose();
            closeSession();
            closed();
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            String message = cause.getMessage();
            if (message == null || message.isEmpty()) message = cause.getClass().getSimpleName();
            try { failed("Could not " + action + " the sandbox: " + message); }
            catch (Throwable reportingError) { error.addSuppressed(reportingError); }
            try { closeSession(); }
            catch (Throwable cleanupError) { error.addSuppressed(cleanupError); }
            error.printStackTrace();
            throw error;
        }
    }

    private static void start(String[] args) throws Exception {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        // Cleanup may only own a new browser sandbox directory, never an existing save folder.
        if (!Paths.get("/files").equals(home.getParent())
            || !home.getFileName().toString().matches("flipping-sandbox-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
            || Files.exists(home, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected a fresh browser sandbox directory");
        }
        temporaryHome = home;
        Path runeLite = home.resolve(".runelite");
        Files.createDirectories(runeLite.resolve("flipping"));
        BrowserFonts.install();
        JsonObject manifest = new JsonParser().parse(Files.readString(Paths.get(args[0]))).getAsJsonObject();
        boolean database = manifest.get("database").getAsBoolean();
        progress("Preparing a working copy of the selected files…");
        for (JsonElement entry : manifest.getAsJsonArray("files")) {
            JsonObject file = entry.getAsJsonObject();
            String name = file.get("path").getAsString();
            Path destination = runeLite.resolve(name).normalize();
            if (!destination.startsWith(runeLite) || !(name.equals("settings.properties") || name.equals("flipping/flipping.db.needs-resync")
                || name.matches("flipping/[^/\\\\]+\\.json(?:\\.pre-migration)?"))) {
                throw new IOException("Unexpected import path: " + name);
            }
            // The DB is authoritative. Do not load unrelated JSON accounts alongside its models.
            if (database && !name.equals("settings.properties") && !name.equals("flipping/accountwide.json")
                && !name.equals("flipping/backupcheckpoints.special.json")) continue;
            Files.copy(Paths.get(file.get("staged").getAsString()), destination);
        }
        Map<String, AccountData> importedAccounts = null;
        if (database) {
            importedAccounts = BrowserSqliteImporter.load();
            progress("Preparing imported accounts…");
            TradePersister persister = new TradePersister(new Gson());
            for (Map.Entry<String, AccountData> entry : importedAccounts.entrySet()) {
                persister.writeToFile(entry.getKey(), entry.getValue());
            }
            imported(importedAccounts.size());
        }
        data = SandboxData.prepared(Paths.get(manifest.get("sourceLabel").getAsString()), home, database);
        progress("Loading saved trades into the sandbox…");
        host = SandboxPlugin.load(data, true, importedAccounts);
        progress("Opening the Grand Exchange and plugin panels…");
        SwingUtilities.invokeAndWait(() -> {
            try {
                RuneLiteLAF.setup();
                JPanel sidebar = host.mount();
                host.startWikiData(new SandboxWikiData(browserHttp()));
                exchange = new SandboxGrandExchangePanel(host, data);
                sidebar.setMinimumSize(new Dimension(225, 0));
                JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, exchange, sidebar);
                split.setResizeWeight(1);
                split.setDividerLocation(Math.max(480, Toolkit.getDefaultToolkit().getScreenSize().width - 320));
                JFrame frame = new JFrame("Flipping Utilities sandbox");
                frame.setUndecorated(true);
                frame.setContentPane(split);
                frame.setSize(Toolkit.getDefaultToolkit().getScreenSize());
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                frame.setVisible(true);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        // Panel construction schedules its item rows on Swing's queue. Let those
        // finish before replacing the loading message with Ready.
        SwingUtilities.invokeAndWait(BrowserSandbox::ready);
    }

    /** Await this before an explicit browser reset; abrupt tab closure cannot guarantee cleanup. */
    public static synchronized void closeSession() throws Exception {
        Runnable closeUi = () -> {
            if (exchange != null) {
                exchange.close();
                exchange = null;
            }
            for (Window window : Window.getWindows()) window.dispose();
        };
        if (host != null || exchange != null) {
            if (SwingUtilities.isEventDispatchThread()) closeUi.run();
            else SwingUtilities.invokeAndWait(closeUi);
        }
        if (host != null) {
            // Wait for writes to finish before removing their directory. Retain it if shutdown fails.
            host.close();
            host = null;
        }
        if (data != null) {
            data.close();
            data = null;
        } else if (temporaryHome != null && Files.exists(temporaryHome, LinkOption.NOFOLLOW_LINKS)) {
            // Imports can fail before SandboxData is ready. This path was validated before creation.
            Files.walkFileTree(temporaryHome, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        temporaryHome = null;
    }

    private static OkHttpClient browserHttp() {
        return new OkHttpClient.Builder().addInterceptor(chain -> {
            // SandboxWikiData validates the public Wiki URL before this application interceptor.
            JsonObject result = new JsonParser().parse(fetch(chain.request().url().toString())).getAsJsonObject();
            if (result.has("error")) throw new IOException(result.get("error").getAsString());
            byte[] bytes = Base64.getDecoder().decode(result.get("body").getAsString());
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(result.get("status").getAsInt()).message("Browser fetch")
                .body(ResponseBody.create(MediaType.parse(result.get("type").getAsString()), bytes)).build();
        }).build();
    }

    private static native String fetch(String url) throws IOException;
    private static native void imported(int accounts);
    private static native void ready();
    private static native void awaitClose();
    private static native void closed();
    private static native void failed(String message);
    private static native void progress(String message);
}
