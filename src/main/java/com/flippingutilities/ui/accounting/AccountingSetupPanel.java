package com.flippingutilities.ui.accounting;

import com.flippingutilities.accounting.AccountingPlan.Mode;
import com.flippingutilities.ui.accounting.AccountingUiService.*;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A preview changes no reporting policy. Only Apply sends the reviewed preview to the writer. */
public final class AccountingSetupPanel extends JPanel {
    private static final int CANDIDATE_PAGE_SIZE = 50;
    enum Cutoff {
        NONE("No previous purchases", 0), DAYS_7("Previous 7 days", 7),
        DAYS_30("Previous 30 days", 30), DAYS_90("Previous 90 days", 90), CUSTOM("Custom earliest date", -1);
        final String label;
        final int days;
        Cutoff(String label, int days) { this.label = label; this.days = days; }
        @Override public String toString() { return label; }
    }

    private final AccountingUiService service;
    private final Executor executor;
    private final Runnable onApplied;
    final AccountingRecoveryPanel recovery;
    final JComboBox<String> account = new JComboBox<>();
    final JComboBox<Mode> mode = new JComboBox<>(Mode.values());
    final JComboBox<Cutoff> cutoff = new JComboBox<>(Cutoff.values());
    final JComboBox<String> zone = new JComboBox<>(new String[]{ZoneId.systemDefault().getId(), "UTC"});
    final JTextField cutover = new JTextField();
    final JTextField earliestPurchase = new JTextField();
    final JButton now = new JButton("Use now");
    final JButton previewButton = new JButton("Preview choice");
    final JButton applyButton = new JButton("Apply reviewed choice");
    final JButton bulkPreviewButton = new JButton("Preview each account");
    final JButton previousCandidates = new JButton("‹");
    final JButton nextCandidates = new JButton("›");
    private final JLabel candidatePageLabel = new JLabel("1", SwingConstants.CENTER);
    final JComboBox<SavedPlan> savedPlans = new JComboBox<>();
    final JButton reviewSaved = new JButton("Review this choice");
    final JTextArea status = AccountingUi.text("Choose an account to review its calculations.");
    private final JTextArea active = AccountingUi.text("");
    private final JTextArea explanation = AccountingUi.text("");
    private final JPanel datedFields = AccountingUi.column();
    private final JPanel earliestPurchaseField;
    private final JPanel candidatePanel = AccountingUi.column();
    private final JPanel comparisonPanel = AccountingUi.column();
    private final JPanel bulkPanel = AccountingUi.column();
    private final List<JButton> bulkApplyButtons = new ArrayList<>();
    private final Map<String, Long> quantities = new LinkedHashMap<>();
    private final List<JSpinner> quantityControls = new ArrayList<>();
    private List<OpeningCandidate> candidates = Collections.emptyList();
    private int candidatePage;
    private Preview reviewed;
    private long generation;
    private long accountGeneration;
    private boolean updating;
    private boolean applying;
    private boolean disposed;
    private String priorPlanId;

