package com.flippingutilities.ui.assistant;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.Suggestion;
import com.flippingutilities.utilities.Constants;
import lombok.Setter;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;

public class SuggestionPanel extends JPanel {
    private FlippingPlugin plugin;
    private final JLabel suggestionText = new JLabel();
    private final JLabel skipButton = new JLabel("skip");

    @Setter
    private String serverMessage = "";

    public SuggestionPanel(FlippingPlugin plugin) {
        this.plugin = plugin;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(0, 135));

        JLabel title = new JLabel("<html><center> <FONT COLOR=white><b>Suggested Action:" +
            "</b></FONT></center></html>");
        title.setHorizontalAlignment(SwingConstants.CENTER);
        add(title, BorderLayout.NORTH);

        JPanel suggestionContainer = new JPanel();
        suggestionContainer.setLayout(new CardLayout());
        suggestionContainer.setOpaque(true);
        suggestionContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        suggestionContainer.setPreferredSize(new Dimension(0, 85));
        add(suggestionContainer, BorderLayout.CENTER);

        suggestionText.setHorizontalAlignment(SwingConstants.CENTER);
        suggestionText.setOpaque(true);
        suggestionText.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        suggestionContainer.add(suggestionText);
        setupSkipButton();
        suggestLogin();
    }

    private void setupSkipButton() {
        skipButton.setForeground(Color.GRAY);
        skipButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                skipButton.setForeground(Color.WHITE);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                skipButton.setForeground(Color.GRAY);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                showLoading();
                plugin.suggestionHandler.skipCurrentSuggestion();
            }
        });

        skipButton.setHorizontalAlignment(SwingConstants.RIGHT);
        add(skipButton, BorderLayout.SOUTH);
    }


    public void updateSuggestion(Suggestion suggestion) {
        NumberFormat formatter = NumberFormat.getNumberInstance();
        String suggestionString = "<html><center>";

        switch (suggestion.getType()) {
            case "wait":
                suggestionString += "Wait <br>";
                break;
            case "abort":
                suggestionString += "Abort offer for<br><FONT COLOR=white>" + suggestion.getName() + "<br></FONT>";
                break;
            case "buy":
            case "sell":
                String capitalisedAction = suggestion.getType().equals("buy") ? "Buy" : "Sell";
                suggestionString += capitalisedAction +
                    " <FONT COLOR=yellow>" + formatter.format(suggestion.getQuantity()) + "</FONT><br>" +
                    "<FONT COLOR=white>" + suggestion.getName() + "</FONT><br>" +
                    "for <FONT COLOR=yellow>" + formatter.format(suggestion.getPrice()) + "</FONT> gp<br>";
                break;
            default:
                suggestionString += "Error processing suggestion<br>";
        }
        suggestionString += suggestion.getMessage();
        suggestionString += "</center><html>";
        suggestionText.setText(suggestionString);
        suggestionText.setVisible(true);
        if(!suggestion.getType().equals("wait")) {
            skipButton.setVisible(true);
        }
    }

    public void suggestCollect() {
        setMessage("Collect items");
        skipButton.setVisible(false);
    }

    public void suggestAddGp() {
        NumberFormat formatter = NumberFormat.getNumberInstance();
        setMessage("Add at least <FONT COLOR=yellow>" + formatter.format(Constants.MIN_GP_NEEDED_TO_FLIP)
            + "</FONT> gp<br>to your inventory<br>"
            + "to get a flip suggestion");
    }

    public void suggestLogin() {
        setMessage("Log in to the game<br>to get a flip suggestion");
        skipButton.setVisible(false);
    }

    public void showConnectionError() {
        setMessage("Failed to connect to server");
        skipButton.setVisible(false);
    }

    public void setMessage(String message) {
        suggestionText.setText("<html><center>" + message +  "<br>" + serverMessage + "</center><html>");
        skipButton.setVisible(false);
    }

    public void showLoading() {
        suggestionText.setVisible(false);
        setServerMessage("");
        skipButton.setVisible(false);
    }

    public void hideLoading() {
        suggestionText.setVisible(true);
    }
}
