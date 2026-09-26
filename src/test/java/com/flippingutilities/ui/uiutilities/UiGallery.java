package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.laf.RuneLiteLAF;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Standalone developer workbench. It never starts RuneLite or the plugin. */
public final class UiGallery {
    private UiGallery() {}

    public static void main(String[] args) throws Exception {
        // Redirect home before loading any RuneLite classes: several production paths are static.
        if (args.length == 0 || args.length == 2 && "--source".equals(args[0])) {
            RuneLiteSandbox.main(args);
            return;
        }
        List<GalleryFixture> fixtures = GalleryFixtures.all();
        if (args.length == 1 && "--fixtures".equals(args[0])) {
            show(fixtures, fixtures.get(0));
        } else if (args.length == 2 && "--fixture".equals(args[0])) {
            GalleryFixture selected = fixtures.stream().filter(f -> f.id.equals(args[1])).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown fixture: " + args[1]));
            show(fixtures, selected);
        } else if (args.length == 2 && "--render-all".equals(args[0])) {
            renderAll(fixtures, Paths.get(args[1]));
        } else {
            throw new IllegalArgumentException("Usage: UiGallery [--source FOLDER_OR_DB | --fixtures | --fixture ID | --render-all OUTPUT_DIRECTORY]");
        }
    }