    public AccountingSetupPanel(AccountingUiService service, Executor executor, Runnable onApplied) {
        super(new BorderLayout());
        this.service = service;
        this.executor = executor;
        this.onApplied = onApplied;
        recovery = new AccountingRecoveryPanel(service, executor, () -> {
            invalidate("Saves retried. Preview again before applying a choice.");
            loadPlans();
            onApplied.run();
        });
        zone.setEditable(true);
        cutover.setText(AccountingUi.formatDate(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), ZoneId.systemDefault()));
        earliestPurchase.setText(cutover.getText());
        mode.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, modeLabel((Mode) value), index, selected, focus);
            }
        });
        JPanel content = AccountingUi.column();
        content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        content.add(AccountingUi.field("Account", account));
        content.add(active);
        content.add(AccountingUi.field("Calculations", mode));
        content.add(explanation);
        datedFields.add(AccountingUi.field("Time zone", zone));
        JPanel date = new JPanel(new BorderLayout(0, 3));
        date.setOpaque(false);
        date.add(cutover, BorderLayout.CENTER);
        date.add(now, BorderLayout.SOUTH);
        datedFields.add(AccountingUi.field("Start at (ISO date/time)", date));
        datedFields.add(AccountingUi.field("Opening purchases", cutoff));
        earliestPurchaseField = AccountingUi.field("Earliest purchase (same zone)", earliestPurchase);
        datedFields.add(earliestPurchaseField);
        datedFields.add(AccountingUi.text("Previous purchases start unselected. Carry only stock you still own; excluded purchases remain in history."));
        content.add(datedFields);
        content.add(candidatePanel);
        content.add(previewButton);
        content.add(status);
        content.add(recovery);
        content.add(comparisonPanel);
        content.add(applyButton);
        content.add(bulkPreviewButton);
        content.add(bulkPanel);
        content.add(AccountingUi.field("Saved choices", savedPlans));
        content.add(reviewSaved);
        content.add(AccountingUi.text("Reviewing an earlier choice creates a new preview against retained trading data. It does not restore an old database."));
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        add(scroll);
        setPreferredSize(new Dimension(225, 550));
        account.addActionListener(event -> { if (!updating) accountChanged(); });
        mode.addActionListener(event -> choicesChanged());
        cutoff.addActionListener(event -> choicesChanged());
        zone.addActionListener(event -> choicesChanged());
        DocumentListener datesChanged = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { choicesChanged(); }
            public void removeUpdate(DocumentEvent e) { choicesChanged(); }
            public void changedUpdate(DocumentEvent e) { choicesChanged(); }
        };
        cutover.getDocument().addDocumentListener(datesChanged);
        earliestPurchase.getDocument().addDocumentListener(datesChanged);
        ((JTextField) zone.getEditor().getEditorComponent()).getDocument().addDocumentListener(datesChanged);
        now.addActionListener(event -> {
            try { cutover.setText(AccountingUi.formatDate(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), selectedZone())); }
            catch (RuntimeException e) { AccountingUi.status(status, "Enter a valid time zone, for example UTC or America/Montreal.", true); }
        });
        previewButton.addActionListener(event -> preview());
        applyButton.addActionListener(event -> apply());
        bulkPreviewButton.addActionListener(event -> previewAllAccounts());
        reviewSaved.addActionListener(event -> reviewSaved());
        previousCandidates.addActionListener(event -> changeCandidatePage(-1));
        nextCandidates.addActionListener(event -> changeCandidatePage(1));
        updateControls();
    }

    public void setAccounts(List<String> accounts, String selected) {
        if (disposed) return;
        String previous = (String) account.getSelectedItem();
        List<String> previousAccounts = new ArrayList<>();
        for (int i = 0; i < account.getItemCount(); i++) previousAccounts.add(account.getItemAt(i));
        String target = accounts.contains(selected) ? selected : accounts.contains(previous) ? previous
            : accounts.isEmpty() ? null : accounts.get(0);
        if (previousAccounts.equals(accounts) && java.util.Objects.equals(previous, target)) {
            if (recovery.isVisible()) loadPlans();
            return;
        }
        ++generation;
        reviewed = null;
        updating = true;
        account.removeAllItems();
        accounts.forEach(account::addItem);
        if (accounts.contains(selected)) account.setSelectedItem(selected);
        else if (accounts.contains(previous)) account.setSelectedItem(previous);
        updating = false;
        accountChanged();
    }

    private void accountChanged() {
        ++accountGeneration;
        updating = true;
        mode.setSelectedItem(Mode.LEGACY);
        cutoff.setSelectedItem(Cutoff.NONE);
        savedPlans.removeAllItems();
        priorPlanId = null;
        updating = false;
        choicesChanged();
        loadPlans();
    }

    private void loadPlans() {
        String name = (String) account.getSelectedItem();
        long ticket = accountGeneration;
        if (name == null) { active.setText("No accounts available."); updateControls(); return; }
        active.setText("Loading current choice…");
        AccountingUi.request(executor, () -> service.loadPlans(name), (history, failure) -> {
            if (ticket != accountGeneration) return;
            if (failure != null) {
                active.setText("Current choice could not be loaded. Your reports have not changed.");
                recovery.showFailure(failure);
                return;
            }
            active.setText("Active: " + history.activeDescription);
            recovery.clear();
            savedPlans.removeAllItems();
            history.plans.forEach(savedPlans::addItem);
            updateControls();
        });
    }

    private void choicesChanged() {
        if (updating || applying) return;
        quantities.clear();
        quantityControls.clear();
        candidates = Collections.emptyList();
        candidatePage = 0;
        candidatePanel.removeAll();
        invalidate("Choose Preview to compare these calculations. Current reports are unchanged.");
        updateControls();
    }

    private void invalidate(String message) {
        ++generation;
        reviewed = null;
        applyButton.setEnabled(false);
        bulkApplyButtons.forEach(button -> button.setEnabled(false));
        bulkApplyButtons.clear();
        bulkPanel.removeAll();
        AccountingUi.status(status, message, false);
        candidatePanel.revalidate();
        candidatePanel.repaint();
    }

    private boolean dated() {
        return mode.getSelectedItem() == Mode.HYBRID || mode.getSelectedItem() == Mode.FRESH_START;
    }

    private void updateControls() {
        boolean enabled = !applying && !disposed;
        account.setEnabled(enabled);
        mode.setEnabled(enabled);
        zone.setEnabled(enabled && dated());
        cutover.setEnabled(enabled && dated());
        now.setEnabled(enabled && dated());
        cutoff.setEnabled(enabled && dated());
        earliestPurchase.setEnabled(enabled && dated() && cutoff.getSelectedItem() == Cutoff.CUSTOM);
        datedFields.setVisible(dated());
        earliestPurchaseField.setVisible(cutoff.getSelectedItem() == Cutoff.CUSTOM);
        previewButton.setEnabled(enabled && account.getSelectedItem() != null);
        bulkPreviewButton.setVisible(account.getItemCount() > 1);
        bulkPreviewButton.setEnabled(enabled && account.getItemCount() > 1);
        previousCandidates.setEnabled(enabled && candidatePage > 0);
        nextCandidates.setEnabled(enabled && (candidatePage + 1) * CANDIDATE_PAGE_SIZE < candidates.size());
        applyButton.setEnabled(enabled && reviewed != null && reviewed.canApply);
        savedPlans.setEnabled(enabled && savedPlans.getItemCount() > 0);
        reviewSaved.setEnabled(savedPlans.isEnabled());
        explanation.setText(modeDescription((Mode) mode.getSelectedItem()));
    }

    private ZoneId selectedZone() { return ZoneId.of(zone.getEditor().getItem().toString().trim()); }

    PlanRequest captureRequest() {
        String name = (String) account.getSelectedItem();
        if (name == null) throw new IllegalArgumentException("Choose an account first.");
        ZoneId selectedZone = dated() ? selectedZone() : ZoneId.systemDefault();
        Instant start = dated() ? AccountingUi.parseDate(cutover.getText(), selectedZone) : null;
        Cutoff selectedCutoff = (Cutoff) cutoff.getSelectedItem();
        Instant purchase = !dated() || selectedCutoff == Cutoff.NONE ? null
            : selectedCutoff == Cutoff.CUSTOM ? AccountingUi.parseDate(earliestPurchase.getText(), selectedZone)
            : start.minus(Duration.ofDays(selectedCutoff.days));
        if (purchase != null && purchase.isAfter(start)) {
            throw new IllegalArgumentException("The earliest purchase must be on or before the accounting start.");
        }
        Map<String, Long> selections = new LinkedHashMap<>();
        if (purchase != null) quantities.forEach((id, quantity) -> { if (quantity > 0) selections.put(id, quantity); });
        return new PlanRequest(name, (Mode) mode.getSelectedItem(), start, purchase, selectedZone, selections, priorPlanId);
    }

    private void preview() {
        if (disposed) return;
        PlanRequest request;
        try {
            for (JSpinner spinner : quantityControls) spinner.commitEdit();
            request = captureRequest();
        } catch (Exception e) {
            AccountingUi.status(status, e instanceof IllegalArgumentException && !(e instanceof java.time.DateTimeException)
                ? e.getMessage() : "Enter an ISO date and time and a valid zone. Example: 2026-09-25T14:30:00, UTC.", true);
            return;
        }
        final long ticket = ++generation;
        bulkApplyButtons.forEach(button -> button.setEnabled(false));
        bulkApplyButtons.clear();
        bulkPanel.removeAll();
        reviewed = null;
        applyButton.setEnabled(false);
        AccountingUi.status(status, "Preparing preview… Current reports are unchanged.", false);
        AccountingUi.request(executor, () -> service.preview(request), (result, failure) -> {
            if (ticket != generation) return;
            if (failure != null) {
                AccountingUi.status(status, "Could not prepare a preview. Your current choice is unchanged. Try again.", true);
                recovery.showFailure(failure);
                return;
            }
            reviewed = result;
            renderPreview(result);
            updateControls();
        });
    }

    private void previewAllAccounts() {
        if (disposed || applying) return;
        final PlanRequest shared;
        try { shared = captureRequest(); }
        catch (RuntimeException failure) {
            AccountingUi.status(status, "Check the dates and time zone before previewing all accounts.", true);
            return;
        }
        final long ticket = ++generation;
        reviewed = null;
        applyButton.setEnabled(false);
        bulkApplyButtons.forEach(button -> button.setEnabled(false));
        bulkApplyButtons.clear();
        bulkPanel.removeAll();
        AccountingUi.status(status, "Review each account below and apply individually. Bulk previews carry no previous stock; review an account's stock separately to select it.", false);
        for (int index = 0; index < account.getItemCount(); index++) {
            String name = account.getItemAt(index);
            PlanRequest request = new PlanRequest(name, shared.mode, shared.cutover, shared.purchaseCutoff,
                shared.displayZone, Collections.emptyMap(), null);
            JPanel resultPanel = AccountingUi.card();
            resultPanel.add(AccountingUi.title(name));
            JTextArea progress = AccountingUi.text("Preparing this account's preview…");
            resultPanel.add(progress);
            bulkPanel.add(resultPanel);
            AccountingUi.request(executor, () -> service.preview(request), (result, failure) -> {
                if (ticket != generation) return;
                if (failure != null) {
                    AccountingUi.status(progress, "Preview unavailable. " + AccountingUi.failureMessage(failure), true);
                    recovery.showFailure(failure);
                    return;
                }
                progress.setText("No previous stock selected. Existing history is retained.");
                for (Segment segment : result.comparisons) resultPanel.add(AccountingUi.segment(segment));
                for (Impact impact : result.impacts) {
                    resultPanel.add(AccountingUi.text(impact.label + ": " + AccountingUi.number(impact.quantity) + " units · "
                        + (impact.amountGp == null ? "Unknown value" : AccountingUi.number(impact.amountGp) + " gp")
                        + ". " + impact.explanation));
                }
                result.warnings.forEach(warning -> resultPanel.add(AccountingUi.text(warning)));
                JButton applyAccount = new JButton("Apply this account");
                applyAccount.setName("applyAccount:" + name);
                applyAccount.setEnabled(result.canApply);
                bulkApplyButtons.add(applyAccount);
                applyAccount.addActionListener(event -> {
                    applyAccount.setEnabled(false);
                    progress.setText("Applying this account's reviewed choice…");
                    AccountingUi.request(executor, () -> service.apply(result), (message, applyFailure) -> {
                        if (applyFailure == null) onApplied.run();
                        if (ticket != generation) return;
                        AccountingUi.status(progress, applyFailure == null ? "Choice applied for this account."
                            : AccountingUi.cause(applyFailure) instanceof StalePreviewException
                            ? "Trading data changed. Preview this account again before applying."
                            : "This choice was not applied. Preview again before retrying.", applyFailure != null);
                        if (applyFailure != null && !(AccountingUi.cause(applyFailure) instanceof StalePreviewException)) recovery.showFailure(applyFailure);
                        if (applyFailure == null && name.equals(account.getSelectedItem())) loadPlans();
                    });
                });
                resultPanel.add(applyAccount);
                if (shared.cutover != null) {
                    JButton reviewStock = new JButton("Review this account's stock");
                    reviewStock.addActionListener(event -> loadChoice(request, null));
                    resultPanel.add(reviewStock);
                }
                resultPanel.revalidate();
                bulkPanel.revalidate();
                bulkPanel.repaint();
            });
        }
        bulkPanel.revalidate();
        bulkPanel.repaint();
    }

    private void renderPreview(Preview preview) {
        comparisonPanel.removeAll();
        if (preview.request.cutover != null) {
            comparisonPanel.add(AccountingUi.text("New calculations begin "
                + AccountingUi.formatDate(preview.request.cutover, preview.request.displayZone)
                + " " + preview.request.displayZone + "."));
        }
        if (preview.request.mode == Mode.FRESH_START) {
            comparisonPanel.add(AccountingUi.text("Earlier history is retained in the archive and excluded from this ledger."));
        }
        for (Segment segment : preview.comparisons) comparisonPanel.add(AccountingUi.segment(segment));
        for (Impact impact : preview.impacts) {
            comparisonPanel.add(AccountingUi.text(impact.label + ": " + AccountingUi.number(impact.quantity)
                + " units · " + (impact.amountGp == null ? "Unknown value" : AccountingUi.number(impact.amountGp) + " gp")
                + ". " + impact.explanation));
        }
        preview.warnings.forEach(warning -> comparisonPanel.add(AccountingUi.text(warning)));
        renderCandidates(preview.candidates);
        AccountingUi.status(status, preview.canApply
            ? "Preview ready. Review the comparison, then apply. Changing carried stock requires another preview."
            : "This choice cannot be applied yet. Review the exclusions and conflicts below.", !preview.canApply);
        comparisonPanel.revalidate();
        comparisonPanel.repaint();
    }

    private void renderCandidates(List<OpeningCandidate> candidates) {
        this.candidates = candidates;
        candidatePage = 0;
        Map<String, Long> validated = new LinkedHashMap<>();
        for (OpeningCandidate candidate : candidates) {
            boolean selectable = candidate.eligible() && dated() && cutoff.getSelectedItem() != Cutoff.NONE;
            long selected = selectable
                ? Math.min(quantities.getOrDefault(candidate.sourceId, 0L), candidate.availableQuantity) : 0L;
            if (selected > 0) validated.put(candidate.sourceId, selected);
        }
        quantities.clear();
        quantities.putAll(validated);
        renderCandidatePage();
    }

    private void changeCandidatePage(int direction) {
        try {
            for (JSpinner spinner : quantityControls) spinner.commitEdit();
        } catch (java.text.ParseException error) {
            AccountingUi.status(status, "Enter a valid carried quantity before changing purchase pages.", true);
            return;
        }
        int lastPage = Math.max(0, (candidates.size() - 1) / CANDIDATE_PAGE_SIZE);
        candidatePage = Math.max(0, Math.min(lastPage, candidatePage + direction));
        renderCandidatePage();
    }

    private void renderCandidatePage() {
        candidatePanel.removeAll();
        quantityControls.clear();
        int first = candidatePage * CANDIDATE_PAGE_SIZE;
        int end = Math.min(first + CANDIDATE_PAGE_SIZE, candidates.size());
        if (!candidates.isEmpty()) {
            candidatePanel.add(AccountingUi.title("Review opening stock"));
            candidatePanel.add(AccountingUi.text("Purchases " + AccountingUi.number(first + 1) + "–"
                + AccountingUi.number(end) + " of " + AccountingUi.number(candidates.size())
                + ". Selections are kept across pages."));
            if (candidates.size() > CANDIDATE_PAGE_SIZE) {
                JPanel navigation = new JPanel(new java.awt.GridLayout(1, 3, 3, 0));
                navigation.setOpaque(false);
                navigation.setAlignmentX(Component.LEFT_ALIGNMENT);
                previousCandidates.setEnabled(!applying && candidatePage > 0);
                nextCandidates.setEnabled(!applying && end < candidates.size());
                candidatePageLabel.setText((candidatePage + 1) + " / " + ((candidates.size() - 1) / CANDIDATE_PAGE_SIZE + 1));
                candidatePageLabel.setForeground(net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR);
                navigation.add(previousCandidates);
                navigation.add(candidatePageLabel);
                navigation.add(nextCandidates);
                candidatePanel.add(navigation);
            }
        }
        for (OpeningCandidate candidate : candidates.subList(first, end)) {
            JPanel row = AccountingUi.card();
            row.add(AccountingUi.title(candidate.itemName));
            row.add(AccountingUi.text("Bought " + (candidate.acquiredAt == null ? "at an unknown date"
                : AccountingUi.formatDate(candidate.acquiredAt, selectedZone()) + " " + selectedZone())));
            row.add(AccountingUi.text(AccountingUi.number(candidate.availableQuantity) + " available · "
                + (candidate.availableCostGp == null ? "Unknown cost" : AccountingUi.number(candidate.availableCostGp) + " gp")
                + (candidate.estimated ? " (estimated)" : "")));
            boolean selectable = candidate.eligible() && dated() && cutoff.getSelectedItem() != Cutoff.NONE;
            long selected = selectable
                ? Math.min(quantities.getOrDefault(candidate.sourceId, 0L), candidate.availableQuantity) : 0L;
            if (selectable) {
                JSpinner quantity = new JSpinner(new SpinnerNumberModel(Long.valueOf(selected), Long.valueOf(0),
                    Long.valueOf(candidate.availableQuantity), Long.valueOf(1)));
                quantity.setName("openingQuantity:" + candidate.sourceId);
                quantity.getAccessibleContext().setAccessibleName("Carry quantity for " + candidate.itemName);
                quantityControls.add(quantity);
                row.add(AccountingUi.field("Carry quantity (0 excludes it)", quantity));
                quantity.addChangeListener(event -> {
                    quantities.put(candidate.sourceId, ((Number) quantity.getValue()).longValue());
                    invalidate("Carried quantities changed. Preview again before applying.");
                });
            } else {
                row.add(AccountingUi.text("Excluded: " + (candidate.eligible()
                    ? "No previous purchases selected." : candidate.exclusionReason == null || candidate.exclusionReason.isEmpty()
                    ? "Purchase date or cost is unavailable." : candidate.exclusionReason)));
            }
            candidatePanel.add(row);
        }
        candidatePanel.revalidate();
        candidatePanel.repaint();
    }

    private void apply() {
        if (disposed) return;
        try {
            for (JSpinner spinner : quantityControls) spinner.commitEdit();
        } catch (java.text.ParseException e) {
            invalidate("Enter a valid carried quantity, then preview again.");
            return;
        }
        if (reviewed == null || !reviewed.canApply || applying) return;
        Preview applyingPreview = reviewed;
        long ticket = ++generation;
        applying = true;
        reviewed = null;
        quantityControls.forEach(control -> control.setEnabled(false));
        updateControls();
        AccountingUi.status(status, "Applying the reviewed choice…", false);
        AccountingUi.request(executor, () -> service.apply(applyingPreview), (message, failure) -> {
            applying = false;
            if (failure == null) onApplied.run();
            if (ticket != generation) { updateControls(); return; }
            quantityControls.forEach(control -> control.setEnabled(true));
            if (failure != null) {
                AccountingUi.status(status, AccountingUi.cause(failure) instanceof StalePreviewException
                    ? "Trading data changed since this preview. Preview again before applying."
                    : "The choice was not applied. Refresh the preview and try again.", true);
                if (!(AccountingUi.cause(failure) instanceof StalePreviewException)) recovery.showFailure(failure);
            } else {
                AccountingUi.status(status, message == null ? "Choice applied. Your history is retained." : message, false);
                loadPlans();
            }
            updateControls();
        });
    }

    private void reviewSaved() {
        SavedPlan saved = (SavedPlan) savedPlans.getSelectedItem();
        if (saved == null || applying) return;
        loadChoice(saved.choices, saved.id);
    }

    private void loadChoice(PlanRequest request, String savedId) {
        updating = true;
        boolean differentAccount = !request.account.equals(account.getSelectedItem());
        account.setSelectedItem(request.account);
        mode.setSelectedItem(request.mode);
        zone.setSelectedItem(request.displayZone.getId());
        if (request.cutover != null) cutover.setText(AccountingUi.formatDate(request.cutover, request.displayZone));
        cutoff.setSelectedItem(request.purchaseCutoff == null ? Cutoff.NONE : Cutoff.CUSTOM);
        if (request.purchaseCutoff != null) earliestPurchase.setText(AccountingUi.formatDate(request.purchaseCutoff, request.displayZone));
        quantities.clear();
        quantities.putAll(request.openingQuantities);
        priorPlanId = savedId;
        updating = false;
        quantityControls.clear();
        candidates = Collections.emptyList();
        candidatePage = 0;
        candidatePanel.removeAll();
        if (differentAccount) { ++accountGeneration; loadPlans(); }
        updateControls();
        preview();
    }

    void dispose() {
        disposed = true;
        ++generation;
        ++accountGeneration;
        reviewed = null;
        recovery.dispose();
        bulkApplyButtons.forEach(button -> button.setEnabled(false));
        updateControls();
    }

    private static String modeLabel(Mode mode) {
        if (mode == null) return "";
        switch (mode) {
            case LEGACY: return "Keep current calculations";
            case RECALCULATE: return "Recalculate all history";
            case HYBRID: return "Keep history, switch from a date";
            case FRESH_START: return "Start fresh from a date";
            default: throw new IllegalArgumentException("Unknown mode");
        }
    }

    private static String modeDescription(Mode mode) {
        switch (mode) {
            case LEGACY: return "Keep the existing method. Selecting a date range continues to recalculate trades inside that range.";
            case RECALCULATE: return "Recalculate retained history. Profit belongs to each sale, using eligible earlier purchase costs. Preview differences before applying.";
            case HYBRID: return "Keep earlier legacy reports; use sale-time profit from the chosen date. Reports label the two methods separately.";
            case FRESH_START: return "Keep earlier history in an archive. Begin a new ledger at the chosen date, with no opening stock unless you select it.";
            default: throw new IllegalArgumentException("Unknown mode");
        }
    }
}
