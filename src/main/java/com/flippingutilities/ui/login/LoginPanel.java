package com.flippingutilities.ui.login;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.components.IconTextField;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LoginPanel extends JPanel{
    FlippingPlugin plugin;
    Runnable onViewChange;
    public boolean showingLoggedInView;
    JLabel slotFeatureHealthLabel = new JLabel("Inactive (not logged in)");
    Instant timeOfLastSuccessfulRequest;
    boolean errorWhenSendingSlotRequest;
    boolean notSendingRequestDueToNoChange;

    public LoginPanel(FlippingPlugin plugin) {
        this.plugin = plugin;
        plugin.getApiAuthHandler().subscribeToLogin(this::showLoggedInView);
        add(createLoggedOutPanel());
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::checkHealth, 5, 1, TimeUnit.SECONDS);
    }

    public void addOnViewChange(Runnable r) {
        this.onViewChange = r;
    }

    private void checkHealth() {
        SwingUtilities.invokeLater(() -> {
            if (plugin.getCurrentlyLoggedInAccount() == null) {
                timeOfLastSuccessfulRequest = null;
                slotFeatureHealthLabel.setText("Inactive (not logged in)");
            }
            else if (!plugin.getApiAuthHandler().canCommunicateWithApi(plugin.getCurrentlyLoggedInAccount())) {
                slotFeatureHealthLabel.setText("Inactive (not registered yet)");
            }
            else if (timeOfLastSuccessfulRequest == null) {
                slotFeatureHealthLabel.setText("Active (starting up)");
            }
            else if (notSendingRequestDueToNoChange) {
                slotFeatureHealthLabel.setText("Active (no slot change detected)");
            }
            else if (errorWhenSendingSlotRequest) {
                slotFeatureHealthLabel.setText("Inactive (error on slot update)");
            }
            else {
                long diff = Instant.now().getEpochSecond() - timeOfLastSuccessfulRequest.getEpochSecond();
                slotFeatureHealthLabel.setText(String.format("Active (%ds since last update)", diff));
            }
            revalidate();
            repaint();
        });
    }

    public void onSlotRequest(int success) {
        if (success == 0) {
            notSendingRequestDueToNoChange = true;
        }
        else if (success == 1) {
            timeOfLastSuccessfulRequest = Instant.now();
            errorWhenSendingSlotRequest = false;
            notSendingRequestDueToNoChange = false;
        }
        else {
            errorWhenSendingSlotRequest = true;
            notSendingRequestDueToNoChange = false;
        }
    }

    public void showLoggedInView() {
        SwingUtilities.invokeLater(() -> {
            removeAll();
            add(createLoggedInPanel());
            revalidate();
            repaint();
            if (this.onViewChange != null) {
                this.onViewChange.run();
            }
            showingLoggedInView = true;
        });
    }

    public void showLoggedOutView() {
        SwingUtilities.invokeLater(() -> {
            removeAll();
            add(createLoggedOutPanel());
            revalidate();
            repaint();
            if (this.onViewChange != null) {
                this.onViewChange.run();
            }
        });
    }

    private JPanel createLoggedInPanel() {
        JPanel loggedInPanel = new JPanel(new BorderLayout());
        loggedInPanel.setBorder(new EmptyBorder(10,20,20,20));
        loggedInPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel headerLabel = new JLabel("Health", JLabel.CENTER);
        headerLabel.setFont(new Font("Whitney", Font.PLAIN + Font.BOLD, 16));
        headerLabel.setForeground(CustomColors.CHEESE);

        JPanel healthPanel = new JPanel(new BorderLayout());
        healthPanel.setBorder(new EmptyBorder(10,0,0,0));

        JPanel slotSendingHealthPanel = new JPanel(new BorderLayout());
        JLabel slotFeatureHealthRight = new JLabel("Slot sending feature: ");
        slotFeatureHealthRight.setFont(new Font("Whitney", Font.PLAIN, 10));
        slotFeatureHealthRight.setForeground(CustomColors.CHEESE);
        slotFeatureHealthLabel.setFont(new Font("Whitney", Font.PLAIN + Font.ITALIC, 10));

        slotSendingHealthPanel.add(slotFeatureHealthRight, BorderLayout.WEST);
        slotSendingHealthPanel.add(slotFeatureHealthLabel, BorderLayout.EAST);

        healthPanel.add(slotSendingHealthPanel, BorderLayout.CENTER);

        loggedInPanel.add(headerLabel, BorderLayout.NORTH);
        loggedInPanel.add(healthPanel, BorderLayout.CENTER);
        return loggedInPanel;
    }

    private JPanel createLoggedOutPanel() {
        JPanel loggedOutPanel = new JPanel(new BorderLayout());
        loggedOutPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        loggedOutPanel.setBorder(new EmptyBorder(20,40,20,25));

        loggedOutPanel.add(createFeaturesPanel(), BorderLayout.WEST);
        loggedOutPanel.add(createTokenPanel(), BorderLayout.CENTER);
        loggedOutPanel.add(createLoginInstructionsPanel(), BorderLayout.EAST);
        return loggedOutPanel;
    }

    private JPanel createFeaturesPanel() {
        JPanel featuresPanel = new JPanel(new BorderLayout());
        featuresPanel.setBorder(new EmptyBorder(0,0,0,20));

        JLabel flashIcon = new JLabel(Icons.FLASH, JLabel.CENTER);

        JLabel headingText = new JLabel("<html>LOGIN TO...</html>");
        headingText.setFont(new Font("Whitney", Font.BOLD, 14));
        headingText.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        headingText.setBorder(new EmptyBorder(0,0,4,0));

        JPanel featuresListPanel = new JPanel(new DynamicGridLayout(4, 0, 0, 0));

        JPanel firstFeaturePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel firstFeatureDesc = new JLabel(String.format("<html><div WIDTH=%d>%s</div></html>", 180, "Get discord DMs when offers complete or you are undercut, even when logged out of rs!"));
        firstFeatureDesc.setFont(new Font("Whitney", Font.PLAIN, 12));
        firstFeatureDesc.setForeground(CustomColors.SOFT_ALCH);
        firstFeaturePanel.add(firstFeatureDesc);

        JPanel secondFeaturePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel secondFeatureDesc = new JLabel(String.format("<html><div WIDTH=%d>%s</div></html>", 180, "View the current state of your offers on discord!"));
        secondFeatureDesc.setFont(new Font("Whitney", Font.PLAIN, 12));
        secondFeatureDesc.setForeground(CustomColors.SOFT_ALCH);
        secondFeaturePanel.add(secondFeatureDesc);

        JLabel bottomText = new JLabel("<html>More features are in development!</html>");
        bottomText.setFont(new Font("Whitney", Font.BOLD + Font.ITALIC, 10));
        bottomText.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        bottomText.setBorder(new EmptyBorder(25,0,0,0));

        featuresListPanel.add(headingText);
        featuresListPanel.add(firstFeaturePanel);
        featuresListPanel.add(secondFeaturePanel);
        featuresListPanel.add(bottomText);

        featuresPanel.add(flashIcon, BorderLayout.NORTH);
        featuresPanel.add(featuresListPanel, BorderLayout.SOUTH);

        return featuresPanel;
    }

    private JPanel createLoginInstructionsPanel() {
        JPanel instructionsPanel = new JPanel(new BorderLayout());
        instructionsPanel.setBorder(new EmptyBorder(0,20,0,0));

        JLabel keyIcon = new JLabel(Icons.KEY, JLabel.CENTER);

        JLabel headingText = new JLabel("<html>GETTING A TOKEN</html>");
        headingText.setFont(new Font("Whitney", Font.BOLD, 14));
        headingText.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        headingText.setBorder(new EmptyBorder(0,0,7,0));

        JPanel firstStepPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel firstStepNumber = new JLabel("<html>1. </html>");
        firstStepNumber.setFont(new Font("Whitney", Font.BOLD, 14));
        firstStepNumber.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        JLabel firstStepDesc = new JLabel("Join our discord", JLabel.LEFT);
        firstStepDesc.setFont(new Font("Whitney", Font.PLAIN, 12));
        firstStepDesc.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        firstStepPanel.add(firstStepNumber);
        firstStepPanel.add(firstStepDesc);
        firstStepPanel.add(UIUtilities.createIcon(Icons.DISCORD_CHEESE, Icons.DISCORD_ICON_ON,"https://discord.gg/GDqVgMH26s","Click to go to Flipping Utilities discord!"));

        JPanel secondStepPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel secondStepDesc = new JLabel("Type !login in the bot channel");
        secondStepDesc.setFont(new Font("Whitney", Font.PLAIN, 12));
        secondStepDesc.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        JLabel secondStepNumber = new JLabel("<html>2. </html>");
        secondStepNumber.setFont(new Font("Whitney", Font.BOLD, 14));
        secondStepNumber.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        secondStepPanel.add(secondStepNumber);
        secondStepPanel.add(secondStepDesc);

        JPanel thirdStepPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel thirdStepNumber = new JLabel("<html>3. </html>");
        thirdStepNumber.setFont(new Font("Whitney", Font.BOLD, 14));
        thirdStepNumber.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        JLabel thirdStepDesc = new JLabel("Enter the token here");
        thirdStepDesc.setFont(new Font("Whitney", Font.PLAIN, 12));
        thirdStepDesc.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        thirdStepPanel.add(thirdStepNumber);
        thirdStepPanel.add(thirdStepDesc);

        JLabel bottomText = new JLabel("<html>You will only have to do this once</html>");
        bottomText.setFont(new Font("Whitney", Font.BOLD + Font.ITALIC, 10));
        bottomText.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        bottomText.setBorder(new EmptyBorder(33,0,0,0));

        JPanel stepsPanel = new JPanel(new DynamicGridLayout(5, 0, 0, 0));
        stepsPanel.add(headingText);
        stepsPanel.add(firstStepPanel);
        stepsPanel.add(secondStepPanel);
        stepsPanel.add(thirdStepPanel);
        stepsPanel.add(bottomText);

        instructionsPanel.add(keyIcon, BorderLayout.NORTH);
        instructionsPanel.add(stepsPanel, BorderLayout.SOUTH);

        return instructionsPanel;
    }

    private JPanel createTokenPanel() {
        JPanel tokenPanel = new JPanel(new BorderLayout());
        tokenPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        tokenPanel.setBorder(new CompoundBorder(
                new MatteBorder(0,1,0,1, ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(0,30,0,30)
        ));

        JPanel header = new JPanel();
        header.setForeground(CustomColors.CHEESE);
        JLabel fuIcon = new JLabel(Icons.FU_ICON, JLabel.CENTER);
        header.add(fuIcon);

        JPanel middlePanel = new JPanel(new BorderLayout());
        middlePanel.setBorder(new EmptyBorder(20,0,20,0));
        middlePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        IconTextField tokenField = new IconTextField();
        tokenField.setBackground(CustomColors.DARK_GRAY);
        tokenField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1,1,1,1, ColorScheme.DARKER_GRAY_COLOR.darker()),
                BorderFactory.createEmptyBorder(10,0,10,0)));
        tokenField.setPreferredSize(new Dimension(170, 40));

        JLabel tokenFieldDescriptor = new JLabel("TOKEN", JLabel.LEFT);
        tokenFieldDescriptor.setFont(new Font("Whitney", Font.BOLD, 12));
        tokenFieldDescriptor.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
        tokenFieldDescriptor.setBorder(new EmptyBorder(0,0,5,0));

        middlePanel.add(tokenFieldDescriptor, BorderLayout.NORTH);
        middlePanel.add(tokenField, BorderLayout.CENTER);

        JPanel loginButtonWrapper = new JPanel();
        loginButtonWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel loginButton = new JLabel("Login", JLabel.CENTER);
        loginButton.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,0,0, ColorScheme.GRAND_EXCHANGE_PRICE.darker()),new EmptyBorder(10,20,10,20))
        );
        loginButton.setFont(new Font("Whitney", Font.BOLD, 12));
        loginButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE);
        loginButton.setOpaque(true);
        loginButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int result = JOptionPane.showOptionDialog(
                        loginButton,
                        "Logging into flipping utilities will submit GE transactions and your IP address\n" +
                                "to flipping utilities, a 3rd party not controlled or verified by the RuneLite developers",
                        "Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                        null, new String[] {"Yes", "No"}, "No");

                if (result == JOptionPane.YES_OPTION)
                {
                    plugin.getApiAuthHandler().loginWithToken(tokenField.getText()).exceptionally((exception) -> {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(loginButton, "Authentication error, contact us on discord for help!", "Authentication error 😔",  JOptionPane.ERROR_MESSAGE));
                        return null;
                    });
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                loginButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE.brighter());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                loginButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE);
            }
        });

        loginButtonWrapper.add(loginButton);

        tokenPanel.add(header, BorderLayout.NORTH);
        tokenPanel.add(middlePanel, BorderLayout.CENTER);
        tokenPanel.add(loginButtonWrapper, BorderLayout.SOUTH);

        return tokenPanel;
    }
}
