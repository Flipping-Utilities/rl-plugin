package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.laf.RuneLiteLAF;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/** Launch only in a fresh process, before any RuneLite class initializes its static paths. */
public final class RuneLiteSandbox {
    private RuneLiteSandbox() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && !(args.length == 2 && "--source".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: RuneLiteSandbox [--source FOLDER_OR_DB]");
        }
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("The RuneLite sandbox needs a display");
        SandboxData data = args.length == 0 ? SandboxSourceChooser.choose(SandboxData.defaultSource())
            : SandboxData.copyOf(Paths.get(args[1]));
        if (data == null) return;
        System.setProperty("user.home", data.getRuneLiteDirectory().getParent().toString());
        SandboxPlugin[] host = new SandboxPlugin[1];
        SandboxGrandExchangePanel[] exchangePanel = new SandboxGrandExchangePanel[1];
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            try {
                SwingUtilities.invokeAndWait(() -> {
                    if (exchangePanel[0] != null) exchangePanel[0].close();
                });
                if (host[0] != null) host[0].close();
                data.close();
            } catch (Exception error) { error.printStackTrace(); }
        };
        Thread shutdown = new Thread(cleanup, "sandbox-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            System.out.println("Sandbox source: " + data.getSource());
            System.out.println("Temporary RuneLite data: " + data.getRuneLiteDirectory());
            host[0] = SandboxPlugin.load(data);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    RuneLiteLAF.setup();
                    JFrame frame = new JFrame("RuneLite sandbox — Flipping Utilities");
                    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    JPanel sidebar = host[0].mount();
                    host[0].startWikiData(new SandboxWikiData());
                    exchangePanel[0] = new SandboxGrandExchangePanel(host[0], data);
                    sidebar.setMinimumSize(new Dimension(225, 0));
                    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, exchangePanel[0], sidebar);
                    split.setResizeWeight(1);
                    split.setDividerLocation(665);
                    frame.setContentPane(split);
                    frame.setMinimumSize(new Dimension(720, 600));
                    frame.setSize(1000, 850);
                    frame.addWindowListener(new WindowAdapter() {
                        @Override public void windowClosed(WindowEvent event) {
                            exchangePanel[0].close();
                            // Production panels create owned and shared-owner dialogs. End them with this host.
                            for (Window window : Window.getWindows()) window.dispose();
                            new Thread(cleanup, "sandbox-close").start();
                        }
                    });
                    frame.setLocationByPlatform(true);
                    frame.setVisible(true);
                } catch (Exception error) { throw new RuntimeException(error); }
            });
        } catch (Exception | Error error) {
            SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); });
            cleanup.run();
            throw error;
        }
    }
}
