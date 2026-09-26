package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.utilities.SORT;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Real sidebar hierarchy with a controlled in-memory plugin boundary, never plugin startup. */
final class StatisticsGalleryFixtures {
    static List<GalleryFixture> all() {
        return Arrays.asList(stats("session", "Empty session", false, false),
            stats("all-time", "Empty all-time history", true, false),
            stats("search", "No search results", true, true));
    }

    private static GalleryFixture stats(String id, String title, boolean allTime, boolean search) {
        return new GalleryFixture("statistics-" + id, "Statistics / " + title,
            "Real Statistics panel with synthetic empty history. Try intervals, search, sorting and tabs. "
                + "File export, deletion and recipe management are disabled in this preview.",
            "account=Gallery fixture, history=[], recipes=[], elapsed=1 hour, interval=" + (allTime ? "All" : "Session")
                + (search ? ", query=dragon claws" : ""), 640, () -> mount(allTime, search));
    }

    private static GalleryFixture.Mounted mount(boolean allTime, boolean search) {
        PreviewPlugin plugin = new PreviewPlugin();
        try {
            StatsPanel panel = new StatsPanel(plugin) {
                @Override public void rebuildItemsDisplay(List<FlippingItem> items) {
                    super.rebuildItemsDisplay(items);
                    SwingUtilities.invokeLater(() -> disableExternalActions(this));
                }
                @Override public void rebuildRecipesDisplay(List<RecipeFlipGroup> groups) {
                    super.rebuildRecipesDisplay(groups);
                    SwingUtilities.invokeLater(() -> disableExternalActions(this));
                }
            };
            plugin.panel = panel;
            if (allTime) find(panel, JComboBox.class).setSelectedItem("All");
            panel.rebuildItemsDisplay(Collections.emptyList());
            panel.rebuildRecipesDisplay(Collections.emptyList());
            if (search) {
                IconTextField field = find(panel, IconTextField.class);
                field.setText("dragon claws");
                find(field, JTextField.class).postActionEvent();
            }
            // Rebuilds are queued by the real panel; disable their external actions afterward too.
            SwingUtilities.invokeLater(() -> disableExternalActions(panel));
            disableExternalActions(panel);
            return new GalleryFixture.Mounted(panel, plugin.executor::shutdownNow);
        } catch (RuntimeException | Error error) {
            plugin.executor.shutdownNow();
            throw error;
        }
    }

    private static void disableExternalActions(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JComponent) {
                JComponent component = (JComponent) child;
                String tooltip = component.getToolTipText();
                boolean external = "Export to CSV".equals(tooltip) || "Reset Statistics".equals(tooltip)
                    || "Reset statistics for this interval".equals(tooltip)
                    || child instanceof JButton && "Manage Custom Recipes".equals(((JButton) child).getText());
                if (external) {
                    for (java.awt.event.MouseListener listener : component.getMouseListeners()) component.removeMouseListener(listener);
                    if (component instanceof AbstractButton) {
                        for (java.awt.event.ActionListener listener : ((AbstractButton) component).getActionListeners()) {
                            ((AbstractButton) component).removeActionListener(listener);
                        }
                    }
                    component.setEnabled(false);
                    component.setToolTipText("Unavailable in the component gallery");
                }
            }
            if (child instanceof Container) disableExternalActions((Container) child);
        }
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container) {
                T match = find((Container) child, type);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static final class PreviewPlugin extends FlippingPlugin {
        private final PreviewExecutor executor = new PreviewExecutor();
        private final FlippingConfig config = new FlippingConfig() {
            @Override public boolean autoSaveEnabled() { return false; }
        };
        private StatsPanel panel;
        @Override public FlippingConfig getConfig() { return config; }
        @Override public PreviewExecutor getExecutor() { return executor; }
        @Override public StatsPanel getStatPanel() { return panel; }
        @Override public String getAccountCurrentlyViewed() { return "Gallery fixture"; }
        @Override public Instant viewStartOfSessionForCurrentView() { return Instant.EPOCH; }
        @Override public Duration viewAccumulatedTimeForCurrentView() { return Duration.ofHours(1); }
        @Override public Font getFont() { return FontManager.getRunescapeFont(); }
        @Override public List<FlippingItem> viewItemsForCurrentView() { return Collections.emptyList(); }
        @Override public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() { return Collections.emptyList(); }
        @Override public List<FlippingItem> sortItems(List<FlippingItem> items, SORT sort, Instant since) { return items; }
        @Override public List<RecipeFlipGroup> sortRecipeFlipGroups(List<RecipeFlipGroup> groups, SORT sort, Instant since) { return groups; }
    }

    /** Search timers are fixture-owned; callbacks are marshalled back to Swing and ignored after unmount. */
    private static final class PreviewExecutor extends ScheduledThreadPoolExecutor {
        PreviewExecutor() {
            super(1, task -> { Thread thread = new Thread(task, "ui-gallery-search"); thread.setDaemon(true); return thread; });
            setRemoveOnCancelPolicy(true);
        }
        @Override public void execute(Runnable task) {
            if (SwingUtilities.isEventDispatchThread()) { if (!isShutdown()) task.run(); }
            else SwingUtilities.invokeLater(() -> { if (!isShutdown()) task.run(); });
        }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            return super.schedule(() -> SwingUtilities.invokeLater(() -> { if (!isShutdown()) task.run(); }), delay, unit);
        }
    }
}
