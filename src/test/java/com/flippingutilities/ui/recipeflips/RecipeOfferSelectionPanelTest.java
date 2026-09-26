package com.flippingutilities.ui.recipeflips;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.flippingutilities.utilities.SORT;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;

import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecipeOfferSelectionPanelTest {
    @Test
    public void previewAndTypedCoinOffsetPreserveLongMoneyThroughSubmission() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                RecipeOfferSelectionPanel panel = selectedOutput(plugin);
                assertTrue(((SpinnerNumberModel) panel.coinOffset.getModel()).getNumber() instanceof Long);
                panel.coinOffset.setValue(1L);
                assertTrue(panel.finishButton.isEnabled());
                assertEquals("+8,000,000,008 gp", panel.profitNumberLabel.getText());

                JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) panel.coinOffset.getEditor();
                editor.getTextField().setText("3000000003");
                panel.finishButton.doClick();

                assertNotNull(plugin.submitted);
                assertEquals(3_000_000_003L, plugin.submitted.getCoinCost());
                assertEquals(5_000_000_006L, plugin.submitted.getProfit());
                assertEquals("+5,000,000,006 gp", panel.profitNumberLabel.getText());
                assertEquals(1, plugin.submissionCount);
            });
        } finally {
            plugin.executor.shutdownNow();
        }
    }

    @Test
    public void typedCoinOffsetAboveDoublePrecisionKeepsItsLastCoin() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                RecipeOfferSelectionPanel panel = selectedOutput(plugin);
                panel.coinOffset.setValue(1L);
                JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) panel.coinOffset.getEditor();
                editor.getTextField().setText("9007199254740993");
                panel.finishButton.doClick();

                assertNotNull(plugin.submitted);
                assertEquals(9_007_199_254_740_993L, plugin.submitted.getCoinCost());
                assertEquals(8_000_000_009L - 9_007_199_254_740_993L, plugin.submitted.getProfit());
                assertTrue(((SpinnerNumberModel) panel.coinOffset.getModel()).getNumber() instanceof Long);
            });
        } finally {
            plugin.executor.shutdownNow();
        }
    }

    @Test
    public void invalidTypedOffsetDoesNotSubmitThePreviousSpinnerValue() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                RecipeOfferSelectionPanel panel = selectedOutput(plugin);
                panel.coinOffset.setValue(99L);
                JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) panel.coinOffset.getEditor();
                editor.getTextField().setText("not a number");
                panel.finishButton.doClick();

                assertNull(plugin.submitted);
                assertEquals(0, plugin.submissionCount);
                assertEquals(99L, ((Number) panel.coinOffset.getValue()).longValue());
                assertTrue(panel.finishButton.isEnabled());
            });
        } finally {
            plugin.executor.shutdownNow();
        }
    }

    private RecipeOfferSelectionPanel selectedOutput(StubPlugin plugin) {
        plugin.stats = new StatsPanel(plugin) {
            @Override public void rebuildRecipesDisplay(List<RecipeFlipGroup> groups) { }
            @Override public void rebuildItemsDisplay(List<FlippingItem> items) { }
        };
        OfferEvent sale = new OfferEvent();
        sale.setUuid("selected-sale");
        sale.setItemId(4151);
        sale.setPrice(8_000_000_009L);
        sale.setTime(Instant.EPOCH);
        sale.setState(GrandExchangeOfferState.SOLD);
        sale.setCurrentQuantityInTrade(1);
        RecipeOfferSelectionPanel panel = new RecipeOfferSelectionPanel(plugin, sale,
            new Recipe(Collections.emptyList(), Collections.emptyList(), "Recipe"));

        // Seed a completed offer selection without needing RuneLite's item-image service.
        // The real spinner listener and Combine action perform preview and submission.
        panel.recipe = new Recipe(Collections.emptyList(),
            Collections.singletonList(new RecipeItem(4151, 1)), "Recipe");
        panel.selectedOffers.put(4151, Collections.singletonMap(sale.getUuid(), new PartialOffer(sale, 1)));
        panel.idToHeader.put(4151, new RecipeItemHeaderPanel(
            new AsyncBufferedImage(new ClientThread(), 1, 1, BufferedImage.TYPE_INT_ARGB)));
        return panel;
    }

    private static class StubPlugin extends FlippingPlugin {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        private StatsPanel stats;
        private RecipeFlip submitted;
        private int submissionCount;

        @Override public FlippingConfig getConfig() { return new FlippingConfig() { }; }
        @Override public StatsPanel getStatPanel() { return stats; }
        @Override public ScheduledExecutorService getExecutor() { return executor; }
        @Override public String getAccountCurrentlyViewed() { return "Recipe account"; }
        @Override public Instant viewStartOfSessionForCurrentView() { return Instant.EPOCH; }
        @Override public Duration viewAccumulatedTimeForCurrentView() { return Duration.ofHours(1); }
        @Override public Font getFont() { return new Font(Font.DIALOG, Font.PLAIN, 12); }
        @Override public List<FlippingItem> viewItemsForCurrentView() { return Collections.emptyList(); }
        @Override public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() { return Collections.emptyList(); }
        @Override public List<FlippingItem> sortItems(List<FlippingItem> items, SORT sort, Instant since) { return items; }
        @Override public List<RecipeFlipGroup> sortRecipeFlipGroups(List<RecipeFlipGroup> groups, SORT sort, Instant since) { return groups; }
        @Override public Map<Integer, Optional<FlippingItem>> getItemsInRecipe(Recipe recipe) { return Collections.emptyMap(); }
        @Override public Map<Integer, Integer> getTargetValuesForMaxRecipeCount(Recipe recipe,
                Map<Integer, List<PartialOffer>> offers, boolean remaining) {
            return recipe.getItemIdToQuantity();
        }
        @Override public Map<Integer, Integer> getItemIdToMaxRecipesThatCanBeMade(Recipe recipe,
                Map<Integer, List<PartialOffer>> offers, boolean remaining) {
            return Collections.emptyMap();
        }
        @Override public void addRecipeFlip(RecipeFlip flip, Recipe recipe) {
            submitted = flip;
            submissionCount++;
        }
    }
}
