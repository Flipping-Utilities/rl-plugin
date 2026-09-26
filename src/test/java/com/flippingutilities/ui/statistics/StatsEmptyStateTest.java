package com.flippingutilities.ui.statistics;

import com.flippingutilities.DataSource;
import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.statistics.items.FlippingItemContainerPanel;
import com.flippingutilities.ui.statistics.recipes.RecipeGroupContainerPanel;
import com.flippingutilities.ui.uiutilities.EmptyStatePanel;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.flippingutilities.utilities.SORT;
import net.runelite.client.ui.components.IconTextField;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class StatsEmptyStateTest {
    @Test
    public void emptyAccountsShowOnboardingAndToolbarActionsHaveButtonSemantics() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rebuild();
            SwingUtilities.invokeAndWait(() -> {
                assertNotNull(find(fixture.panel, JLabel.class, "No trade history yet"));
                assertNotNull(find(fixture.panel, JLabel.class, "No recipe flips yet"));
                assertNull(find(fixture.panel, JButton.class, "Show all time"));
                for (String name : new String[]{"Sort history", "Export to CSV", "Reset statistics for this interval"}) {
                    JButton button = findNamed(fixture.panel, JButton.class, name);
                    assertNotNull(name, button);
                    assertTrue(button.isFocusable());
                }
                for (Class<? extends JPanel> type : new Class[]{FlippingItemContainerPanel.class, RecipeGroupContainerPanel.class}) {
                    JScrollPane pane = find(find(fixture.panel, type, null), JScrollPane.class, null);
                    assertTrue("Scrollbar should retain the theme's usable width", pane.getVerticalScrollBar().getPreferredSize().width > 2);
                }
            });
        }
    }

    @Test
    public void olderHistoryShowsIntervalRecoveryForItemsAndRecipes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.items = Collections.singletonList(oldItem());
            Recipe recipe = new Recipe(Collections.singletonList(new RecipeItem(1, 1)),
                Collections.singletonList(new RecipeItem(2, 1)), "Test recipe");
            RecipeFlip flip = new RecipeFlip(Instant.now().minusSeconds(7200), Collections.emptyMap(), Collections.emptyMap(), 0);
            fixture.recipes = Collections.singletonList(new RecipeFlipGroup(recipe, Collections.singletonList(flip)));
            fixture.rebuild();
            SwingUtilities.invokeAndWait(() -> {
                assertNull(find(fixture.panel, JLabel.class, "No trade history yet"));
                assertNull(find(fixture.panel, JLabel.class, "No recipe flips yet"));
                assertNotNull(find(find(fixture.panel, FlippingItemContainerPanel.class, null), JButton.class, "Show all time"));
                assertNotNull(find(find(fixture.panel, RecipeGroupContainerPanel.class, null), JButton.class, "Show all time"));
                // Keep this test about the filter action, independent of item-image loading in populated cards.
                fixture.items = Collections.emptyList();
                fixture.recipes = Collections.emptyList();
                find(fixture.panel, JButton.class, "Show all time").doClick();
                assertEquals(Instant.EPOCH, fixture.panel.getStartOfInterval());
                assertEquals("All", findNamed(fixture.panel, JComboBox.class, "History interval").getSelectedItem());
            });
            flushEdt();
        }
    }

    @Test
    public void clearingAnEmptySearchRetainsTheSelectedInterval() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                findNamed(fixture.panel, JComboBox.class, "History interval").setSelectedItem("All");
                IconTextField search = find(fixture.panel, IconTextField.class, null);
                search.setText("<unmatched>");
                find(search, JTextField.class, null).postActionEvent();
            });
            fixture.executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            flushEdt();
            flushEdt();
            SwingUtilities.invokeAndWait(() -> {
                assertNotNull(find(fixture.panel, JLabel.class, "No matching trades"));
                assertNotNull(find(fixture.panel, JLabel.class, "No matching recipe flips"));
                assertNull(find(fixture.panel, JButton.class, "Show all time"));
                find(fixture.panel, JButton.class, "Clear search").doClick();
                assertEquals("", find(fixture.panel, IconTextField.class, null).getText());
                assertEquals("All", fixture.panel.getStartOfIntervalName());
            });
            flushEdt();
            SwingUtilities.invokeAndWait(() -> assertNotNull(find(fixture.panel, JLabel.class, "No trade history yet")));
        }
    }

    @Test
    public void emptyStateDescriptionsWrapWithinSidebarWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int width : new int[]{195, 270}) {
                EmptyStatePanel panel = new EmptyStatePanel("No results in this interval",
                    "This account has recipe flips outside the selected interval. Show all time to see them.",
                    "Show all time", () -> {});
                panel.setSize(width, panel.getPreferredSize().height);
                panel.doLayout();
                JTextArea text = find(panel, JTextArea.class, null);
                assertTrue(text.getLineWrap());
                assertTrue(text.getBounds().getMaxX() <= panel.getWidth());
                assertTrue(find(panel, JButton.class, "Show all time").getBounds().getMaxY() <= panel.getHeight());
            }
        });
    }

    static FlippingItem oldItem() {
        FlippingItem item = new FlippingItem(1, "Old item", 100, "Test account");
        OfferEvent offer = new OfferEvent();
        offer.setTime(Instant.now().minusSeconds(7200));
        item.getHistory().getCompressedOfferEvents().add(offer);
        return item;
    }

    private static void flushEdt() throws Exception { SwingUtilities.invokeAndWait(() -> {}); }

    private static <T extends Component> T findNamed(Container parent, Class<T> type, String name) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getAccessibleContext().getAccessibleName())) return type.cast(child);
            if (child instanceof Container) {
                T found = findNamed((Container) child, type, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    static <T extends Component> T find(Container parent, Class<T> type, String text) {
        for (Component child : parent.getComponents()) {
            String actual = child instanceof JLabel ? ((JLabel) child).getText()
                : child instanceof AbstractButton ? ((AbstractButton) child).getText() : null;
            if (type.isInstance(child) && (text == null || text.equals(actual))) return type.cast(child);
            if (child instanceof Container) {
                T found = find((Container) child, type, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    static class Fixture extends FlippingPlugin implements AutoCloseable {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        List<FlippingItem> items = Collections.emptyList();
        List<RecipeFlipGroup> recipes = Collections.emptyList();
        StatsPanel panel;
        Fixture() throws Exception { SwingUtilities.invokeAndWait(() -> panel = new StatsPanel(this)); }
        void rebuild() throws Exception {
            SwingUtilities.invokeAndWait(() -> { panel.rebuildItemsDisplay(items); panel.rebuildRecipesDisplay(recipes); });
            flushEdt();
        }
        @Override public void close() throws Exception { flushEdt(); executor.shutdownNow(); }
        @Override public FlippingConfig getConfig() { return new FlippingConfig() { @Override public DataSource dataSource() { return DataSource.JSON; } }; }
        @Override public ScheduledExecutorService getExecutor() { return executor; }
        @Override public String getAccountCurrentlyViewed() { return ACCOUNT_WIDE; }
        @Override public Instant viewStartOfSessionForCurrentView() { return Instant.now().minusSeconds(3600); }
        @Override public Duration viewAccumulatedTimeForCurrentView() { return Duration.ofHours(1); }
        @Override public Font getFont() { return new Font(Font.DIALOG, Font.PLAIN, 12); }
        @Override public List<FlippingItem> viewItemsForCurrentView() { return items; }
        @Override public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() { return recipes; }
        @Override public List<FlippingItem> sortItems(List<FlippingItem> values, SORT sort, Instant since) { return values; }
        @Override public List<RecipeFlipGroup> sortRecipeFlipGroups(List<RecipeFlipGroup> values, SORT sort, Instant since) { return values; }
    }
}
