package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;

/** Compact sidebar actions with standard Swing keyboard and accessibility behavior. */
public final class IconButtons {
    private IconButtons() {}

    public static JButton action(String name, Icon icon, Icon hoverIcon, ActionListener action) {
        JButton button = configure(new JButton(icon), name, hoverIcon);
        button.addActionListener(action);
        return button;
    }

    public static JToggleButton toggle(String name, Icon icon, Icon hoverIcon, Icon selectedIcon) {
        JToggleButton button = configure(new JToggleButton(icon), name, hoverIcon);
        button.setSelectedIcon(selectedIcon);
        button.setRolloverSelectedIcon(selectedIcon);
        return button;
    }

    private static <T extends AbstractButton> T configure(T button, String name, Icon hoverIcon) {
        button.setToolTipText(name);
        button.getAccessibleContext().setAccessibleName(name);
        button.setRolloverIcon(hoverIcon);
        button.setPreferredSize(new Dimension(32, 32));
        button.setContentAreaFilled(false);
        button.setBorder(new EmptyBorder(1, 1, 1, 1));
        button.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                button.setBorder(BorderFactory.createLineBorder(ColorScheme.LIGHT_GRAY_COLOR));
            }

            @Override
            public void focusLost(FocusEvent event) {
                button.setBorder(new EmptyBorder(1, 1, 1, 1));
            }
        });
        // Space is supplied by Swing. Enter stays local to the focused control.
        button.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "activate");
        button.getActionMap().put("activate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                button.doClick();
            }
        });
        return button;
    }
}
