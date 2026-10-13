package com.flippingutilities.ui.accounting;

import java.awt.BorderLayout;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import javax.swing.JPanel;
import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

/** Host for account policy review and SQL-backed reporting. The caller owns the worker executor. */
public final class AccountingPanel extends JPanel {
    private final AccountingSetupPanel setup;
    private final AccountingReportsPanel reports;
    private final AccountingRecoveryPanel recovery;
    private final JTabbedPane tabs = new JTabbedPane();
    private JComponent tradesTab;

    public AccountingPanel(AccountingUiService service, Executor executor) {
        this(service, executor, null, () -> {});
    }

    /** Trade editing retains the original offer/recipe actions and legacy financial labels. */
    public AccountingPanel(AccountingUiService service, Executor executor, JComponent trades, Runnable onTradesSelected) {
        super(new BorderLayout());
        setBackground(net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR);
        reports = new AccountingReportsPanel(service, executor);
        setup = new AccountingSetupPanel(service, executor, reports::refresh);
        recovery = new AccountingRecoveryPanel(service, executor, reports::refresh);
        add(recovery, BorderLayout.NORTH);
        tabs.addTab("Reports", reports);
        tabs.addTab("Accounting", setup);
        if (trades != null) {
            JPanel editor = new JPanel(new BorderLayout(0, 4));
            editor.setBackground(net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR);
            editor.add(AccountingUi.text("Trades editor · Financial figures here use the legacy calculation. "
                + "Edit offers and recipes here; the CSV icon exports raw trade history."), BorderLayout.NORTH);
            editor.add(trades, BorderLayout.CENTER);
            tradesTab = editor;
            tabs.addTab("Trades", editor);
            tabs.addChangeListener(event -> { if (isTradesSelected()) onTradesSelected.run(); });
        }
        add(tabs, BorderLayout.CENTER);
    }

    /** A selected name outside the account list means account-wide reporting. */
    public void setAccounts(List<String> accountNames, String selectedAccount, Instant sessionStart) {
        List<String> captured = AccountingUiService.immutable(accountNames);
        onEdt(() -> {
            setup.setAccounts(captured, selectedAccount);
            reports.setAccounts(captured.contains(selectedAccount)
                ? Collections.singletonList(selectedAccount) : captured, sessionStart);
        });
    }

    public void refreshReports() { onEdt(reports::refresh); }

    /** Recovery remains reachable even when no account or report can be loaded. */
    public void setPendingSaves(boolean pending) {
        onEdt(() -> {
            reports.recovery.setExternallyManaged(pending);
            setup.recovery.setExternallyManaged(pending);
            if (pending) recovery.showFailure(new IllegalStateException(
                "SQLite recovery is pending. Keep the client open and retry saves before closing it."));
            else recovery.clear();
        });
    }

    public boolean isTradesSelected() { return tradesTab != null && tabs.getSelectedComponent() == tradesTab; }

    public void dispose() {
        onEdt(() -> {
            setup.dispose();
            reports.dispose();
            recovery.dispose();
        });
    }

    private void onEdt(Runnable work) {
        if (SwingUtilities.isEventDispatchThread()) work.run();
        else SwingUtilities.invokeLater(work);
    }
}
