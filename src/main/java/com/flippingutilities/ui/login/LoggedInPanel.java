package com.flippingutilities.ui.login;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

public class LoggedInPanel extends JPanel {
    FlippingPlugin plugin;
    Runnable showLoggedOutPanel;
    JLabel slotFeatureHealthLabel = new JLabel("Inactive (not logged in)");

    public LoggedInPanel(FlippingPlugin plugin, Runnable showLoggedOutPanel) {
        this.plugin = plugin;
        this.showLoggedOutPanel = showLoggedOutPanel;
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(createTitle(), BorderLayout.NORTH);
        add(createSignOutButton(), BorderLayout.SOUTH);
    }

    private JLabel createTitle() {
        JLabel headerLabel = new JLabel("Status", JLabel.CENTER);
        headerLabel.setFont(new Font("Whitney", Font.PLAIN + Font.BOLD, 16));
        headerLabel.setForeground(CustomColors.CHEESE);
        return headerLabel;
    }

    /**
     * Creates the panel that is shown when there is an error logging in.
     * It displays an error message and a retry button.
     *
     * @return a new JPanel for the error state
     */
    private JPanel createErrorPanel() {
        JPanel errorPanel = new JPanel();
        errorPanel.setLayout(new BoxLayout(errorPanel, BoxLayout.Y_AXIS));
        errorPanel.setBorder(new EmptyBorder(10, 0, 10, 0));

        JLabel errorMessage = new JLabel(
                String.format(
                        "<html><div style=\"text-align:center\" WIDTH=%d>%s</div></html>",
                        180,
                        "Error logging in"
                ),
                JLabel.CENTER
        );
        errorMessage.setFont(new Font("Whitney", Font.PLAIN, 12));
        errorMessage.setForeground(CustomColors.TOMATO);
        errorMessage.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel retryLink = new JLabel("<html><u>Retry</u></html>", JLabel.CENTER);
        retryLink.setFont(new Font("Whitney", Font.PLAIN, 12));
        retryLink.setForeground(Color.WHITE);
        retryLink.setCursor(new Cursor(Cursor.HAND_CURSOR));
        retryLink.setAlignmentX(Component.CENTER_ALIGNMENT);

        retryLink.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        // Bad method name, but this re-triggers the login flow
                        plugin.getApiAuthHandler().checkApiSession();
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        retryLink.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        retryLink.setForeground(Color.WHITE);
                    }
                }
        );

        errorPanel.add(errorMessage);
        errorPanel.add(Box.createVerticalStrut(10));
        errorPanel.add(retryLink);

        return errorPanel;
    }

    private JPanel createApiFeaturesPanel() {
        JPanel healthPanel = new JPanel();
        healthPanel.setLayout(new BoxLayout(healthPanel, BoxLayout.Y_AXIS));
        healthPanel.setBorder(new EmptyBorder(10, 0, 10, 0));

        JPanel slotSendingHealthPanel = new JPanel(new BorderLayout());
        JLabel slotFeatureHealthRight = new JLabel("Slot sending feature: ");
        slotFeatureHealthRight.setFont(new Font("Whitney", Font.PLAIN, 10));
        slotFeatureHealthRight.setForeground(CustomColors.CHEESE);
        slotFeatureHealthLabel.setFont(
                new Font("Whitney", Font.PLAIN + Font.ITALIC, 10)
        );
        slotSendingHealthPanel.add(slotFeatureHealthRight, BorderLayout.WEST);
        slotSendingHealthPanel.add(slotFeatureHealthLabel, BorderLayout.EAST);

        healthPanel.add(slotSendingHealthPanel);
        healthPanel.add(createSlotEnhancementTogglePanel());
        return healthPanel;
    }

    private JPanel createSignOutButton() {
        JPanel signoutButtonWrapper = new JPanel();
        JLabel signOutButton = new JLabel("Sign Out", JLabel.CENTER);
        signOutButton.setBorder(new EmptyBorder(10, 10, 10, 10));
        signOutButton.setFont(new Font("Whitney", Font.BOLD, 12));
        signOutButton.setBackground(CustomColors.TOMATO);
        signOutButton.setOpaque(true);
        Runnable r = showLoggedOutPanel;
        signOutButton.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        int result = JOptionPane.showOptionDialog(
                                signOutButton,
                                "Signing out will require you to re-enter the token\ngiven to you by the Flopper discord bot",
                                "Are you sure?",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE,
                                null,
                                new String[] { "Yes", "No" },
                                "No"
                        );

                        if (result == JOptionPane.YES_OPTION) {
                            plugin.getDataHandler().getAccountWideData().setJwt(null);
                            plugin
                                    .getDataHandler()
                                    .markDataAsHavingChanged(FlippingPlugin.ACCOUNT_WIDE);
                            plugin.getApiAuthHandler().setHasValidJWT(false);
                            r.run();
                        }
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        signOutButton.setBackground(CustomColors.TOMATO.brighter());
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        signOutButton.setBackground(CustomColors.TOMATO);
                    }
                }
        );

        signoutButtonWrapper.add(signOutButton);
        return signoutButtonWrapper;
    }

    /**
     * Switches the panel to show a login error message and a retry option.
     * This should be called when validating the JWT fails.
     */
    public void showLoginError() {
        SwingUtilities.invokeLater(
                () -> {
                    removeAll();
                    add(createTitle(), BorderLayout.NORTH);
                    add(createSignOutButton(), BorderLayout.SOUTH);
                    add(createErrorPanel(), BorderLayout.CENTER);
                    revalidate();
                    repaint();
                }
        );
    }

    /**
     * Shows the health of the features that rely on the api (slot sending) alongside the
     * slot enhancement toggle.
     */
    public void showApiFeaturesHealth() {
        SwingUtilities.invokeLater(
                () -> {
                    removeAll();
                    add(createTitle(), BorderLayout.NORTH);
                    add(createSignOutButton(), BorderLayout.SOUTH);
                    add(createApiFeaturesPanel(), BorderLayout.CENTER);
                    revalidate();
                    repaint();
                }
        );
    }

    private JPanel createSlotEnhancementTogglePanel() {
        JLabel toggleLabel = new JLabel("Slot enhancement");
        toggleLabel.setFont(new Font("Whitney", Font.PLAIN, 10));
        toggleLabel.setForeground(CustomColors.CHEESE);

        JToggleButton toggleButton = UIUtilities.createToggleButton();
        toggleButton.setSelected(plugin.shouldEnhanceSlots());
        toggleButton.addItemListener(
                i -> plugin.toggleEnhancedSlots(toggleButton.isSelected())
        );

        JPanel toggleSlotEnhancementPanel = new JPanel(new BorderLayout());
        toggleSlotEnhancementPanel.add(toggleLabel, BorderLayout.WEST);
        toggleSlotEnhancementPanel.add(toggleButton, BorderLayout.EAST);
        return toggleSlotEnhancementPanel;
    }

    public void setSlotFeatureHealthText(String text) {
        slotFeatureHealthLabel.setText(text);
    }
}
