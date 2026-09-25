package com.flippingutilities.ui.statistics;

import com.flippingutilities.DataSource;
import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.db.FlipRepository;
import com.flippingutilities.db.JsonFlipRepository;
import com.flippingutilities.model.FlippingItem;
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
        @Override public FlipRepository getFlipRepository() { return new JsonFlipRepository(this); }
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
