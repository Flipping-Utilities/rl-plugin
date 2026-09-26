package com.flippingutilities.ui.statistics.items;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.PartialOffer;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.utilities.SORT;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FlippingItemPanelTest {
    @Test
    public void mixedEmptyProfitableAndLosingHistoriesKeepTheirAverageLabelsAfterWarmup() throws Exception {
        StubPlugin plugin = new StubPlugin();
        try {
            SwingUtilities.invokeAndWait(() -> {
                plugin.stats = new StatsPanel(plugin);
                FlippingItemPanel panel = new FlippingItemPanel(plugin,
                    new FlippingItem(4151, "Synthetic item", 70, "Synthetic account"));
                JLabel average = valueLabel(panel, "Avg. Profit ea: ");
                JLabel total = valueLabel(panel, "Total Profit: ");
                JLabel quantity = valueLabel(panel, "Quantity Flipped: ");
                JLabel averageBuy = valueLabel(panel, "Avg. Buy Price: ");
                JLabel averageSell = valueLabel(panel, "Avg. Sell Price: ");
                assertNotNull(average);
                assertNotNull(total);
                assertNotNull(quantity);
                assertNotNull(averageBuy);
                assertNotNull(averageSell);

                List<OfferEvent> buyOnly = Collections.singletonList(offer(true, 10, 1000));
                List<OfferEvent> sellOnly = Collections.singletonList(offer(false, 10, 980));
                List<OfferEvent> profit = Arrays.asList(offer(true, 100, 1000), offer(false, 100, 1078));
                List<OfferEvent> loss = Arrays.asList(offer(true, 10, 5000), offer(false, 10, 980));
                List<OfferEvent> breakeven = Arrays.asList(offer(true, 10, 98), offer(false, 10, 98));
                List<List<OfferEvent>> histories = Arrays.asList(buyOnly, profit, loss, sellOnly, breakeven, profit);
                String[] averages = {"0 gp/ea", "78 gp/ea", "-4,020 gp/ea", "0 gp/ea", "0 gp/ea", "0 gp/ea"};
                String[] totals = {"0 gp", "7,800 gp", "-40,200 gp", "0 gp", "0 gp", "0 gp"};
                String[] quantities = {"0 Items", "100 Items", "10 Items", "0 Items", "10 Items", "0 Items"};
                String[] buyPrices = {"1,000 gp", "1,000 gp", "5,000 gp", "0 gp", "98 gp", "1,000 gp"};
                String[] sellPrices = {"0 gp", "1,078 gp", "980 gp", "980 gp", "98 gp", "1,078 gp"};

                // Mixed paths matter: repeated empty rows alone did not expose the browser JVM failure.
                for (int update = 0; update < 1000; update++) {
                    int state = update % histories.size();
                    List<OfferEvent> offers = histories.get(state);
                    // Fully recipe-consumed history has no remaining matches despite its original offers.
                    List<OfferEvent> adjusted = state == 5 ? Collections.emptyList() : offers;
                    panel.updateLabels(offers, adjusted);
                    assertEquals("Average after update " + update, averages[state], average.getText());
                    assertEquals(averages[state], average.getToolTipText());
                    assertEquals(totals[state], total.getText());
                    assertEquals(quantities[state], quantity.getText());
                    assertEquals(buyPrices[state], averageBuy.getText());
                    assertEquals(sellPrices[state], averageSell.getText());
                }
            });
            SwingUtilities.invokeAndWait(() -> {});
        } finally {
            plugin.executor.shutdownNow();
        }
    }

    private static OfferEvent offer(boolean buy, int quantity, int price) {
        OfferEvent offer = new OfferEvent();
        offer.setBuy(buy);
        offer.setItemId(4151);
        offer.setCurrentQuantityInTrade(quantity);
        offer.setTotalQuantityInTrade(quantity);
        offer.setPrice(price);
        offer.setTime(Instant.parse("2020-01-01T00:00:00Z")); // Before GE tax; prices are exact fixture inputs.
        offer.setState(buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD);
        return offer;
    }

    private static JLabel valueLabel(Container parent, String description) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel && description.equals(((JLabel) child).getText())
                && parent instanceof JPanel && parent.getLayout() instanceof BorderLayout) {
                return (JLabel) ((BorderLayout) parent.getLayout()).getLayoutComponent(BorderLayout.EAST);
            }
            if (child instanceof Container) {
                JLabel found = valueLabel((Container) child, description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static class StubPlugin extends FlippingPlugin {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        private final ItemManager itemManager = mock(ItemManager.class);
        private StatsPanel stats;

        private StubPlugin() {
            when(itemManager.getImage(4151)).thenReturn(new AsyncBufferedImage(
                new ClientThread(), 36, 32, BufferedImage.TYPE_INT_ARGB));
        }

        @Override public FlippingConfig getConfig() { return new FlippingConfig() {}; }
        @Override public ItemManager getItemManager() { return itemManager; }
        @Override public StatsPanel getStatPanel() { return stats; }
        @Override public ScheduledExecutorService getExecutor() { return executor; }
        @Override public String getAccountCurrentlyViewed() { return ACCOUNT_WIDE; }
        @Override public Instant viewStartOfSessionForCurrentView() { return Instant.EPOCH; }
        @Override public Duration viewAccumulatedTimeForCurrentView() { return Duration.ofHours(1); }
        @Override public Font getFont() { return new Font(Font.DIALOG, Font.PLAIN, 12); }
        @Override public Map<String, PartialOffer> getOfferIdToPartialOffer(int itemId) { return Collections.emptyMap(); }
        @Override public List<FlippingItem> viewItemsForCurrentView() { return Collections.emptyList(); }
        @Override public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() { return Collections.emptyList(); }
        @Override public List<FlippingItem> sortItems(List<FlippingItem> items, SORT sort, Instant since) { return items; }
        @Override public List<RecipeFlipGroup> sortRecipeFlipGroups(List<RecipeFlipGroup> groups, SORT sort, Instant since) { return groups; }
    }
}
