package com.flippingutilities.ui.accounting;

import java.awt.BorderLayout;
import java.util.concurrent.Executor;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/** Keeps an actionable failure visible while filters and report requests change. */
final class AccountingRecoveryPanel extends JPanel {
    final JButton retry = new JButton("Retry saves");
    private final JTextArea message = AccountingUi.text("");
    private boolean disposed;
    private boolean failed;
    private boolean externallyManaged;

    AccountingRecoveryPanel(AccountingUiService service, Executor executor, Runnable recovered) {
        super(new BorderLayout(0, 3));
        setOpaque(false);
        retry.setName("retrySaves");
        setAlignmentX(LEFT_ALIGNMENT);
        add(message, BorderLayout.CENTER);
        add(retry, BorderLayout.SOUTH);
        setVisible(false);
        retry.addActionListener(event -> {
            retry.setEnabled(false);
            AccountingUi.status(message, "Retrying pending saves…", false);
            AccountingUi.request(executor, service::retryStorage, (result, failure) -> {
                if (disposed) return;
                retry.setEnabled(true);
                if (failure != null) showFailure(failure);
                else {
                    clear();
                    recovered.run();
                }
            });
        });
    }

    void showFailure(Throwable failure) {
        if (disposed) return;
        AccountingUi.status(message, AccountingUi.failureMessage(failure), true);
        failed = true;
        setVisible(!externallyManaged);
        revalidate();
        repaint();
    }

    void clear() { if (!disposed) { failed = false; setVisible(false); } }

    void setExternallyManaged(boolean value) {
        externallyManaged = value;
        setVisible(failed && !value && !disposed);
    }

    void dispose() { disposed = true; retry.setEnabled(false); }
}
