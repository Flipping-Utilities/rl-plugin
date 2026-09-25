package com.flippingutilities.ui.accounting;

import java.awt.BorderLayout;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

/** Host for account policy review and SQL-backed reporting. The caller owns the worker executor. */
public final class AccountingPanel extends JPanel {
    private final AccountingSetupPanel setup;
    private final AccountingReportsPanel reports;

    public AccountingPanel(AccountingUiService service, Executor executor) {
        super(new BorderLayout());
        reports = new AccountingReportsPanel(service, executor);
        setup = new AccountingSetupPanel(service, executor, reports::refresh);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Reports", reports);
        tabs.addTab("Accounting", setup);
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

    public void dispose() {
        onEdt(() -> {
            setup.dispose();
            reports.dispose();
        });
    }

    private void onEdt(Runnable work) {
        if (SwingUtilities.isEventDispatchThread()) work.run();
        else SwingUtilities.invokeLater(work);
    }
}
