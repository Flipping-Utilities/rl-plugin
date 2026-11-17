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
        bindEnterLikeSpace(button);
        return button;
    }

    /**
     * Gives Enter the same press/release semantics the look and feel installs for Space, so a held key
     * (auto-repeat) activates once on release instead of firing on every repeated press.
     * Enter stays local to the focused control.
     */
    static void bindEnterLikeSpace(AbstractButton button) {
        InputMap keys = button.getInputMap(JComponent.WHEN_FOCUSED);
        Object press = keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false));
        Object release = keys.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true));
        if (press != null && release != null
            && button.getActionMap().get(press) != null && button.getActionMap().get(release) != null) {
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, false), press);
            keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true), release);
            return;
        }
        // Look and feels without a Space binding: activate on release to avoid auto-repeat.
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0, true), "activate");
        button.getActionMap().put("activate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                button.doClick(0);
            }
        });
    }
}
