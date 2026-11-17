package com.flippingutilities.ui.login;

import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.ButtonModel;
import javax.swing.JButton;

/** Keeps the login panel's coloured call-to-action look on real, keyboard-operable buttons. */
final class LoginButtons {
    static final Font FONT = new Font("Whitney", Font.BOLD, 12);

    private LoginButtons() {
    }

    static JButton filled(JButton button, Color background, Color foreground) {
        button.setFont(FONT);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setMargin(new Insets(8, 18, 8, 18));
        ButtonModel model = button.getModel();
        model.addChangeListener(event ->
            button.setBackground(model.isRollover() && model.isEnabled() ? background.brighter() : background));
        return button;
    }
}
