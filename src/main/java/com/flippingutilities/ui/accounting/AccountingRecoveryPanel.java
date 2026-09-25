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

    AccountingRecoveryPanel(AccountingUiService service, Executor executor, Runnable recovered) {
        super(new BorderLayout(0, 3));
        setOpaque(false);
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
                    setVisible(false);
                    recovered.run();
                }
            });
        });
    }

    void showFailure(Throwable failure) {
        if (disposed) return;
        AccountingUi.status(message, AccountingUi.failureMessage(failure), true);
        setVisible(true);
        revalidate();
        repaint();
    }

    void clear() { if (!disposed) setVisible(false); }

    void dispose() { disposed = true; retry.setEnabled(false); }
}
