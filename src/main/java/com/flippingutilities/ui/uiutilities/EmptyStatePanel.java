package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;

/** Plain, wrapping text and an optional next step for a sidebar with no results. */
public final class EmptyStatePanel extends JPanel {
    public EmptyStatePanel(String title, String description) {
        this(title, description, null, null);
    }

    public EmptyStatePanel(String title, String description, String actionName, Runnable action) {
        super(new BorderLayout(0, 10));
        setBorder(new EmptyBorder(20, 10, 20, 10));
        JLabel heading = new JLabel(title, SwingConstants.CENTER);
        heading.setFont(FontManager.getRunescapeBoldFont());
        add(heading, BorderLayout.NORTH);

        JTextArea explanation = new JTextArea(description, 4, 16);
        explanation.setFont(FontManager.getRunescapeFont());
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setEditable(false);
        explanation.setFocusable(false);
        explanation.setOpaque(false);
        explanation.getAccessibleContext().setAccessibleName(description);
        add(explanation, BorderLayout.CENTER);

        if (action != null) {
            JButton button = new JButton(actionName);
            button.addActionListener(event -> action.run());
            add(button, BorderLayout.SOUTH);
        }
    }
}
