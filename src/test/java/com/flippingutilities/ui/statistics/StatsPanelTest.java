package com.flippingutilities.ui.statistics;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.DataHandler;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.flippingutilities.ui.statistics.recipes.RecipeFlipPanel;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.utilities.SORT;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.*;

public class StatsPanelTest {
    @Test
    public void sessionFieldsFollowTheSelectedInterval() throws Exception {
        assertSessionFieldsFollowInterval();
    }

    @Test
    public void storageStatusShowsPendingErrorsAndRecoveryWithoutACountdown() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                StatsPanel panel = new StatsPanel(plugin);
                assertNotNull(findComponent(panel, JLabel.class, "Storage: "));
                assertNotNull(findComponent(panel, JLabel.class, "Saved"));
                assertNull(findComponent(panel, JLabel.class, "Next auto-save: "));

                plugin.savePending = true;
                panel.updateAutoSaveDisplay();
                assertNotNull(findComponent(panel, JLabel.class, "Saving…"));

                plugin.storageError = "The data folder is read-only. Recent changes have not been saved.";
                panel.updateAutoSaveDisplay();
                JLabel error = findComponent(panel, JLabel.class, "Needs attention");
                assertNotNull(error);
                assertEquals(plugin.storageError, error.getToolTipText());
                assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, error.getForeground());

                plugin.storageError = null;
                plugin.savePending = false;
                panel.updateAutoSaveDisplay();
                JLabel saved = findComponent(panel, JLabel.class, "Saved");
                assertNotNull(saved);
                assertEquals("All changes are saved.", saved.getToolTipText());
                assertEquals(ColorScheme.GRAND_EXCHANGE_ALCH, saved.getForeground());
                assertNull(findComponent(panel, JLabel.class, "Needs attention"));
                assertEquals(1, countLabels(panel, "Storage: "));

                // Collapsing historical statistics must not hide storage failures.
                JLabel profitTitle = findComponent(panel, JLabel.class, "Total Profit: ");
                Container profitPanel = profitTitle.getParent().getParent();
                profitPanel.dispatchEvent(new MouseEvent(profitPanel, MouseEvent.MOUSE_PRESSED,
                    0, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
                plugin.storageError = "Save failed";
                panel.updateAutoSaveDisplay();
                Component visible = findComponent(panel, JLabel.class, "Needs attention");
                assertNotNull(visible);
                while (visible != null && visible != panel) {
                    assertTrue("Storage remains visible when statistics are collapsed: " + visible.getClass().getName(),
                        visible.isVisible());
                    visible = visible.getParent();
                }
            });
        } finally {
            plugin.executor.shutdownNow();
        }
    }

    @Test
    public void missingRecipePricesAreUnknownButRealZeroPricesRemainNumeric() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                Recipe recipe = new Recipe(Collections.singletonList(new RecipeItem(1, 1)),
                    Collections.singletonList(new RecipeItem(2, 1)), "Test recipe");
                PartialOffer missing = new PartialOffer("missing", 1);
                OfferEvent output = new OfferEvent();
                output.setPrice(0);
                output.setTime(Instant.now());
                RecipeFlip flip = new RecipeFlip(Instant.now(),
                    Collections.singletonMap(2, Collections.singletonMap("out", new PartialOffer(output, 1))),
                    Collections.singletonMap(1, Collections.singletonMap("missing", missing)), 0);
                RecipeFlipGroup group = new RecipeFlipGroup(recipe, Collections.singletonList(flip));
                StatsPanel panel = new StatsPanel(plugin);
                panel.updateCumulativeDisplays(Collections.emptyList(), Collections.singletonList(group));
                assertEquals(4, countLabels(panel, "Unknown"));
                assertEquals(2, countLabels(new RecipeFlipPanel(group, flip, recipe, plugin), "Unknown"));
                OfferEvent zeroPrice = new OfferEvent();
                zeroPrice.setBuy(true);
                zeroPrice.setTime(Instant.now());
                zeroPrice.setPrice(0);
                missing.setOffer(zeroPrice);
                panel.updateCumulativeDisplays(Collections.emptyList(), Collections.singletonList(group));
                assertEquals(0, countLabels(panel, "Unknown"));
                assertNotNull(findComponent(panel, JLabel.class, "0 gp"));
                RecipeFlipPanel knownPanel = new RecipeFlipPanel(group, flip, recipe, plugin);
                assertEquals(0, countLabels(knownPanel, "Unknown"));
                assertTrue(countLabels(knownPanel, "0 gp") >= 2);
            });
            SwingUtilities.invokeAndWait(() -> {});
        } finally {
            plugin.executor.shutdownNow();
        }
    }

    private int countLabels(Container parent, String text) {
        int count = 0;
        for (Component component : parent.getComponents()) {
            if (component instanceof JLabel && text.equals(((JLabel) component).getText())) count++;
            if (component instanceof Container) count += countLabels((Container) component, text);
        }
        return count;
    }

    private void assertSessionFieldsFollowInterval() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                StatsPanel panel = new StatsPanel(plugin);
                JComboBox<?> interval = findComponent(panel, JComboBox.class, null);
                assertNotNull("The interval selector should be available", interval);

                for (String selection : new String[]{"Session", "All", "Session"}) {
                    interval.setSelectedItem(selection);
                    panel.updateCumulativeDisplays(Collections.emptyList(), Collections.emptyList());
                    boolean session = selection.equals("Session");
                    assertEquals("Session time should follow the selected interval",
                        session, findComponent(panel, JLabel.class, "Session Time: ") != null);
                    assertEquals("Hourly profit should follow the selected interval",
                        session, findComponent(panel, JLabel.class, "Hourly Profit: ") != null);
                    assertNotNull("Storage should be visible for every interval",
                        findComponent(panel, JLabel.class, "Storage: "));
                }
            });
            SwingUtilities.invokeAndWait(() -> {});
        } finally {
            plugin.executor.shutdownNow();
        }
    }

    private <T extends Component> T findComponent(Container parent, Class<T> type, String label) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)
                && (label == null || label.equals(((JLabel) component).getText()))) {
                return type.cast(component);
            }
            if (component instanceof Container) {
                T found = findComponent((Container) component, type, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static class StubPlugin extends FlippingPlugin {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        private final FlippingConfig config = new FlippingConfig() {};
        private String storageError;
        private boolean savePending;
        private final DataHandler data = new DataHandler(this) {
            @Override public String getStorageError() { return StubPlugin.this.storageError; }
            @Override public boolean isSavePending() { return StubPlugin.this.savePending; }
        };

        @Override public DataHandler getDataHandler() { return data; }
        @Override public FlippingConfig getConfig() { return config; }
        @Override public ScheduledExecutorService getExecutor() { return executor; }
        @Override public String getAccountCurrentlyViewed() { return ACCOUNT_WIDE; }
        @Override public Instant viewStartOfSessionForCurrentView() { return Instant.EPOCH; }
        @Override public Duration viewAccumulatedTimeForCurrentView() { return Duration.ofHours(1); }
        @Override public Font getFont() { return new Font(Font.DIALOG, Font.PLAIN, 12); }
        @Override public List<FlippingItem> viewItemsForCurrentView() { return Collections.emptyList(); }
        @Override public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() { return Collections.emptyList(); }
        @Override public List<FlippingItem> sortItems(List<FlippingItem> items, SORT sort, Instant since) { return items; }
        @Override public List<RecipeFlipGroup> sortRecipeFlipGroups(List<RecipeFlipGroup> groups, SORT sort, Instant since) { return groups; }
    }
}
