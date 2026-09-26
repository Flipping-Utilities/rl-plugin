package com.flippingutilities.ui.login;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javax.swing.*;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.laf.RuneLiteLAF;

/** Renders the production form with fake completions; never calls an authentication service. */
public final class TokenLoginFormPreview {
    public static void main(String[] args) throws Exception {
        Path destination = Paths.get(args.length == 0 ? "build/ui-preview/login-form-states.png" : args[0]);
        JPanel[] sheet = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            RuneLiteLAF.setup();
            sheet[0] = new JPanel(new GridLayout(1, 4, 12, 0));
            sheet[0].setBackground(ColorScheme.DARKER_GRAY_COLOR);
            sheet[0].setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            for (String state : new String[]{"Ready", "Empty token", "Logging in", "Request failed"}) {
                CompletableFuture<String> request = new CompletableFuture<>();
                TokenLoginForm form = new TokenLoginForm(() -> true, value -> request);
                JPanel cell = new JPanel(new BorderLayout());
                cell.setBackground(ColorScheme.DARK_GRAY_COLOR);
                cell.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
                cell.add(new JLabel(state), BorderLayout.NORTH);
                cell.add(form, BorderLayout.CENTER);
                sheet[0].add(cell);
                if (!"Ready".equals(state)) {
                    if (!"Empty token".equals(state)) {
                        find(form, JPasswordField.class).setText("example-token");
                    }
                    find(form, JButton.class).doClick();
                    if ("Request failed".equals(state)) request.completeExceptionally(new IllegalStateException());
                }
            }
        });
        // The completed request posts its production error update to the event queue.
        SwingUtilities.invokeAndWait(() -> {
            sheet[0].setSize(860, 270);
            layout(sheet[0]);
        });
        Files.createDirectories(destination.toAbsolutePath().getParent());
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage image = new BufferedImage(860, 270, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            sheet[0].printAll(graphics);
            graphics.dispose();
            try { ImageIO.write(image, "png", destination.toFile()); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        });
    }

    private static <T> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T result = find((Container) child, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container) layout((Container) child);
    }
}
