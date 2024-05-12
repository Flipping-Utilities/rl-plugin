package com.flippingutilities.ui.assistant;
import com.flippingutilities.controller.FlippingPlugin;
import javax.swing.*;

/**
 * Panel for the flipping assistant feature
 */
public class AssistantPanel extends JPanel {

    FlippingPlugin plugin;
    public SuggestionPanel suggestionPanel;
    public StatePanel statePanel;

    public AssistantPanel(FlippingPlugin plugin) {
        this.plugin = plugin;
        this.suggestionPanel = new SuggestionPanel(plugin);
        this.statePanel = new StatePanel(plugin);

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(suggestionPanel);
        add(statePanel);
    }
}

