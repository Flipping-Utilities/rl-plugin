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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RecipeFlipGroupPanelTest {
    @Test public void unknownExecutionCountsKeepKnownProfitAndRoiAndRecoverWhenDefinitionIsFound() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                plugin.stats = new StatsPanel(plugin);
                OfferEvent sale = new OfferEvent();
                sale.setTime(Instant.EPOCH);
                sale.setPrice(100);
                Map<Integer, Map<String, PartialOffer>> outputs = new LinkedHashMap<>();
                outputs.put(10, Collections.singletonMap("a", new PartialOffer(sale, 6)));
                outputs.put(20, Collections.singletonMap("b", new PartialOffer(sale, 15)));
                RecipeFlip flip = new RecipeFlip(Instant.now(), outputs, Collections.emptyMap(), 100);
                RecipeFlipGroup original = new RecipeFlipGroup((String) null);
                original.addRecipeFlip(flip);
                original.synthesizeRecipe(null);
                RecipeFlipGroup group = new RecipeFlipGroup(original.getRecipeKey());
                group.addRecipeFlip(flip);
                RecipeFlipGroupPanel panel = new RecipeFlipGroupPanel(plugin, group);

                assertEquals(2, labelsWithText(panel, "Unknown"));
                assertEquals(1, labelsWithText(panel, "Unknown count"));
                assertEquals(1, labelsWithText(panel, "2000.00%"));
                assertTrue(labelTexts(panel).stream().anyMatch(text -> text.endsWith("gp (x Unknown)")));
                assertTrue(labelTexts(panel).stream().anyMatch(text -> text.endsWith("gp (Unknown gp ea)")));
                assertFalse(labelTexts(panel).stream().anyMatch(text -> text.contains("Mismatched")));

                group.setRecipe(new Recipe(Collections.emptyList(),
                    Arrays.asList(new RecipeItem(10, 2), new RecipeItem(20, 5)), "Known"));
                panel.updateLabels(group.getRecipeFlips());
                assertEquals(0, labelsWithText(panel, "Unknown"));
                assertEquals(1, labelsWithText(panel, "3 Items"));
                assertTrue(labelTexts(panel).stream().anyMatch(text -> text.endsWith("gp (x 3)")));
            });
        } finally { plugin.executor.shutdownNow(); }
    }

    private int labelsWithText(Container container, String text) {
        return (int) labelTexts(container).stream().filter(text::equals).count();
    }

    private List<String> labelTexts(Container container) {
        List<String> texts = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel && ((JLabel) component).getText() != null) {
                texts.add(((JLabel) component).getText());
            }
            if (component instanceof Container) texts.addAll(labelTexts((Container) component));
        }
        return texts;
    }

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
