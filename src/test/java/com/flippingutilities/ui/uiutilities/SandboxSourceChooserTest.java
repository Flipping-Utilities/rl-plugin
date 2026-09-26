package com.flippingutilities.ui.uiutilities;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/** Drive the same controls and file-drop boundary used by the startup popup. */
public class SandboxSourceChooserTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void defaultButtonCopiesDefaultEvenWhenAnotherPathIsEntered() throws Exception {
        Path source = folder.newFolder("default").toPath();
        Files.writeString(source.resolve("Player.json"), "saved account");
        SandboxSourceChooser chooser = chooser(source);
        SwingUtilities.invokeAndWait(() -> {
            find(chooser, JTextField.class, null).setText("some other path");
            find(chooser, JButton.class, "Open default").doClick();
        });
        try (SandboxData data = chooser.selection().get(10, TimeUnit.SECONDS)) {
            assertEquals(source, data.getSource());
            assertEquals("saved account", Files.readString(data.getRuneLiteDirectory().resolve("flipping/Player.json")));
        }
        assertEquals("saved account", Files.readString(source.resolve("Player.json")));
    }

    @Test
    public void acceptsPastedPathsAndRetriesAfterCopyFailure() throws Exception {
        Path source = folder.newFolder("chosen folder").toPath();
        Path invalid = folder.newFile("invalid.db").toPath();
        Files.writeString(invalid, "not a database");
        SandboxSourceChooser chooser = chooser(source);
        CountDownLatch failureFinished = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            JButton open = find(chooser, JButton.class, "Open copy");
            open.addPropertyChangeListener("enabled", event -> {
                if (Boolean.TRUE.equals(event.getNewValue())) failureFinished.countDown();
            });
            JTextField path = find(chooser, JTextField.class, null);
            path.setText(invalid.toString());
            open.doClick();
        });
        assertTrue(failureFinished.await(10, TimeUnit.SECONDS));
        assertFalse("An invalid source must leave the chooser open", chooser.selection().isDone());
        SwingUtilities.invokeAndWait(() -> {
            assertNotNull(find(chooser, JLabel.class, "Could not open source. Check the path or choose another file or folder."));
            JTextField path = find(chooser, JTextField.class, null);
            path.selectAll();
            assertTrue(path.getTransferHandler().importData(new TransferHandler.TransferSupport(path,
                new StringSelection(source.toString()))));
            path.postActionEvent();
        });
        try (SandboxData data = chooser.selection().get(10, TimeUnit.SECONDS)) {
            assertEquals(source, data.getSource());
        }
    }

    @Test
    public void dropsFolderOrDatabaseAndRejectsMultipleFiles() throws Exception {
        Path source = folder.newFolder("drop folder").toPath();
        Path database = folder.getRoot().toPath().resolve("sample data.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE records (value TEXT)");
            sql.execute("INSERT INTO records VALUES ('copied')");
        }
        SandboxSourceChooser chooser = chooser(source);
        SwingUtilities.invokeAndWait(() -> {
            TransferHandler handler = chooser.getTransferHandler();
            assertFalse(handler.importData(drop(chooser, source.toFile(), database.toFile())));
            assertTrue(handler.importData(drop(chooser, source.toFile())));
            assertEquals(source.toString(), find(chooser, JTextField.class, null).getText());
            JTextField field = find(chooser, JTextField.class, null);
            assertTrue(field.getTransferHandler().importData(drop(field, database.toFile())));
            assertEquals(database.toString(), field.getText());
            find(chooser, JButton.class, "Open copy").doClick();
            assertFalse("No second drop while copying", handler.canImport(drop(chooser, source.toFile())));
        });
        try (SandboxData data = chooser.selection().get(10, TimeUnit.SECONDS);
             Connection connection = DriverManager.getConnection("jdbc:sqlite:" + data.getRuneLiteDirectory().resolve("flipping/flipping.db"));
             Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery("SELECT value FROM records")) {
            assertEquals(database, data.getSource());
            assertTrue(rows.next());
            assertEquals("copied", rows.getString(1));
        }
    }

    @Test
    public void cancelReturnsNoSelectionAndRemovesAnyCopyInProgress() throws Exception {
        Path source = folder.newFolder("cancel").toPath();
        Files.writeString(source.resolve("Player.json"), "original");
        Set<Path> before = snapshots();
        for (boolean startCopy : new boolean[]{false, true}) {
            SandboxSourceChooser chooser = chooser(source);
            SwingUtilities.invokeAndWait(() -> {
                if (startCopy) find(chooser, JButton.class, "Open copy").doClick();
                // This EDT turn cannot hand off a result before cancellation.
                find(chooser, JButton.class, "Cancel").doClick();
            });
            assertNull(chooser.selection().get(10, TimeUnit.SECONDS));
        }
        assertEquals(before, snapshots());
        assertEquals("original", Files.readString(source.resolve("Player.json")));
    }

    private static SandboxSourceChooser chooser(Path source) throws Exception {
        SandboxSourceChooser[] chooser = new SandboxSourceChooser[1];
        SwingUtilities.invokeAndWait(() -> chooser[0] = new SandboxSourceChooser(source));
        return chooser[0];
    }

    private static TransferHandler.TransferSupport drop(JComponent component, File... files) {
        Transferable transferable = new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
            @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.javaFileListFlavor.equals(flavor); }
            @Override public Object getTransferData(DataFlavor flavor) { return Arrays.asList(files); }
        };
        return new TransferHandler.TransferSupport(component, transferable);
    }

    private static Set<Path> snapshots() throws Exception {
        try (Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("flipping-sandbox-")).collect(Collectors.toSet());
        }
    }

    private static <T extends Component> T find(Container parent, Class<T> type, String text) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component) && (text == null
                || component instanceof AbstractButton && text.equals(((AbstractButton) component).getText())
                || component instanceof JLabel && text.equals(((JLabel) component).getText()))) return type.cast(component);
            if (component instanceof Container) {
                T match = find((Container) component, type, text);
                if (match != null) return match;
            }
        }
        return null;
    }
}
