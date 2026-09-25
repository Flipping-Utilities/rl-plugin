package com.flippingutilities.ui.accounting;

import com.flippingutilities.ui.accounting.AccountingUiService.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.runelite.client.ui.ColorScheme;

/** Renders persisted query results. Filters, sorts, pages and exports share one captured query. */
public final class AccountingReportsPanel extends JPanel {
    private static final int PAGE_SIZE = 20;
    private final AccountingUiService service;
    private final Executor executor;
    final JComboBox<String> period = new JComboBox<>(new String[]{"Session", "24 hours", "7 days", "30 days", "90 days", "All", "Custom"});
    final JComboBox<String> view = new JComboBox<>(new String[]{"Items", "Recipes", "Tracked inventory"});
    final JComboBox<Sort> sort = new JComboBox<>(Sort.values());
    final JCheckBox archived = new JCheckBox("Archived history");
    final JTextField search = new JTextField();
    final JTextField from = new JTextField();
    final JTextField to = new JTextField();
    final JComboBox<String> zone = new JComboBox<>(new String[]{ZoneId.systemDefault().getId(), "UTC"});
    final JButton refreshButton = new JButton("Refresh");
    final JButton exportButton = new JButton("Export CSV");
    final JButton previousButton = new JButton("‹");
    final JButton nextButton = new JButton("›");
    final JButton backButton = new JButton("Back to summaries");
    final JTextField pageInput = new JTextField("1", 3);
    final JLabel pageCount = new JLabel("of 1");
    final JTextArea status = AccountingUi.text("Choose an account to view reports.");
    private final JTextArea scopeLabel = AccountingUi.text("");
    private final JPanel rows = AccountingUi.column();
    private final JPanel totals = AccountingUi.column();
    private final JPanel customDates = AccountingUi.column();
    private final Timer searchDebounce;
    private List<String> accounts = Collections.emptyList();
    private Instant sessionStart = Instant.now();
    private ReportKind kind = ReportKind.ITEMS;
    private String groupKey;
    private int page = 1;
    private long totalPages = 1;
    private long generation;
    private ReportQuery displayedQuery;
    private final Set<String> expandedAmounts = new HashSet<>();
    private final Set<String> expandedSources = new HashSet<>();
    private boolean disposed;
    private boolean updatingFilters;

