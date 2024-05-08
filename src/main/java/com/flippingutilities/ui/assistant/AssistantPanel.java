package com.flippingutilities.ui.assistant;
import com.flippingutilities.controller.FlippingPlugin;
import javax.swing.*;

/**
 * Panel for the flipping assistant feature
 */
public class AssistantPanel extends JPanel {

    FlippingPlugin plugin;
    public SuggestionPanel suggestionPanel;


    public AssistantPanel(FlippingPlugin plugin) {
        this.plugin = plugin;
        this.suggestionPanel = new SuggestionPanel(plugin);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(suggestionPanel);
    }
}

