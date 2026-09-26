package com.flippingutilities.ui.login;

import com.flippingutilities.ui.uiutilities.CustomColors;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** Token entry and request feedback; the owner supplies consent and authentication. */
public final class TokenLoginForm extends JPanel {
    private final JPasswordField token = new JPasswordField();
    private final JButton login = new JButton("Log in");
    private final JTextArea status = new JTextArea("Enter the token from the Flopper Discord bot.");
    private final BooleanSupplier confirm;
    private final Function<String, CompletableFuture<?>> authenticate;
    private boolean pending;

    public TokenLoginForm(BooleanSupplier confirm, Function<String, CompletableFuture<?>> authenticate) {
        super(new BorderLayout(0, 8));
        this.confirm = confirm;
        this.authenticate = authenticate;
        setOpaque(false);
        setBorder(new EmptyBorder(20, 0, 0, 0));
        setPreferredSize(new Dimension(170, 175));

        JLabel label = new JLabel("Token");
        label.setFont(FontManager.getRunescapeBoldFont());
        label.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        label.setLabelFor(token);
        token.setName("loginToken");
        token.getAccessibleContext().setAccessibleName("Discord login token");
        token.setToolTipText("Get a token with /login in the Flopper bot channel");
        token.setPreferredSize(new Dimension(170, 32));
        token.addActionListener(event -> submit());

        JPanel entry = new JPanel(new BorderLayout(0, 5));
        entry.setOpaque(false);
        entry.add(label, BorderLayout.NORTH);
        entry.add(token, BorderLayout.CENTER);
        add(entry, BorderLayout.NORTH);

        status.setEditable(false);
        status.setOpaque(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setFont(FontManager.getRunescapeSmallFont());
        status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        status.getAccessibleContext().setAccessibleName("Login status");
        status.setName("loginStatus");
        add(status, BorderLayout.CENTER);

        login.setName("loginSubmit");
        login.setToolTipText("Log in with this token");
        login.addActionListener(event -> submit());
        // Keep Enter and Space equivalent while the button has focus.
        login.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "pressed");
        login.getInputMap().put(KeyStroke.getKeyStroke("released ENTER"), "released");
        add(login, BorderLayout.SOUTH);
    }

    private void submit() {
        if (pending) return;
        String value = new String(token.getPassword()).trim();
        if (value.isEmpty()) {
            showError("Enter a token before logging in.");
            token.requestFocusInWindow();
            return;
        }
        // A modal confirmation runs a nested event loop, so guard it as well as the request.
        pending = true;
        boolean accepted;
        try {
            accepted = confirm.getAsBoolean();
        } catch (RuntimeException e) {
            pending = false;
            throw e;
        }
        if (!accepted) {
            pending = false;
            return;
        }
        token.setEnabled(false);
        login.setEnabled(false);
        login.setText("Logging in…");
        status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        status.setText("Checking your token…");
        try {
            authenticate.apply(value).whenComplete((result, error) ->
                SwingUtilities.invokeLater(() -> complete(error)));
        } catch (RuntimeException e) {
            complete(e);
        }
    }

    private void complete(Throwable error) {
        if (error == null) {
            token.setText("");
            login.setText("Logged in");
            status.setText("Logged in successfully.");
            return;
        }
        pending = false;
        token.setEnabled(true);
        login.setEnabled(true);
        login.setText("Log in");
        showError("Could not log in. Check your token or try again.");
        token.requestFocusInWindow();
    }

    private void showError(String message) {
        status.setForeground(CustomColors.TOMATO);
        status.setText(message);
    }
}
