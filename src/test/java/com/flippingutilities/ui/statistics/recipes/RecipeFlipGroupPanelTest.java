package com.flippingutilities.ui.statistics.recipes;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.*;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.utilities.*;
import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RecipeFlipGroupPanelTest {
    @Test public void groupTotalsBecomeKnownWhenOriginalOffersAreRecovered() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                plugin.stats = new StatsPanel(plugin);
                Recipe recipe = new Recipe(Collections.emptyList(), Collections.singletonList(new RecipeItem(1, 1)), "Test");
                PartialOffer missing = new PartialOffer("missing", 1);
                RecipeFlip flip = new RecipeFlip(Instant.now(), Collections.singletonMap(1,
                    Collections.singletonMap("missing", missing)), Collections.emptyMap(), 0);
                RecipeFlipGroup group = new RecipeFlipGroup(recipe, Collections.singletonList(flip));
                RecipeFlipGroupPanel panel = new RecipeFlipGroupPanel(plugin, group);
                assertEquals(4, unknownSummaryLabels(panel));
                OfferEvent zero = new OfferEvent();
                zero.setTime(Instant.EPOCH);
                missing.setOffer(zero);
                panel.updateLabels(group.getRecipeFlips());
                assertEquals(0, unknownSummaryLabels(panel));
            });
            SwingUtilities.invokeAndWait(() -> {});
        } finally { plugin.executor.shutdownNow(); }
    }

    private int unknownSummaryLabels(Container container) {
        int count = 0;
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel) {
                JLabel label = (JLabel) component;
                if (label.getToolTipText() != null && label.getToolTipText().contains("recipe financial totals")) {
                    assertTrue(label.getText().startsWith("Unknown"));
                    count++;
                }
            }
            if (component instanceof Container) count += unknownSummaryLabels((Container) component);
        }
        return count;
    }

    private static class StubPlugin extends FlippingPlugin {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        StatsPanel stats;
        @Override public FlippingConfig getConfig() { return new FlippingConfig() {}; }
        @Override public StatsPanel getStatPanel() { return stats; }
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
