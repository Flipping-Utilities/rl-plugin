package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class UiGalleryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void everyRegisteredFixtureExportsAtBothWidths() throws Exception {
        List<GalleryFixture> fixtures = GalleryFixtures.all();
        Set<String> ids = new HashSet<>();
        for (GalleryFixture fixture : fixtures) assertTrue("Unique fixture ID: " + fixture.id, ids.add(fixture.id));
        Path directory = temporary.newFolder("gallery").toPath();
        UiGallery.renderAll(fixtures, directory);
        for (GalleryFixture fixture : fixtures) {
            for (int width : new int[]{225, 300}) {
                BufferedImage image = ImageIO.read(directory.resolve(fixture.id + "-" + width + ".png").toFile());
                assertNotNull(fixture.id, image);
                assertEquals(width, image.getWidth());
                assertEquals(fixture.height, image.getHeight());
            }
        }
        String index = Files.readString(directory.resolve("index.html"));
        assertTrue(index.contains("statistics-session-225.png"));
        assertTrue(index.contains("quick-look-missing-prices-300.png"));
    }

    @Test public void renderingObservesQueuedUpdatesAndClosesTheFixture() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        GalleryFixture fixture = new GalleryFixture("queued", "Queued", "", "", 20, () -> {
            JPanel component = new JPanel();
            component.setBackground(Color.RED);
            SwingUtilities.invokeLater(() -> component.setBackground(Color.GREEN));
            return new GalleryFixture.Mounted(component, closes::incrementAndGet);
        });
        BufferedImage image = UiGallery.render(fixture, 30);
        assertEquals(Color.GREEN.getRGB(), image.getRGB(15, 10));
        assertEquals(1, closes.get());
    }

    @Test public void renderFailureStillClosesTheFixture() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        GalleryFixture fixture = new GalleryFixture("failure", "Failure", "", "", 20, () ->
            new GalleryFixture.Mounted(new JPanel() {
                @Override public void printAll(Graphics graphics) { throw new IllegalStateException("broken fixture"); }
            }, closes::incrementAndGet));
        try { UiGallery.render(fixture, 30); fail("Rendering should fail"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            assertEquals("broken fixture", expected.getCause().getMessage());
        }
        assertEquals(1, closes.get());
    }

    @Test public void resettingAndSwitchingReleaseMountedState() throws Exception {
        AtomicInteger mounts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        GalleryFixture first = new GalleryFixture("first", "First", "", "page=1", 80, () -> {
            mounts.incrementAndGet();
            Paginator paginator = new Paginator(() -> {});
            paginator.updateTotalPages(60);
            return new GalleryFixture.Mounted(paginator, closes::incrementAndGet);
        });
        GalleryFixture second = GalleryFixture.component("second", "Second", "", "", 30, JPanel::new);
        SwingUtilities.invokeAndWait(() -> {
            RuneLiteLAF.setup();
            UiGallery.Workbench workbench = new UiGallery.Workbench(Arrays.asList(first, second), first);
            Paginator original = find(workbench, Paginator.class);
            original.setPageNumber(3);
            button(workbench, "Reset state").doClick();
            Paginator reset = find(workbench, Paginator.class);
            assertNotSame(original, reset);
            assertEquals(1, reset.getPageNumber());
            assertEquals(2, mounts.get());
            assertEquals(1, closes.get());
            find(workbench, JComboBox.class).setSelectedItem(second);
            assertEquals(2, closes.get());
            workbench.close();
            workbench.close();
            assertEquals(2, closes.get());
        });
    }

    @Test public void widthControlsAndExportUseTheCurrentComponentState() throws Exception {
        Path directory = temporary.newFolder("interactive-export").toPath();
        JPanel[] component = new JPanel[1];
        GalleryFixture fixture = GalleryFixture.component("interactive", "Interactive", "", "Blue", 80, () -> {
            component[0] = new JPanel(); component[0].setBackground(Color.BLUE); return component[0];
        });
        UiGallery.Workbench[] workbench = new UiGallery.Workbench[1];
        CountDownLatch exported = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                RuneLiteLAF.setup();
                workbench[0] = new UiGallery.Workbench(Arrays.asList(fixture), fixture, directory);
                UiGallery.capture(workbench[0], 1020, 860);
                button(workbench[0], "225 px").doClick();
                UiGallery.capture(workbench[0], 1020, 860);
                assertEquals(225, component[0].getWidth());
                button(workbench[0], "300 px").doClick();
                UiGallery.capture(workbench[0], 1020, 860);
                assertEquals(300, component[0].getWidth());
                find(workbench[0], JSpinner.class).setValue(350);
                UiGallery.capture(workbench[0], 1020, 860);
                assertEquals(350, component[0].getWidth());
                find(workbench[0], JCheckBox.class).doClick();
                UiGallery.capture(workbench[0], 1020, 860);
                assertTrue(component[0].getWidth() > 350);
                button(workbench[0], "225 px").doClick();
                UiGallery.capture(workbench[0], 1020, 860);
                component[0].setBackground(Color.RED);
                JButton export = button(workbench[0], "Export PNG");
                export.addPropertyChangeListener("enabled", event -> { if (Boolean.TRUE.equals(event.getNewValue())) exported.countDown(); });
                export.doClick();
            });
            assertTrue("Export should finish and restore its button", exported.await(10, TimeUnit.SECONDS));
            BufferedImage image = ImageIO.read(directory.resolve("interactive-225.png").toFile());
            assertEquals(225, image.getWidth());
            assertEquals(80, image.getHeight());
            assertEquals("Export captures current state, not initial fixture state", Color.RED.getRGB(), image.getRGB(100, 40));
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (workbench[0] != null) workbench[0].close(); });
        }
    }

    private static JButton button(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) return (JButton) child;
            if (child instanceof Container) {
                JButton found = button((Container) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T result = find((Container) child, type);
                if (result != null) return result;
            }
        }
        return null;
    }
}
