package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.laf.RuneLiteLAF;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Paths;
import java.util.List;

/** Browser entry point; shares every fixture and control with the desktop workbench. */
public final class BrowserGallery {
    private BrowserGallery() {}

    public static void main(String[] args) throws Exception {
        List<GalleryFixture> fixtures = GalleryFixtures.all();
        SwingUtilities.invokeAndWait(() -> {
            RuneLiteLAF.setup();
            UiGallery.Workbench workbench = new UiGallery.Workbench(fixtures, fixtures.get(0),
                Paths.get("/files/downloads"));
            JFrame frame = new JFrame("Flipping Utilities component gallery");
            frame.setUndecorated(true);
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setContentPane(workbench);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { workbench.close(); }
            });
            frame.setSize(Toolkit.getDefaultToolkit().getScreenSize());
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setVisible(true);
        });
        ready();
    }

    /** Supplied by the CheerpJ launcher so loading status follows actual Swing initialization. */
    private static native void ready();
}