    public AccountingReportsPanel(AccountingUiService service, Executor executor) {
        super(new BorderLayout());
        this.service = service;
        this.executor = executor;
        zone.setEditable(true);
        ZoneId initialZone = ZoneId.systemDefault();
        to.setText(AccountingUi.formatDate(Instant.now(), initialZone));
        from.setText(AccountingUi.formatDate(Instant.now().minus(Duration.ofDays(30)), initialZone));
        JPanel header = AccountingUi.column();
        header.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        header.add(scopeLabel);
        archived.setOpaque(false);
        archived.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        archived.setAlignmentX(Component.LEFT_ALIGNMENT);
        archived.setToolTipText("Browse retained legacy history separately from your active ledger");
        header.add(archived);
        header.add(AccountingUi.field("Period", period));
        customDates.add(AccountingUi.field("From, inclusive (ISO date/time)", from));
        customDates.add(AccountingUi.field("To, exclusive (ISO date/time)", to));
        customDates.add(AccountingUi.field("Time zone", zone));
        customDates.setVisible(false);
        header.add(customDates);
        header.add(AccountingUi.field("Search names", search));
        JPanel choices = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
        choices.setOpaque(false);
        choices.setAlignmentX(Component.LEFT_ALIGNMENT);
        choices.add(view);
        choices.add(sort);
        header.add(choices);
        JPanel actions = new JPanel(new java.awt.GridLayout(1, 2, 3, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.add(refreshButton);
        actions.add(exportButton);
        exportButton.setToolTipText("Export recognized profit for the displayed filters and accounting methods");
        header.add(actions);
        header.add(backButton);
        header.add(status);
        JPanel content = AccountingUi.column();
        content.add(totals);
        content.add(rows);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 3));
        navigation.setBackground(ColorScheme.DARK_GRAY_COLOR);
        navigation.add(previousButton);
        JLabel pageLabel = new JLabel("Page");
        pageLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        pageCount.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        navigation.add(pageLabel);
        navigation.add(pageInput);
        navigation.add(pageCount);
        navigation.add(nextButton);
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(navigation, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(225, 550));
        backButton.setVisible(false);
        exportButton.setEnabled(false);
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
        search.getAccessibleContext().setAccessibleName("Search report names");
        pageInput.getAccessibleContext().setAccessibleName("Report page");
        searchDebounce = new Timer(250, event -> changed());
        searchDebounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { searchChanged(); }
            public void removeUpdate(DocumentEvent e) { searchChanged(); }
            public void changedUpdate(DocumentEvent e) { searchChanged(); }
        });
        DocumentListener customDatesChanged = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { invalidateDates(); }
            public void removeUpdate(DocumentEvent e) { invalidateDates(); }
            public void changedUpdate(DocumentEvent e) { invalidateDates(); }
        };
        from.getDocument().addDocumentListener(customDatesChanged);
        to.getDocument().addDocumentListener(customDatesChanged);
        ((JTextField) zone.getEditor().getEditorComponent()).getDocument().addDocumentListener(customDatesChanged);
        period.addActionListener(event -> {
            customDates.setVisible(kind != ReportKind.INVENTORY && "Custom".equals(period.getSelectedItem()));
            changed();
        });
        view.addActionListener(event -> {
            kind = selectedKind();
            groupKey = null;
            backButton.setVisible(false);
            updatingFilters = true;
            Sort previousSort = (Sort) sort.getSelectedItem();
            sort.setModel(new DefaultComboBoxModel<>(kind == ReportKind.INVENTORY
                ? new Sort[]{Sort.TIME, Sort.QUANTITY} : Sort.values()));
            if (kind != ReportKind.INVENTORY || previousSort == Sort.TIME || previousSort == Sort.QUANTITY) sort.setSelectedItem(previousSort);
            period.setEnabled(kind != ReportKind.INVENTORY);
            customDates.setVisible(kind != ReportKind.INVENTORY && "Custom".equals(period.getSelectedItem()));
            updatingFilters = false;
            changed();
        });
        sort.addActionListener(event -> { if (!updatingFilters) changed(); });
        archived.addActionListener(event -> {
            groupKey = null;
            kind = selectedKind();
            backButton.setVisible(false);
            changed();
        });
        from.addActionListener(event -> changed());
        to.addActionListener(event -> changed());
        zone.addActionListener(event -> { if ("Custom".equals(period.getSelectedItem())) changed(); });
        refreshButton.addActionListener(event -> refresh());
        exportButton.addActionListener(event -> chooseExport());
        previousButton.addActionListener(event -> { if (page > 1) { --page; refresh(); } });
        nextButton.addActionListener(event -> { if (page < totalPages) { ++page; refresh(); } });
        pageInput.addActionListener(event -> {
            try {
                int requested = Integer.parseInt(pageInput.getText().trim());
                if (requested < 1 || requested > totalPages) throw new NumberFormatException();
                page = requested;
                refresh();
            } catch (NumberFormatException e) { pageInput.setText(String.valueOf(page)); }
        });
        backButton.addActionListener(event -> {
            groupKey = null;
            kind = selectedKind();
            backButton.setVisible(false);
            changed();
        });
    }

    public void setAccounts(List<String> scope, Instant startOfSession) {
        if (disposed) return;
        if (accounts.equals(scope) && sessionStart.equals(startOfSession)) { refresh(); return; }
        accounts = Collections.unmodifiableList(new ArrayList<>(scope));
        expandedAmounts.clear();
        expandedSources.clear();
        sessionStart = startOfSession;
        scopeLabel.setText(accounts.isEmpty() ? "No accounts" : String.join(", ", accounts));
        groupKey = null;
        kind = selectedKind();
        backButton.setVisible(false);
        displayedQuery = null;
        totals.removeAll();
        rows.removeAll();
        changed();
    }

    private void searchChanged() {
        ++generation; // Invalidate the old request immediately, before the debounce fires.
        exportButton.setEnabled(false);
        searchDebounce.restart();
    }

    private ReportKind selectedKind() {
        return view.getSelectedIndex() == 0 ? ReportKind.ITEMS
            : view.getSelectedIndex() == 1 ? ReportKind.RECIPES : ReportKind.INVENTORY;
    }

    private void changed() {
        page = 1;
        refresh();
    }

    private void invalidateDates() {
        if (!"Custom".equals(period.getSelectedItem())) return;
        ++generation;
        exportButton.setEnabled(false);
        AccountingUi.status(status, "Dates changed. Press Refresh to update the report.", false);
    }

    ReportQuery captureQuery() {
        if (kind == ReportKind.INVENTORY) {
            return new ReportQuery(accounts, null, null, "Current tracked inventory", search.getText(),
                (Sort) sort.getSelectedItem(), kind, null, page - 1, PAGE_SIZE, null, null, archived.isSelected());
        }
        String selection = (String) period.getSelectedItem();
        Instant end = Instant.now();
        Instant start;
        switch (selection) {
            case "All": start = null; end = null; break;
            case "Session": start = sessionStart; break;
            case "24 hours": start = end.minus(Duration.ofHours(24)); break;
            case "7 days": start = end.minus(Duration.ofDays(7)); break;
            case "30 days": start = end.minus(Duration.ofDays(30)); break;
            case "90 days": start = end.minus(Duration.ofDays(90)); break;
            case "Custom":
                ZoneId selectedZone = ZoneId.of(zone.getEditor().getItem().toString().trim());
                start = AccountingUi.parseDate(from.getText(), selectedZone);
                end = AccountingUi.parseDate(to.getText(), selectedZone);
                if (!start.isBefore(end)) throw new IllegalArgumentException("The end must be after the start.");
                selection = from.getText() + " to " + to.getText() + " " + selectedZone;
                break;
            default: throw new IllegalArgumentException("Unknown period");
        }
        return new ReportQuery(accounts, start, end, selection, search.getText(), (Sort) sort.getSelectedItem(),
            kind, groupKey, page - 1, PAGE_SIZE, null, null, archived.isSelected());
    }

    public void refresh() {
        if (disposed) return;
        searchDebounce.stop();
        long ticket = ++generation;
        exportButton.setEnabled(false);
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
        if (accounts.isEmpty()) {
            AccountingUi.status(status, "No accounts available.", false);
            return;
        }
        final ReportQuery query;
        try { query = captureQuery(); }
        catch (RuntimeException e) {
            AccountingUi.status(status, "Check the custom dates and time zone. The end must be after the start; use UTC for repeated local times.", true);
            return;
        }
        AccountingUi.status(status, displayedQuery == null ? "Loading report…" : "Refreshing… Previous results remain below.", false);
        AccountingUi.request(executor, () -> service.queryReport(query), (result, failure) -> {
            if (ticket != generation) return;
            if (failure != null) {
                AccountingUi.status(status, displayedQuery == null ? "The report could not be loaded. Try Refresh."
                    : "The report could not be refreshed. Results below are from the previous report.", true);
                return;
            }
            totalPages = Math.max(1, result.totalRows / PAGE_SIZE + (result.totalRows % PAGE_SIZE == 0 ? 0 : 1));
            if (page > totalPages) { page = (int) totalPages; refresh(); return; }
            displayedQuery = query.atRevision(result.sourceRevision, result.projectionRevision);
            render(result);
        });
    }

    private void render(ReportResult result) {
        totals.removeAll();
        if (displayedQuery.archived) {
            totals.add(AccountingUi.text("Archived history · Legacy calculations. These results are separate from your active ledger."));
        }
        result.segments.forEach(segment -> totals.add(kind == ReportKind.INVENTORY
            ? AccountingUi.inventorySegment(segment) : AccountingUi.segment(segment)));
        result.warnings.forEach(warning -> totals.add(AccountingUi.text(warning)));
        rows.removeAll();
        if (result.rows.isEmpty()) rows.add(AccountingUi.text("No results for this period and search."));
        for (ReportRow row : result.rows) rows.add(rowCard(row));
        pageInput.setText(String.valueOf(page));
        pageCount.setText("of " + AccountingUi.number(totalPages));
        previousButton.setEnabled(page > 1);
        nextButton.setEnabled(page < totalPages);
        exportButton.setEnabled(true);
        AccountingUi.status(status, AccountingUi.number(result.totalRows) + " results. Totals include all matching pages."
            + (result.segments.size() > 1 ? " Methods are shown separately." : ""), false);
        totals.revalidate();
        rows.revalidate();
        revalidate();
        repaint();
    }

    private JPanel rowCard(ReportRow row) {
        JPanel card = AccountingUi.card();
        card.add(AccountingUi.title(row.title));
        card.add(AccountingUi.text(row.accountLabel + " · " + row.methodLabel));
        if (kind == ReportKind.INVENTORY) {
            card.add(AccountingUi.text(AccountingUi.number(row.quantity) + " tracked units"));
            card.add(AccountingUi.text("Tracked cost: " + AccountingUi.money(row.amounts.cost)));
            card.add(AccountingUi.text("Cost per unit: " + AccountingUi.perUnit(row.amounts.cost, row.quantity)));
            card.add(AccountingUi.text(row.description));
            addSourcesButton(row.id, card);
            return card;
        }
        JTextArea profit = AccountingUi.text("Profit: " + AccountingUi.money(row.amounts.profit));
        if (row.amounts.profit.completeGp != null) profit.setForeground(row.amounts.profit.completeGp < 0
            ? new Color(250, 100, 100) : ColorScheme.GRAND_EXCHANGE_PRICE);
        card.add(profit);
        card.add(AccountingUi.text("Profit per unit: " + AccountingUi.perUnit(row.amounts.profit, row.quantity)));
        card.add(AccountingUi.text("Return on investment: " + AccountingUi.roi(row.amounts)));
        card.add(AccountingUi.text(AccountingUi.number(row.quantity) + " units · " + AccountingUi.number(row.count) + " flips"));
        card.add(AccountingUi.text(row.description));
        JPanel details = AccountingUi.amounts(row.amounts);
        details.setVisible(expandedAmounts.contains(row.id));
        JButton amounts = new JButton(details.isVisible() ? "Hide amounts" : "Show amounts");
        amounts.setName("reportAmounts:" + row.id);
        amounts.addActionListener(event -> {
            details.setVisible(!details.isVisible());
            if (details.isVisible()) expandedAmounts.add(row.id); else expandedAmounts.remove(row.id);
            amounts.setText(details.isVisible() ? "Hide amounts" : "Show amounts");
            card.revalidate();
        });
        card.add(amounts);
        card.add(details);
        if (row.detailKind != null && row.detailKey != null) {
            JButton show = new JButton("View flips");
            show.addActionListener(event -> {
                kind = row.detailKind;
                groupKey = row.detailKey;
                backButton.setVisible(true);
                changed();
            });
            card.add(show);
        } else {
            addSourcesButton(row.id, card);
        }
        return card;
    }

    private void addSourcesButton(String rowId, JPanel card) {
        JButton sourceButton = new JButton(expandedSources.contains(rowId) ? "Hide sources" : "Sources and allocations");
        sourceButton.setName("reportDetails:" + rowId);
        JPanel sources = AccountingUi.column();
        sources.setVisible(expandedSources.contains(rowId));
        sourceButton.addActionListener(event -> {
            sources.setVisible(!sources.isVisible());
            if (sources.isVisible()) {
                expandedSources.add(rowId);
                loadSources(rowId, sources, card);
            } else expandedSources.remove(rowId);
            sourceButton.setText(sources.isVisible() ? "Hide sources" : "Sources and allocations");
            card.revalidate();
        });
        card.add(sourceButton);
        card.add(sources);
        if (sources.isVisible()) loadSources(rowId, sources, card);
    }

    private void loadSources(String rowId, JPanel sources, JPanel card) {
        ReportQuery snapshot = displayedQuery;
        long ticket = generation;
        if (snapshot == null) return;
        sources.removeAll();
        sources.add(AccountingUi.text("Loading source details…"));
        sources.revalidate();
        AccountingUi.request(executor, () -> service.queryDetails(snapshot, rowId), (result, failure) -> {
            if (ticket != generation || !sources.isVisible()) return;
            sources.removeAll();
            if (failure != null) sources.add(AccountingUi.text("Details could not be loaded. Refresh and try again."));
            else {
                sources.add(AccountingUi.title(result.title));
                result.lines.forEach(line -> sources.add(AccountingUi.text(line)));
            }
            sources.revalidate();
            card.revalidate();
            card.repaint();
        });
    }

    private void chooseExport() {
        if (displayedQuery == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(kind == ReportKind.INVENTORY ? "Export tracked inventory" : "Export recognized profit");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv"));
        chooser.setSelectedFile(new java.io.File(kind == ReportKind.INVENTORY ? "inventory.csv" : "profit.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath();
        if (Files.exists(destination) && JOptionPane.showConfirmDialog(this,
            "Replace the selected file?", "Export profit", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        exportTo(destination);
    }

    void exportTo(Path destination) {
        if (displayedQuery == null) return;
        ReportQuery snapshot = displayedQuery;
        long ticket = generation;
        exportButton.setEnabled(false);
        AccountingUi.status(status, "Exporting all matching rows…", false);
        AccountingUi.request(executor, () -> service.exportReport(snapshot, destination), (written, failure) -> {
            if (ticket != generation) return;
            exportButton.setEnabled(true);
            AccountingUi.status(status, failure == null ? "Profit report exported."
                : "The export could not be completed. Refresh the report before trying again.", failure != null);
        });
    }

    void dispose() {
        disposed = true;
        ++generation;
        searchDebounce.stop();
        displayedQuery = null;
        exportButton.setEnabled(false);
    }
}
