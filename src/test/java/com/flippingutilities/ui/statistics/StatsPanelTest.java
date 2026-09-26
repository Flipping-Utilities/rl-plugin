package com.flippingutilities.ui.statistics;

import com.flippingutilities.DataSource;
import com.flippingutilities.FlippingConfig;
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
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.*;

public class StatsPanelTest {
    @Test
    public void sessionFieldsFollowTheSelectedIntervalInJsonMode() throws Exception {
        assertSessionFieldsFollowInterval(DataSource.JSON);
    }

    @Test
    public void sessionFieldsFollowTheSelectedIntervalInSqliteMode() throws Exception {
        assertSessionFieldsFollowInterval(DataSource.SQLITE);
    }

    @Test
    public void missingRecipePricesAreUnknownButRealZeroPricesRemainNumeric() throws Exception {
        StubPlugin plugin = new StubPlugin(DataSource.JSON);
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

    private void assertSessionFieldsFollowInterval(DataSource dataSource) throws Exception {
        StubPlugin plugin = new StubPlugin(dataSource);
        try {
            SwingUtilities.invokeAndWait(() -> {
                StatsPanel panel = new StatsPanel(plugin);
                JComboBox<?> interval = findComponent(panel, JComboBox.class, null);
                assertNotNull("The interval selector should be available", interval);

                for (String selection : new String[]{"Session", "All", "Session"}) {
                    interval.setSelectedItem(selection);
                    panel.updateCumulativeDisplays(Collections.emptyList(), Collections.emptyList());
                    boolean session = selection.equals("Session");
                    assertEquals("Session time should follow the selected interval in " + dataSource,
                        session, findComponent(panel, JLabel.class, "Session Time: ") != null);
                    assertEquals("Hourly profit should follow the selected interval in " + dataSource,
                        session, findComponent(panel, JLabel.class, "Hourly Profit: ") != null);
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
        private final FlippingConfig config;

        private StubPlugin(DataSource dataSource) {
            config = new FlippingConfig() {
                @Override
                public DataSource dataSource() {
                    return dataSource;
                }
            };
        }

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
