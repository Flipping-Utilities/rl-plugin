package com.flippingutilities.ui.accounting;

import com.flippingutilities.accounting.AccountingPlan.Mode;
import com.flippingutilities.ui.accounting.AccountingUiService.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.swing.JSpinner;
import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccountingPanelsTest {
    private static final Instant START = Instant.parse("2026-09-25T14:30:00Z");
    private final ManualExecutor worker = new ManualExecutor();
    private final StubService service = new StubService();

    @Test public void openingStockIsOptInAndPresetResolvesAgainstFixedCutover() throws Exception {
        AccountingSetupPanel panel = setup();
        edt(() -> {
            panel.mode.setSelectedItem(Mode.FRESH_START);
            panel.zone.setSelectedItem("UTC");
            panel.cutover.setText("2026-09-25T14:30:00");
            PlanRequest empty = panel.captureRequest();
            assertNull(empty.purchaseCutoff);
            assertTrue(empty.openingQuantities.isEmpty());
            panel.cutoff.setSelectedItem(AccountingSetupPanel.Cutoff.DAYS_30);
            PlanRequest dated = panel.captureRequest();
            assertEquals(START, dated.cutover);
            assertEquals(START.minus(Duration.ofDays(30)), dated.purchaseCutoff);
            panel.mode.setSelectedItem(Mode.RECALCULATE);
            PlanRequest all = panel.captureRequest();
            assertNull(all.cutover);
            assertNull(all.purchaseCutoff);
            assertTrue(all.openingQuantities.isEmpty());
            assertFalse(panel.applyButton.isEnabled());
        });
        assertTrue(service.applied.isEmpty());
    }

    @Test public void editedChoiceDiscardsPendingPreviewWithoutChangingActivePlan() throws Exception {
        AccountingSetupPanel panel = setup();
        edt(() -> panel.previewButton.doClick());
        worker.drain();
        edt(() -> panel.mode.setSelectedItem(Mode.RECALCULATE));
        service.previews.get(0).complete(preview("old", service.requests.get(0), Collections.emptyList()));
        flush();
        edt(() -> {
            assertFalse(panel.applyButton.isEnabled());
            assertTrue(panel.status.getText().contains("Choose Preview"));
        });
        assertTrue(service.applied.isEmpty());
    }

    @Test public void quantitiesNeedAnotherPreviewAndStaleApplyCannotBeRetriedBlindly() throws Exception {
        AccountingSetupPanel panel = setup();
        edt(() -> {
            panel.mode.setSelectedItem(Mode.FRESH_START);
            panel.cutoff.setSelectedItem(AccountingSetupPanel.Cutoff.DAYS_30);
            panel.previewButton.doClick();
        });
        worker.drain();
        List<OpeningCandidate> candidates = Arrays.asList(
            new OpeningCandidate("eligible", "Abyssal whip", START.minusSeconds(100), 4, 400L, false, null),
            new OpeningCandidate("excluded", "Old purchase", START.minusSeconds(100), 4, 400L, false, "Used by frozen legacy sales"));
        service.previews.get(0).complete(preview("first", service.requests.get(0), candidates));
        flush();
        edt(() -> {
            assertTrue(panel.applyButton.isEnabled());
            JSpinner quantity = named(panel, JSpinner.class, "openingQuantity:eligible");
            assertEquals(0L, quantity.getValue());
            assertNull(named(panel, JSpinner.class, "openingQuantity:excluded"));
            quantity.setValue(2L);
            assertFalse(panel.applyButton.isEnabled());
            panel.previewButton.doClick();
        });
        worker.drain();
        assertEquals(Collections.singletonMap("eligible", 2L), service.requests.get(1).openingQuantities);
        service.previews.get(1).complete(preview("reviewed", service.requests.get(1), candidates));
        flush();
        edt(() -> {
            panel.applyButton.doClick();
            panel.applyButton.doClick();
        });
        worker.drain();
        assertEquals(1, service.applied.size());
        assertEquals("reviewed", service.applied.get(0).id);
        service.applyResult.completeExceptionally(new StalePreviewException("revision changed"));
        flush();
        edt(() -> {
            assertFalse(panel.applyButton.isEnabled());
            assertTrue(panel.previewButton.isEnabled());
            assertTrue(panel.status.getText().contains("Trading data changed"));
        });
    }

    @Test public void uncommittedQuantityAndTimeZoneEditsDisableApply() throws Exception {
        AccountingSetupPanel panel = setup();
        edt(() -> {
            panel.mode.setSelectedItem(Mode.FRESH_START);
            panel.cutoff.setSelectedItem(AccountingSetupPanel.Cutoff.DAYS_7);
            panel.previewButton.doClick();
        });
        worker.drain();
        service.previews.get(0).complete(preview("first", service.requests.get(0), Collections.singletonList(
            new OpeningCandidate("source", "Item", START.minusSeconds(10), 4, 400L, false, null))));
        flush();
        edt(() -> {
            JSpinner spinner = named(panel, JSpinner.class, "openingQuantity:source");
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText("3");
            panel.applyButton.doClick();
            assertFalse(panel.applyButton.isEnabled());
            panel.previewButton.doClick();
        });
        worker.drain();
        assertTrue(service.applied.isEmpty());
        assertEquals(Long.valueOf(3), service.requests.get(1).openingQuantities.get("source"));
        service.previews.get(1).complete(preview("second", service.requests.get(1), Collections.emptyList()));
        flush();
        edt(() -> {
            ((javax.swing.JTextField) panel.zone.getEditor().getEditorComponent()).setText("UTC");
            assertFalse(panel.applyButton.isEnabled());
        });
    }

    @Test public void reviewingSavedChoiceRepreviewsItsDatesAndSelections() throws Exception {
        PlanRequest saved = new PlanRequest("Account", Mode.FRESH_START, START, START.minus(Duration.ofDays(7)),
            ZoneId.of("UTC"), Collections.singletonMap("purchase", 2L), null);
        service.history = new PlanHistory("Keep current calculations", Collections.singletonList(
            new SavedPlan("prior-plan", "Fresh ledger in September", false, saved)));
        AccountingSetupPanel panel = setup();
        edt(() -> panel.reviewSaved.doClick());
        worker.drain();
        PlanRequest request = service.requests.get(0);
        assertEquals("prior-plan", request.priorPlanId);
        assertEquals(saved.cutover, request.cutover);
        assertEquals(saved.purchaseCutoff, request.purchaseCutoff);
        assertEquals(saved.openingQuantities, request.openingQuantities);
        assertTrue(service.applied.isEmpty());
    }

    @Test public void sameAccountRefreshDoesNotEraseAReviewedChoice() throws Exception {
        AccountingSetupPanel panel = setup();
        edt(() -> panel.previewButton.doClick());
        worker.drain();
        service.previews.get(0).complete(preview("ready", service.requests.get(0), Collections.emptyList()));
        flush();
        edt(() -> {
            panel.setAccounts(Collections.singletonList("Account"), "Account");
            assertTrue(panel.applyButton.isEnabled());
        });
    }

    @Test public void bulkPreviewsRequireAnIndividualApplyAndNeverSelectOpeningStock() throws Exception {
        AccountingSetupPanel panel = setup();
        edt(() -> {
            panel.setAccounts(Arrays.asList("Account", "Other"), "Account");
            panel.mode.setSelectedItem(Mode.FRESH_START);
            panel.cutoff.setSelectedItem(AccountingSetupPanel.Cutoff.DAYS_30);
            panel.bulkPreviewButton.doClick();
        });
        worker.drain();
        assertEquals(2, service.requests.size());
        for (int i = 0; i < service.requests.size(); i++) {
            assertTrue(service.requests.get(i).openingQuantities.isEmpty());
            service.previews.get(i).complete(preview("preview-" + i, service.requests.get(i), Collections.emptyList()));
        }
        flush();
        assertTrue(service.applied.isEmpty());
        edt(() -> {
            named(panel, JButton.class, "applyAccount:Other").doClick();
            named(panel, JButton.class, "applyAccount:Other").doClick();
        });
        worker.drain();
        assertEquals(1, service.applied.size());
        assertEquals("Other", service.applied.get(0).request.account);
        service.applyResult.completeExceptionally(new StalePreviewException("changed"));
        flush();
        edt(() -> {
            assertFalse(named(panel, JButton.class, "applyAccount:Other").isEnabled());
            assertTrue(containsText(panel, "Trading data changed"));
            panel.mode.setSelectedItem(Mode.LEGACY);
            assertNull(named(panel, JButton.class, "applyAccount:Account"));
        });
    }

    @Test public void openingCandidatesHaveBoundedControlsAndPreserveCrossPageEdits() throws Exception {
        AccountingSetupPanel panel = setup();
        edt(() -> {
            panel.mode.setSelectedItem(Mode.FRESH_START);
            panel.cutoff.setSelectedItem(AccountingSetupPanel.Cutoff.DAYS_90);
            panel.previewButton.doClick();
        });
        worker.drain();
        List<OpeningCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 1000; i++) candidates.add(new OpeningCandidate("source-" + i,
            "Item " + i, START.minusSeconds(i + 1), 5, 500L, false, null));
        service.previews.get(0).complete(preview("many", service.requests.get(0), candidates));
        flush();
        edt(() -> {
            assertEquals(50, countComponents(panel, JSpinner.class));
            JSpinner first = named(panel, JSpinner.class, "openingQuantity:source-0");
            ((JSpinner.DefaultEditor) first.getEditor()).getTextField().setText("3");
            panel.nextCandidates.doClick();
            assertEquals(50, countComponents(panel, JSpinner.class));
            assertNull(named(panel, JSpinner.class, "openingQuantity:source-0"));
            named(panel, JSpinner.class, "openingQuantity:source-50").setValue(4L);
            panel.previousCandidates.doClick();
            assertEquals(3L, named(panel, JSpinner.class, "openingQuantity:source-0").getValue());
            panel.previewButton.doClick();
        });
        worker.drain();
        assertEquals(2, service.requests.get(1).openingQuantities.size());
        assertEquals(Long.valueOf(3), service.requests.get(1).openingQuantities.get("source-0"));
        assertEquals(Long.valueOf(4), service.requests.get(1).openingQuantities.get("source-50"));
    }

    @Test public void archiveQueriesAndVisiblePagesKeepTheSqlPageIndexAndExportScope() throws Exception {
        AccountingReportsPanel panel = reports();
        assertEquals(0, service.queries.get(0).page);
        service.reports.get(0).complete(new ReportResult(Collections.emptyList(), Collections.emptyList(),
            42, "source", "projection", Collections.emptyList()));
        flush();
        edt(() -> panel.nextButton.doClick());
        worker.drain();
        assertEquals(1, service.queries.get(1).page);
        edt(() -> panel.archived.doClick());
        worker.drain();
        assertTrue(service.queries.get(2).archived);
        assertEquals(0, service.queries.get(2).page);
        service.reports.get(2).complete(report("Archive", "source", "projection", false));
        flush();
        edt(() -> {
            assertEquals("1", panel.pageInput.getText());
            assertTrue(containsText(panel, "Archived history"));
            panel.exportTo(Paths.get("archive.csv"));
        });
        worker.drain();
        assertTrue(service.exported.archived);
        assertEquals(0, service.exported.page);
    }

    @Test public void sourceDetailsUseTheDisplayedRevisionAndActivityIsLabeledAcrossItems() throws Exception {
        AccountingReportsPanel panel = reports();
        ReportResult base = report("Stored flip", "source-1", "projection-1", false);
        Segment original = base.segments.get(0);
        Segment activity = new Segment(original.accountLabel, original.methodLabel, original.periodLabel,
            original.amounts, original.flipCount, original.soldQuantity, original.unknownQuantity,
            new Activity(8, 3, Money.known(800), Money.known(400), Money.known(4)));
        service.reports.get(0).complete(new ReportResult(Collections.singletonList(activity), base.rows,
            1, base.sourceRevision, base.projectionRevision, Collections.emptyList()));
        flush();
        edt(() -> {
            assertTrue(containsText(panel, "All-item trade activity"));
            assertTrue(containsText(panel, "Purchases: 800 gp"));
            named(panel, JButton.class, "reportDetails:id").doClick();
        });
        worker.drain();
        assertEquals("source-1", service.detailsQuery.sourceRevision);
        assertEquals("projection-1", service.detailsQuery.projectionRevision);
        assertEquals("id", service.detailsId);
        service.details.complete(new ReportDetails("Sources", Collections.singletonList("Purchase 12: allocated 2 of 8")));
        flush();
        edt(() -> {
            assertTrue(containsText(panel, "Purchase 12: allocated 2 of 8"));
            named(panel, JButton.class, "reportAmounts:id").doClick();
            panel.refresh();
        });
        worker.drain();
        service.reports.get(1).complete(report("Stored flip", "source-2", "projection-2", false));
        flush();
        worker.drain();
        flush();
        assertEquals("source-2", service.detailsQuery.sourceRevision);
        edt(() -> {
            assertEquals("Hide amounts", named(panel, JButton.class, "reportAmounts:id").getText());
            assertEquals("Hide sources", named(panel, JButton.class, "reportDetails:id").getText());
        });
    }

    @Test public void inventoryShowsCurrentCostAndQuantityWithoutSaleMetrics() throws Exception {
        AccountingReportsPanel panel = reports();
        edt(() -> panel.view.setSelectedIndex(2));
        worker.drain();
        ReportQuery query = service.queries.get(1);
        assertEquals(ReportKind.INVENTORY, query.kind);
        assertNull(query.fromInclusive);
        assertNull(query.toExclusive);
        assertEquals("Current tracked inventory", query.periodLabel);
        service.reports.get(1).complete(report("Unsold item", "source", "projection", false));
        flush();
        edt(() -> {
            assertFalse(panel.period.isEnabled());
            assertEquals(2, panel.sort.getItemCount());
            assertTrue(containsText(panel, "2 tracked units"));
            assertTrue(containsText(panel, "Tracked cost: 100 gp"));
            assertFalse(containsText(panel, "Profit:"));
        });
    }

    @Test public void displayedRatiosAndHourlyProfitRespectUnknownAndZeroBasis() throws Exception {
        Amounts known = new Amounts(Money.known(100), Money.known(300), Money.known(400), Money.known(0), Money.known(400));
        assertEquals("33.33%", AccountingUi.roi(known));
        assertEquals("33.33 gp", AccountingUi.perUnit(known.profit, 3));
        assertEquals("N/A", AccountingUi.perUnit(known.profit, 0));
        Amounts noBasis = new Amounts(Money.known(100), Money.known(0), Money.known(100), Money.known(0), Money.known(100));
        assertEquals("N/A (no positive basis)", AccountingUi.roi(noBasis));
        Money unknown = new Money(null, 15, 2, true);
        assertEquals("Unknown", AccountingUi.perUnit(unknown, 3));
        assertEquals("3074457345618258602.33 gp", AccountingUi.perUnit(Money.known(Long.MAX_VALUE), 3));
        edt(() -> {
            javax.swing.JPanel timed = AccountingUi.segment(new Segment("Account", "New", "Session", known, 1, 1, 0, null, 1_800_000L));
            assertTrue(containsText(timed, "Tracked time: 0h 30m 0s"));
            assertTrue(containsText(timed, "Profit per tracked hour: 200 gp"));
            javax.swing.JPanel untimed = AccountingUi.segment(new Segment("Account", "New", "All", known, 1, 1, 0));
            assertFalse(containsText(untimed, "Profit per tracked hour"));
        });
    }

    @Test public void staleAccountReportIsIgnoredAndReadFailureKeepsVisibleRows() throws Exception {
        AccountingReportsPanel panel = reports();
        edt(() -> panel.setAccounts(Collections.singletonList("Other account"), START));
        worker.drain();
        service.reports.get(1).complete(report("current item", "new-source", "new-projection", true));
        flush();
        service.reports.get(0).complete(report("stale item", "old-source", "old-projection", false));
        flush();
        edt(() -> {
            assertTrue(containsText(panel, "current item"));
            assertFalse(containsText(panel, "stale item"));
            assertTrue(containsText(panel, "Unknown (known subtotal: 15 gp; 1 incomplete)"));
            panel.refresh();
        });
        worker.drain();
        service.reports.get(2).completeExceptionally(new IllegalStateException("query failed"));
        flush();
        edt(() -> {
            assertTrue(containsText(panel, "current item"));
            assertTrue(panel.status.getText().contains("previous report"));
            assertFalse(panel.exportButton.isEnabled());
        });
    }

    @Test public void saveFailureRemainsVisibleUntilAnExplicitSuccessfulRetry() throws Exception {
        AccountingReportsPanel panel = reports();
        service.reports.get(0).completeExceptionally(new IllegalStateException("Pending saves: disk full; keep the plugin open"));
        flush();
        edt(() -> {
            assertTrue(panel.recovery.isVisible());
            assertTrue(containsText(panel, "Pending saves: disk full"));
            panel.refresh();
            assertTrue(panel.recovery.isVisible());
            panel.recovery.retry.doClick();
            panel.recovery.retry.doClick();
            assertFalse(panel.recovery.retry.isEnabled());
        });
        worker.drain();
        assertEquals(1, service.retries.size());
        service.retries.get(0).completeExceptionally(new IllegalStateException("Still cannot save: disk full"));
        flush();
        edt(() -> {
            assertTrue(containsText(panel, "Still cannot save: disk full"));
            panel.recovery.retry.doClick();
        });
        worker.drain();
        service.retries.get(1).complete("Pending saves recovered");
        flush();
        edt(() -> assertFalse(panel.recovery.isVisible()));
        worker.drain();
        assertEquals("Recovery refreshes the report", 3, service.queries.size());
    }

    @Test public void saveRecoveryIsReachableBeforeAnyAccountOrReportLoads() throws Exception {
        AccountingPanel[] panel = new AccountingPanel[1];
        edt(() -> {
            panel[0] = new AccountingPanel(service, worker);
            panel[0].setAccounts(Collections.emptyList(), "Account wide", START);
            panel[0].setPendingSaves(true);
            JButton retry = named(panel[0], JButton.class, "retrySaves");
            assertTrue(retry.getParent().isVisible());
            assertTrue(containsText(panel[0], "SQLite recovery is pending"));
            retry.doClick();
        });
        worker.drain();
        assertEquals(1, service.retries.size());
        assertTrue(service.queries.isEmpty());
        edt(() -> {
            panel[0].setPendingSaves(false);
            assertFalse(named(panel[0], JButton.class, "retrySaves").getParent().isVisible());
        });
    }

    @Test public void setupFailuresExposeTheReasonAndSaveRetry() throws Exception {
        AccountingSetupPanel panel = setup();
        edt(() -> panel.previewButton.doClick());
        worker.drain();
        service.previews.get(0).completeExceptionally(new IllegalStateException("Pending offers could not be saved"));
        flush();
        edt(() -> {
            assertTrue(panel.recovery.isVisible());
            assertTrue(containsText(panel, "Pending offers could not be saved"));
            assertFalse(panel.applyButton.isEnabled());
            panel.recovery.retry.doClick();
        });
        worker.drain();
        assertEquals(1, service.retries.size());
    }

    @Test public void sourcePagesReplaceControlsAndPreserveRevisionAndPageOnRefresh() throws Exception {
        service.pageDetails = true;
        AccountingReportsPanel panel = reports();
        service.reports.get(0).complete(report("Flip", "source-1", "projection-1", false));
        flush();
        edt(() -> named(panel, JButton.class, "reportDetails:id").doClick());
        worker.drain();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 50; ++i) lines.add("Source page zero record " + i);
        service.pagedDetails.get(0).complete(new ReportDetails("Sources", lines, 0, true));
        flush();
        edt(() -> {
            assertTrue(containsText(panel, "Source page zero record 49"));
            assertFalse(named(panel, JButton.class, "previousSources:id").isEnabled());
            named(panel, JButton.class, "nextSources:id").doClick();
        });
        worker.drain();
        assertEquals(Arrays.asList(0, 1), service.requestedDetailPages);
        assertEquals("source-1", service.detailsQuery.sourceRevision);
        service.pagedDetails.get(1).complete(new ReportDetails("Sources", Collections.singletonList("Source page one record"), 1, false));
        flush();
        edt(() -> {
            assertFalse(containsText(panel, "Source page zero record"));
            assertTrue(containsText(panel, "Source page one record"));
            assertFalse(named(panel, JButton.class, "nextSources:id").isEnabled());
            assertTrue(named(panel, JButton.class, "previousSources:id").isEnabled());
            panel.refresh();
        });
        worker.drain();
        service.reports.get(1).complete(report("Flip", "source-2", "projection-2", false));
        flush();
        worker.drain();
        assertEquals(Arrays.asList(0, 1, 1), service.requestedDetailPages);
        assertEquals("source-2", service.detailsQuery.sourceRevision);
    }

    @Test public void exportUsesDisplayedBoundsAndRevisionsAndDoesNotRecalculateMoney() throws Exception {
        AccountingReportsPanel panel = reports();
        service.reports.get(0).complete(report("zero profit", "source-7", "projection-3", false));
        flush();
        edt(() -> {
            assertTrue(containsText(panel, "Profit: 0 gp"));
            panel.exportTo(Paths.get("report.csv"));
        });
        worker.drain();
        ReportQuery queried = service.queries.get(0);
        ReportQuery exported = service.exported;
        assertEquals(queried.fromInclusive, exported.fromInclusive);
        assertEquals(queried.toExclusive, exported.toExclusive);
        assertEquals("source-7", exported.sourceRevision);
        assertEquals("projection-3", exported.projectionRevision);
        assertEquals(queried.accounts, exported.accounts);
    }

    @Test public void localDatesRejectDstGapsAndAmbiguity() {
        ZoneId zone = ZoneId.of("America/Montreal");
        for (String value : Arrays.asList("2026-03-08T02:30:00", "2026-11-01T01:30:00")) {
            try {
                AccountingUi.parseDate(value, zone);
                fail("Should require an unambiguous instant");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("daylight saving"));
            }
        }
        assertEquals(START, AccountingUi.parseDate("2026-09-25T14:30:00", ZoneId.of("UTC")));
    }

    private AccountingSetupPanel setup() throws Exception {
        AccountingSetupPanel[] panels = new AccountingSetupPanel[1];
        edt(() -> {
            panels[0] = new AccountingSetupPanel(service, worker, () -> {});
            panels[0].setAccounts(Collections.singletonList("Account"), "Account");
        });
        worker.drain();
        flush();
        return panels[0];
    }

    private AccountingReportsPanel reports() throws Exception {
        AccountingReportsPanel[] panels = new AccountingReportsPanel[1];
        edt(() -> {
            panels[0] = new AccountingReportsPanel(service, worker);
            panels[0].setAccounts(Collections.singletonList("Account"), START);
        });
        worker.drain();
        return panels[0];
    }

    private Preview preview(String id, PlanRequest request, List<OpeningCandidate> candidates) {
        return new Preview(id, "revision", request, candidates, Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), true);
    }

    private ReportResult report(String title, String source, String projection, boolean unknown) {
        Money money = unknown ? new Money(null, 15, 1, false) : Money.known(0);
        Amounts amounts = new Amounts(money, Money.known(100), Money.known(120), Money.known(0), Money.known(120));
        ReportRow row = new ReportRow("id", "Account", "Sale-time profit", title, "Exact stored amounts", amounts,
            2, 1, null, null);
        Segment segment = new Segment("Account", "Sale-time profit", "Session", amounts, 1, 2, unknown ? 1 : 0);
        return new ReportResult(Collections.singletonList(segment), Collections.singletonList(row), 1, source,
            projection, Collections.emptyList());
    }

    private static <T extends Component> T named(Container parent, Class<T> type, String name) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) return type.cast(component);
            if (component instanceof Container) {
                T found = named((Container) component, type, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean containsText(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JTextArea && ((JTextArea) component).getText().contains(text)) return true;
            if (component instanceof javax.swing.JLabel && ((javax.swing.JLabel) component).getText().contains(text)) return true;
            if (component instanceof Container && containsText((Container) component, text)) return true;
        }
        return false;
    }

    private static int countComponents(Container parent, Class<? extends Component> type) {
        int count = 0;
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) ++count;
            if (component instanceof Container) count += countComponents((Container) component, type);
        }
        return count;
    }

    private static void edt(Runnable work) throws Exception { SwingUtilities.invokeAndWait(work); }
    private static void flush() throws Exception { edt(() -> {}); }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> work = new ArrayDeque<>();
        @Override public void execute(Runnable command) { work.add(command); }
        void drain() { while (!work.isEmpty()) work.remove().run(); }
    }

    private static final class StubService implements AccountingUiService {
        PlanHistory history = new PlanHistory("Keep current calculations", Collections.emptyList());
        final List<PlanRequest> requests = new ArrayList<>();
        final List<CompletableFuture<Preview>> previews = new ArrayList<>();
        final List<Preview> applied = new ArrayList<>();
        final CompletableFuture<String> applyResult = new CompletableFuture<>();
        final List<ReportQuery> queries = new ArrayList<>();
        final List<CompletableFuture<ReportResult>> reports = new ArrayList<>();
        ReportQuery exported;
        ReportQuery detailsQuery;
        String detailsId;
        final CompletableFuture<ReportDetails> details = new CompletableFuture<>();
        final List<CompletableFuture<String>> retries = new ArrayList<>();
        boolean pageDetails;
        final List<Integer> requestedDetailPages = new ArrayList<>();
        final List<CompletableFuture<ReportDetails>> pagedDetails = new ArrayList<>();
        private void workerOnly() { assertFalse("Service calls must stay off the EDT", SwingUtilities.isEventDispatchThread()); }
        @Override public CompletableFuture<PlanHistory> loadPlans(String account) {
            workerOnly();
            return CompletableFuture.completedFuture(history);
        }
        @Override public CompletableFuture<Preview> preview(PlanRequest request) {
            workerOnly();
            requests.add(request);
            CompletableFuture<Preview> future = new CompletableFuture<>();
            previews.add(future);
            return future;
        }
        @Override public CompletableFuture<String> apply(Preview preview) {
            workerOnly();
            applied.add(preview);
            return applyResult;
        }
        @Override public CompletableFuture<ReportResult> queryReport(ReportQuery query) {
            workerOnly();
            queries.add(query);
            CompletableFuture<ReportResult> future = new CompletableFuture<>();
            reports.add(future);
            return future;
        }
        @Override public CompletableFuture<Path> exportReport(ReportQuery query, Path destination) {
            workerOnly();
            exported = query;
            return CompletableFuture.completedFuture(destination);
        }
        @Override public CompletableFuture<ReportDetails> queryDetails(ReportQuery query, String rowId) {
            workerOnly();
            detailsQuery = query;
            detailsId = rowId;
            return details;
        }
        @Override public CompletableFuture<ReportDetails> queryDetails(ReportQuery query, String rowId, int page) {
            if (!pageDetails) return queryDetails(query, rowId);
            workerOnly();
            detailsQuery = query;
            detailsId = rowId;
            requestedDetailPages.add(page);
            CompletableFuture<ReportDetails> future = new CompletableFuture<>();
            pagedDetails.add(future);
            return future;
        }
        @Override public CompletableFuture<String> retryStorage() {
            workerOnly();
            CompletableFuture<String> future = new CompletableFuture<>();
            retries.add(future);
            return future;
        }
    }
}