    private static void show(List<GalleryFixture> fixtures, GalleryFixture selected) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Interactive gallery needs a display. Use ./gradlew renderUiGallery for PNGs.");
        }
        SwingUtilities.invokeAndWait(() -> {
            RuneLiteLAF.setup();
            Workbench workbench = new Workbench(fixtures, selected);
            JFrame frame = new JFrame("Flipping Utilities component gallery");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setContentPane(workbench);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { workbench.close(); }
            });
            frame.setMinimumSize(new Dimension(720, 560));
            frame.setSize(1020, 860);
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        });
    }

    static void renderAll(List<GalleryFixture> fixtures, Path directory) throws Exception {
        Files.createDirectories(directory);
        StringBuilder index = new StringBuilder("<!doctype html><meta charset='utf-8'><title>Component gallery</title>"
            + "<style>body{font:14px system-ui;background:#202124;color:#eee;margin:24px}section{margin:32px 0}"
            + "figure{display:inline-block;vertical-align:top;margin:8px 24px 8px 0}img{border:1px solid #666}"
            + "code{white-space:pre-wrap}</style><h1>Flipping Utilities component gallery</h1>"
            + "<p>Real Swing components with synthetic data. Logical widths: 225 and 300. Font rasterization varies by platform.</p>");
        for (GalleryFixture fixture : fixtures) {
            index.append("<section><h2>").append(escape(fixture.title)).append("</h2><p>")
                .append(escape(fixture.description)).append("</p><code>").append(escape(fixture.initialState)).append("</code><br>");
            for (int width : new int[]{225, 300}) {
                String name = fixture.id + "-" + width + ".png";
                writePng(render(fixture, width), directory.resolve(name));
                index.append("<figure><figcaption>").append(width).append(" px</figcaption><a href='").append(name)
                    .append("'><img src='").append(name).append("' alt='").append(escape(fixture.title)).append("'></a></figure>");
            }
            index.append("</section>");
        }
        Files.write(directory.resolve("index.html"), index.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Rendered " + fixtures.size() + " fixtures at both widths: " + directory.toAbsolutePath().resolve("index.html"));
    }

    /** Mount, let queued Swing rebuilds finish, capture, and release fixture-owned resources. */
    static BufferedImage render(GalleryFixture fixture, int width) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Batch rendering must be invoked outside Swing");
        GalleryFixture.Mounted[] mounted = new GalleryFixture.Mounted[1];
        BufferedImage[] image = new BufferedImage[1];
        try {
            SwingUtilities.invokeAndWait(() -> { RuneLiteLAF.setup(); mounted[0] = fixture.mount(); });
            // Search updates can enqueue their own rebuild. Drain the initial callbacks first,
            // then capture on the next turn so both stages have run.
            SwingUtilities.invokeAndWait(() -> {});
            SwingUtilities.invokeAndWait(() -> image[0] = capture(mounted[0].component, width, fixture.height));
            return image[0];
        } finally {
            if (mounted[0] != null) SwingUtilities.invokeAndWait(mounted[0]::close);
        }
    }

    static BufferedImage capture(JComponent component, int width, int height) {
        GalleryFixture.requireEdt();
        if (width < 1 || height < 1) throw new IllegalArgumentException("Image dimensions must be positive");
        component.setSize(width, height);
        layoutTree(component);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(ColorScheme.DARK_GRAY_COLOR);
            graphics.fillRect(0, 0, width, height);
            component.printAll(graphics);
            // HTML labels settle their wrapped preferred height after the first paint.
            layoutTree(component);
            graphics.setColor(ColorScheme.DARK_GRAY_COLOR);
            graphics.fillRect(0, 0, width, height);
            component.printAll(graphics);
        } finally { graphics.dispose(); }
        return image;
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) layoutTree((Container) child);
        }
    }

    private static void writePng(BufferedImage image, Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        if (!ImageIO.write(image, "png", path.toFile())) throw new IOException("PNG writer unavailable");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static final class Workbench extends JPanel implements AutoCloseable {
        private final JComboBox<GalleryFixture> selector;
        private final JSpinner width = new JSpinner(new SpinnerNumberModel(300, 160, 1200, 5));
        private final JCheckBox fitWidth = new JCheckBox("Fit available width");
        private final JPanel stage = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 12));
        private final JScrollPane preview = new JScrollPane(stage);
        private final JTextArea details = new JTextArea(5, 30);
        private final JLabel status = new JLabel("Synthetic fixtures only. Tab into the component to inspect keyboard behavior.");
        private GalleryFixture.Mounted mounted;
        private final Path outputDirectory;

        Workbench(List<GalleryFixture> fixtures, GalleryFixture selected) {
            this(fixtures, selected, Paths.get("build", "ui-gallery"));
        }

        Workbench(List<GalleryFixture> fixtures, GalleryFixture selected, Path outputDirectory) {
            super(new BorderLayout(12, 12));
            GalleryFixture.requireEdt();
            this.outputDirectory = outputDirectory;
            setBorder(new EmptyBorder(12, 12, 12, 12));
            selector = new JComboBox<>(fixtures.toArray(new GalleryFixture[0]));
            selector.setSelectedItem(selected);
            selector.getAccessibleContext().setAccessibleName("Component state");
            width.getAccessibleContext().setAccessibleName("Sidebar width in logical pixels");
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            JLabel stateLabel = new JLabel("State"); stateLabel.setLabelFor(selector);
            toolbar.add(stateLabel); toolbar.add(selector);
            JButton reset = new JButton("Reset state"); reset.addActionListener(event -> remount()); toolbar.add(reset);
            JButton export = new JButton("Export PNG"); export.addActionListener(event -> exportCurrent(export)); toolbar.add(export);
            JPanel sizes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            JLabel widthLabel = new JLabel("Width"); widthLabel.setLabelFor(width); sizes.add(widthLabel); sizes.add(width);
            for (int preset : new int[]{225, 300}) {
                JButton button = new JButton(preset + " px");
                button.addActionListener(event -> { fitWidth.setSelected(false); width.setEnabled(true); width.setValue(preset); resizePreview(); });
                sizes.add(button);
            }
            sizes.add(fitWidth);
            JPanel controls = new JPanel(new GridLayout(2, 1, 0, 8)); controls.add(toolbar); controls.add(sizes);
            add(controls, BorderLayout.NORTH);
            stage.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            add(preview, BorderLayout.CENTER);
            details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
            details.getAccessibleContext().setAccessibleName("Fixture description and initial state");
            JPanel footer = new JPanel(new BorderLayout(0, 8)); footer.add(new JScrollPane(details), BorderLayout.CENTER);
            footer.add(status, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
            selector.addActionListener(event -> remount());
            width.addChangeListener(event -> resizePreview());
            fitWidth.addActionListener(event -> { width.setEnabled(!fitWidth.isSelected()); resizePreview(); });
            preview.getViewport().addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent event) { resizePreview(); }
            });
            remount();
        }

        private GalleryFixture selected() { return (GalleryFixture) selector.getSelectedItem(); }

        void remount() {
            close(); stage.removeAll();
            GalleryFixture fixture = selected();
            mounted = fixture.mount(); stage.add(mounted.component);
            details.setText(fixture.id + "\n" + fixture.description + "\n\nInitial state (Reset restores this): " + fixture.initialState);
            details.setCaretPosition(0);
            resizePreview();
        }

        private void resizePreview() {
            if (mounted == null) return;
            int pixels = fitWidth.isSelected() ? Math.max(160, preview.getViewport().getWidth() - 24) : (int) width.getValue();
            mounted.component.setPreferredSize(new Dimension(pixels, selected().height));
            stage.setPreferredSize(new Dimension(pixels + 24, selected().height + 24));
            stage.revalidate(); stage.repaint();
        }

        private void exportCurrent(JButton button) {
            GalleryFixture fixture = selected();
            BufferedImage image = capture(mounted.component, mounted.component.getWidth(), selected().height);
            Path path = outputDirectory.resolve(fixture.id + "-" + image.getWidth() + ".png");
            button.setEnabled(false);
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws IOException { writePng(image, path); return null; }
                @Override protected void done() {
                    button.setEnabled(true);
                    try { get(); status.setText("Saved " + path.toAbsolutePath()); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); status.setText("PNG export interrupted"); }
                    catch (ExecutionException error) { status.setText("PNG export failed: " + error.getCause().getMessage()); }
                }
            }.execute();
        }

        @Override public void close() {
            GalleryFixture.requireEdt();
            if (mounted != null) { mounted.close(); mounted = null; }
        }
    }
}
