package com.flippingutilities.ui.uiutilities;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Plain Swing only: RuneLite must not initialize until the temporary home is ready. */
final class SandboxSourceChooser extends JPanel implements AutoCloseable {
    private final Path defaultSource;
    private final JTextField source = new JTextField(38);
    private final JButton browse = new JButton("Browse…");
    private final JButton openDefault = new JButton("Open default");
    private final JButton open = new JButton("Open copy");
    private final JLabel status = new JLabel("Your original saved data will stay unchanged.");
    private final CompletableFuture<SandboxData> selection = new CompletableFuture<>();
    private boolean copying;
    private boolean closed;

    static SandboxData choose(Path defaultSource) throws Exception {
        SandboxSourceChooser[] chooser = new SandboxSourceChooser[1];
        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = new JDialog((Frame) null, "Open RuneLite sandbox", true);
            chooser[0] = new SandboxSourceChooser(defaultSource);
            dialog.setContentPane(chooser[0]);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { chooser[0].close(); }
            });
            chooser[0].selection.whenComplete((data, error) -> SwingUtilities.invokeLater(dialog::dispose));
            dialog.getRootPane().setDefaultButton(chooser[0].open);
            dialog.getRootPane().registerKeyboardAction(event -> chooser[0].close(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.pack();
            dialog.setMinimumSize(dialog.getSize());
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
        });
        return chooser[0].selection.get();
    }

    SandboxSourceChooser(Path defaultSource) {
        super(new BorderLayout(0, 16));
        this.defaultSource = defaultSource;
        setBorder(new EmptyBorder(20, 20, 20, 20));
        JLabel heading = new JLabel("Open a temporary copy of your data");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        add(heading, BorderLayout.NORTH);

        source.setText(defaultSource.toString());
        source.getAccessibleContext().setAccessibleName("Source folder or database");
        JLabel label = new JLabel("Source");
        label.setLabelFor(source);
        JPanel pathRow = new JPanel(new BorderLayout(8, 0));
        pathRow.add(label, BorderLayout.WEST);
        pathRow.add(source, BorderLayout.CENTER);
        pathRow.add(browse, BorderLayout.EAST);
        JLabel drop = new JLabel("Drop one RuneLite folder, flipping folder or SQLite file here", SwingConstants.CENTER);
        drop.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createDashedBorder(Color.GRAY), new EmptyBorder(24, 16, 24, 16)));
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.add(pathRow, BorderLayout.NORTH);
        center.add(drop, BorderLayout.CENTER);
        center.add(status, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout());
        openDefault.setToolTipText(defaultSource.toString());
        actions.add(openDefault, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancel = new JButton("Cancel");
        right.add(cancel);
        right.add(open);
        actions.add(right, BorderLayout.EAST);
        add(actions, BorderLayout.SOUTH);

        browse.addActionListener(event -> {
            JFileChooser picker = new JFileChooser(defaultSource.toFile());
            picker.setDialogTitle("Choose RuneLite data");
            picker.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            picker.setMultiSelectionEnabled(false);
            if (picker.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                source.setText(picker.getSelectedFile().getAbsolutePath());
            }
        });
        openDefault.addActionListener(event -> { source.setText(defaultSource.toString()); openCopy(); });
        open.addActionListener(event -> openCopy());
        source.addActionListener(event -> openCopy());
        cancel.addActionListener(event -> close());

        TransferHandler textTransfer = source.getTransferHandler();
        TransferHandler transfer = new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                if (copying || closed) return false;
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    if (support.isDrop()) {
                        if ((support.getSourceDropActions() & COPY) == 0) return false;
                        support.setDropAction(COPY);
                    }
                    return true;
                }
                return support.getComponent() == source && textTransfer.canImport(support);
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return textTransfer.importData(support);
                try {
                    List<?> files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.size() != 1 || !(files.get(0) instanceof File)) {
                        status.setText("Drop one folder or database file at a time.");
                        return false;
                    }
                    source.setText(((File) files.get(0)).getAbsolutePath());
                    status.setText("Source selected. Choose Open copy to continue.");
                    return true;
                } catch (Exception error) {
                    showError(error);
                    return false;
                }
            }
            @Override public boolean importData(JComponent component, Transferable data) {
                return importData(new TransferSupport(component, data));
            }
            @Override public int getSourceActions(JComponent component) {
                return component == source ? textTransfer.getSourceActions(component) : NONE;
            }
            @Override public void exportToClipboard(JComponent component, Clipboard clipboard, int action) {
                if (component == source) textTransfer.exportToClipboard(component, clipboard, action);
            }
            @Override public void exportAsDrag(JComponent component, InputEvent event, int action) {
                if (component == source) textTransfer.exportAsDrag(component, event, action);
            }
        };
        setTransferHandler(transfer);
        drop.setTransferHandler(transfer);
        source.setTransferHandler(transfer);
    }

    CompletableFuture<SandboxData> selection() { return selection; }

    private void openCopy() {
        if (copying || closed || selection.isDone()) return;
        final Path path;
        try {
            if (source.getText().trim().isEmpty()) throw new IllegalArgumentException("Choose a folder or SQLite file.");
            path = Paths.get(source.getText().trim());
        } catch (RuntimeException error) {
            showError(error);
            return;
        }
        copying = true;
        setControlsEnabled(false);
        status.setText("Creating temporary copy…");
        status.setToolTipText(null);
        // Keep this thread alive on cancellation until it has removed any completed copy.
        new Thread(() -> {
            SandboxData data = null;
            try {
                data = SandboxData.copyOf(path);
                SandboxData ready = data;
                SwingUtilities.invokeAndWait(() -> {
                    if (!closed) selection.complete(ready);
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    if (!closed) showError(error);
                });
            } finally {
                if (data != null && selection.getNow(null) != data) {
                    try { data.close(); }
                    catch (Exception error) { error.printStackTrace(); }
                }
                SwingUtilities.invokeLater(() -> {
                    copying = false;
                    if (closed) selection.complete(null);
                    else setControlsEnabled(true);
                });
            }
        }, "sandbox-source-copy").start();
    }

    private void setControlsEnabled(boolean enabled) {
        source.setEnabled(enabled);
        browse.setEnabled(enabled);
        openDefault.setEnabled(enabled);
        open.setEnabled(enabled);
    }

    private void showError(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        status.setText("Could not open source. Check the path or choose another file or folder.");
        status.setToolTipText(message);
        status.getAccessibleContext().setAccessibleDescription(message);
    }

    @Override public void close() {
        if (selection.isDone()) return;
        closed = true;
        setControlsEnabled(false);
        status.setText(copying ? "Canceling and removing temporary copy…" : "Canceled.");
        if (!copying) selection.complete(null);
    }
}
