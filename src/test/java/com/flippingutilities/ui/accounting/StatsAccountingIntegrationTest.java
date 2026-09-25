package com.flippingutilities.ui.accounting;

import com.flippingutilities.FlippingConfig;
import com.flippingutilities.controller.DataHandler;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.utilities.SORT;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.swing.SwingUtilities;
import javax.swing.JTabbedPane;
import javax.swing.JComponent;
import com.flippingutilities.ui.statistics.items.FlippingItemContainerPanel;
import com.flippingutilities.ui.statistics.recipes.RecipeGroupContainerPanel;
import org.junit.Test;
import static org.junit.Assert.*;

public class StatsAccountingIntegrationTest {
    @Test public void accountingRouteSkipsLegacyScansAndRestoresLegacyWhenDetached() throws Exception {
        StubPlugin plugin = new StubPlugin();
        StatsPanel[] panel = new StatsPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new StatsPanel(plugin);
                panel[0].rebuildItemsDisplay(Collections.emptyList());
                panel[0].rebuildRecipesDisplay(Collections.emptyList());
            });
            flush();
            flush();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(containsAccountingPanel(panel[0]));
                assertEquals("No legacy financial sorting should run for the accounting view", 0, plugin.legacySortCalls);
                assertEquals("Paired item/recipe rebuilds should coalesce into one setup/report request", 2, plugin.worker.size());
                plugin.service = null;
                panel[0].rebuildItemsDisplay(Collections.emptyList());
            });
            flush();
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(containsAccountingPanel(panel[0]));
                assertTrue("Legacy reporting should resume after service detach", plugin.legacySortCalls > 0);
            });
        } finally { plugin.executor.shutdownNow(); }
    }

    @Test public void tradesEditorRetainsManagementAndRawExportWithoutScanningOnReports() throws Exception {
        StubPlugin plugin = new StubPlugin();
        StatsPanel[] panel = new StatsPanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new StatsPanel(plugin);
                panel[0].rebuildItemsDisplay(Collections.emptyList());
            });
            flush();
            flush();
            SwingUtilities.invokeAndWait(() -> {
                JTabbedPane tabs = find(panel[0], JTabbedPane.class);
                assertEquals("Reports", tabs.getTitleAt(tabs.getSelectedIndex()));
                assertEquals(0, plugin.legacySortCalls);
                int trades = tabs.indexOfTab("Trades");
                assertTrue(trades >= 0);
                Container editor = (Container) tabs.getComponentAt(trades);
                assertNotNull("Original offer panels remain reachable", find(editor, FlippingItemContainerPanel.class));
                assertNotNull("Original recipe panels remain reachable", find(editor, RecipeGroupContainerPanel.class));
                assertTrue("Original raw-trade export remains reachable", hasTooltip(editor, "Export to CSV"));
                tabs.setSelectedIndex(trades);
            });
            flush();
            flush();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue("Legacy scans run only after entering the editor", plugin.legacySortCalls > 0);
                find(panel[0], JTabbedPane.class).setSelectedIndex(0);
                plugin.legacySortCalls = 0;
                panel[0].rebuildItemsDisplay(Collections.emptyList());
                panel[0].rebuildRecipesDisplay(Collections.emptyList());
            });
            flush();
            flush();
            assertEquals("Returning to Reports restores the SQL-only refresh path", 0, plugin.legacySortCalls);
        } finally { plugin.executor.shutdownNow(); }
    }

    private static void flush() throws Exception { SwingUtilities.invokeAndWait(() -> {}); }

    private static boolean containsAccountingPanel(Container parent) {
        for (Component component : parent.getComponents()) {
            if (component instanceof AccountingPanel) return true;
            if (component instanceof Container && containsAccountingPanel((Container) component)) return true;
        }
        return false;
    }

    private static <T extends Component> T find(Container parent, Class<T> type) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container) {
                T child = find((Container) component, type);
                if (child != null) return child;
            }
        }
        return null;
    }

    private static boolean hasTooltip(Container parent, String tooltip) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JComponent && tooltip.equals(((JComponent) component).getToolTipText())) return true;
            if (component instanceof Container && hasTooltip((Container) component, tooltip)) return true;
        }
        return false;
    }

    private static final class StubPlugin extends FlippingPlugin {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        private final List<Runnable> worker = new ArrayList<>();
        private AccountingUiService service = new NeverCalledService();
        private int legacySortCalls;
        @Override public void registerAccountingItemNames(List<FlippingItem> items) {}
        @Override public AccountingUiService getAccountingUiService() { return service; }
        @Override public Executor getAccountingExecutor() { return worker::add; }
        @Override public DataHandler getDataHandler() {
            return new DataHandler(this) {
                @Override public Set<String> getCurrentAccounts() { return Collections.singleton("Account"); }
            };
        }
        @Override public FlippingConfig getConfig() { return new FlippingConfig() {}; }
        @Override public ScheduledExecutorService getExecutor() { return executor; }
        @Override public String getAccountCurrentlyViewed() { return "Account"; }
        @Override public Instant viewStartOfSessionForCurrentView() { return Instant.EPOCH; }
        @Override public Duration viewAccumulatedTimeForCurrentView() { return Duration.ZERO; }
        @Override public Font getFont() { return new Font(Font.DIALOG, Font.PLAIN, 12); }
        @Override public List<FlippingItem> viewItemsForCurrentView() { return Collections.emptyList(); }
        @Override public List<RecipeFlipGroup> viewRecipeFlipGroupsForCurrentView() { return Collections.emptyList(); }
        @Override public List<FlippingItem> sortItems(List<FlippingItem> items, SORT sort, Instant since) {
            ++legacySortCalls;
            return items;
        }
        @Override public List<RecipeFlipGroup> sortRecipeFlipGroups(List<RecipeFlipGroup> groups, SORT sort, Instant since) {
            ++legacySortCalls;
            return groups;
        }
    }

    private static final class NeverCalledService implements AccountingUiService {
        @Override public CompletableFuture<PlanHistory> loadPlans(String account) { throw new AssertionError("worker was not drained"); }
        @Override public CompletableFuture<Preview> preview(PlanRequest request) { throw new AssertionError(); }
        @Override public CompletableFuture<String> apply(Preview preview) { throw new AssertionError(); }
        @Override public CompletableFuture<ReportResult> queryReport(ReportQuery query) { throw new AssertionError("worker was not drained"); }
        @Override public CompletableFuture<Path> exportReport(ReportQuery query, Path destination) { throw new AssertionError(); }
    }
}
