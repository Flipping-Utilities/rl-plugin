package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.DataSource;
import com.flippingutilities.model.Timestep;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/** Session-only controls for the shared plugin config and sandbox chart host. */
final class SandboxSettingsPanel extends JPanel {
    SandboxSettingsPanel(SandboxConfig config, boolean browser, Runnable changed) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(16, 16, 16, 16));
        section("Charts");
        toggle("Quick lookup prices", config.quickLookup, value -> config.quickLookup = value, changed);
        toggle("Offer page chart", config.offerChart, value -> config.offerChart = value, changed);
        toggle("Show tax on chart hover", config.tax, value -> config.tax = value, changed);
        JPanel period = row();
        period.add(new JLabel("Default chart period"));
        JComboBox<Timestep> timestep = new JComboBox<>(Timestep.values());
        named(timestep, "Default chart period");
        timestep.setSelectedItem(config.timestep);
        timestep.addActionListener(event -> { config.timestep = (Timestep) timestep.getSelectedItem(); changed.run(); });
        period.add(timestep);
        add(period);
        section("Plugin panels");
        toggle("Verbose item details", config.verbose, value -> config.verbose = value, changed);
        toggle("Account for margin check loss", config.marginLoss, value -> config.marginLoss = value, changed);
        toggle("Profit from remaining GE limit", config.remainingLimit, value -> config.remainingLimit = value, changed);
        toggle("12 hour time format", config.twelveHour, value -> config.twelveHour = value, changed);
        section("Storage");
        JPanel storage = row();
        storage.add(new JLabel("Session backend"));
        JComboBox<DataSource> backend = new JComboBox<>(DataSource.values());
        named(backend, "Session storage backend");
        backend.setSelectedItem(config.dataSource());
        backend.setEnabled(false);
        storage.add(backend);
        add(storage);
        note(browser
            ? "SQLite is supported for import. This browser session converts it to temporary JSON; SQLite writing and maintenance are unavailable."
            : "The backend is chosen when you open the sandbox. Open a SQLite database or a JSON data folder to test that backend.");
        add(Box.createVerticalStrut(12));
        note("Changes apply immediately and last for this session. Your original RuneLite settings are unchanged.");
    }

    private void toggle(String text, boolean selected, Consumer<Boolean> setter, Runnable changed) {
        JCheckBox checkbox = new JCheckBox(text, selected);
        named(checkbox, text);
        checkbox.setAlignmentX(LEFT_ALIGNMENT);
        checkbox.addActionListener(event -> { setter.accept(checkbox.isSelected()); changed.run(); });
        add(checkbox);
    }

    private void section(String text) {
        if (getComponentCount() > 0) add(Box.createVerticalStrut(16));
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setAlignmentX(LEFT_ALIGNMENT);
        add(label);
        add(Box.createVerticalStrut(6));
    }

    private void note(String text) {
        JTextArea note = new JTextArea(text);
        note.setEditable(false);
        note.setFocusable(false);
        note.setOpaque(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setColumns(36);
        note.setRows(3);
        note.setAlignmentX(LEFT_ALIGNMENT);
        add(note);
    }

    private static JPanel row() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        return row;
    }

    private static void named(JComponent component, String name) {
        component.setName(name);
        component.getAccessibleContext().setAccessibleName(name);
    }
}
