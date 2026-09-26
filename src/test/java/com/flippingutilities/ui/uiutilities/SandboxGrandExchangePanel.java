package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.model.OfferEvent;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Interactive, local GE controls for the disposable RuneLite host. All work runs on the EDT. */
final class SandboxGrandExchangePanel extends JPanel implements AutoCloseable {
    private static final Color BUY = new Color(127, 202, 226);
    private static final Color SELL = new Color(233, 180, 113);
    private static final Color MUTED = new Color(181, 181, 181);
    private static final Color ERROR = new Color(255, 158, 145);
    private final SandboxExchange exchange;
    private final JComboBox<String> accounts = new JComboBox<>();
    private final JToggleButton[] slots = new JToggleButton[8];
    private final JComboBox<SandboxExchange.Item> item = new JComboBox<>();
    private final JToggleButton buy = new JToggleButton("Buy", true);
    private final JToggleButton sell = new JToggleButton("Sell");
    private final JSpinner quantity = number(1, 1);
    private final JSpinner price = number(1, 1);
    private final JButton place = new JButton("Place offer");
    private final JLabel selectedTitle = new JLabel();
    private final JLabel selectedInfo = new JLabel();
    private final JProgressBar progress = new JProgressBar();
    private final JSpinner fillPrice = number(0, 0);
    private final JButton applyPrice = new JButton("Apply price");
    private final JSpinner rate = number(0, 0);
    private final JButton applyRate = new JButton("Set rate");
    private final JSpinner chunk = number(1, 1);
    private final JButton fillChunk = new JButton("Fill chunk");
    private final JButton fillRemaining = new JButton("Fill remaining");
    private final JButton cancel = new JButton("Cancel offer");
    private final JButton collect = new JButton("Collect");
    private final JTextArea status = new JTextArea(2, 20);
    private final Timer timer;
    private final NumberFormat numbers = NumberFormat.getIntegerInstance();
    private List<String> accountNames = Collections.emptyList();
    private List<SandboxExchange.Item> items = Collections.emptyList();
    private String shownAccount;
    private int selectedSlot;
    private boolean syncingAccounts;
    private boolean closed;

    SandboxGrandExchangePanel(SandboxExchange exchange, SandboxData data) {
        super(new BorderLayout(0, 12));
        this.exchange = exchange;
        setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setBorder(new EmptyBorder(16, 16, 12, 16));

        JPanel heading = panel(new BorderLayout(0, 8));
        JLabel title = new JLabel("Grand Exchange sandbox");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        heading.add(title, BorderLayout.NORTH);
        identify(accounts, "Simulated account", "Account receiving simulated trades; independent of the sidebar account filter.");
        heading.add(field("Account", accounts), BorderLayout.CENTER);
        JLabel hint = new JLabel("Local offers only. Changes are discarded when you close this window.");
        hint.setForeground(MUTED);
        heading.add(hint, BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);

        JPanel content = new JPanel(new GridBagLayout()) {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                return new Dimension(0, size.height);
            }
        };
        content.setOpaque(false);
        GridBagConstraints layout = new GridBagConstraints();
        layout.gridx = 0;
        layout.weightx = 1;
        layout.fill = GridBagConstraints.HORIZONTAL;
        layout.anchor = GridBagConstraints.NORTH;
        layout.insets = new Insets(0, 0, 12, 0);

        JPanel slotGrid = panel(new GridLayout(0, 4, 6, 6));
        slotGrid.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                GridLayout grid = (GridLayout) slotGrid.getLayout();
                int columns = slotGrid.getWidth() >= 540 ? 4 : 2;
                if (grid.getColumns() != columns) {
                    grid.setColumns(columns);
                    slotGrid.revalidate();
                }
            }
        });
        ButtonGroup slotGroup = new ButtonGroup();
        for (int index = 0; index < slots.length; index++) {
            final int slot = index;
            JToggleButton card = new JToggleButton();
            card.setHorizontalAlignment(SwingConstants.LEFT);
            card.setPreferredSize(new Dimension(130, 82));
            card.setMinimumSize(new Dimension(0, 82));
            card.setFocusPainted(true);
            card.setName("Slot " + (index + 1));
            card.addActionListener(event -> {
                selectedSlot = slot;
                syncOfferInputs();
                refresh();
            });
            slots[index] = card;
            slotGroup.add(card);
            slotGrid.add(card);
        }
        slots[0].setSelected(true);
        layout.gridy = 0;
        content.add(slotGrid, layout);
        layout.gridy++;
        content.add(newOfferPanel(), layout);
        layout.gridy++;
        content.add(selectedOfferPanel(), layout);
        layout.gridy++;
        layout.weighty = 1;
        content.add(panel(new BorderLayout()), layout);

        JScrollPane scroll = new JScrollPane(content,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(getBackground());
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        add(scroll, BorderLayout.CENTER);

        JPanel footer = panel(new BorderLayout(0, 6));
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        identify(status, "Exchange status", "Result of the last simulation action.");
        footer.add(status, BorderLayout.NORTH);
        JPanel paths = panel(new GridLayout(2, 1, 0, 2));
        paths.add(pathLabel("Source", data.getSource().toString()));
        paths.add(pathLabel("Temporary copy", data.getRuneLiteDirectory().toString()));
        footer.add(paths, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        accounts.addActionListener(event -> {
            if (syncingAccounts || accounts.getSelectedItem() == null) return;
            String account = (String) accounts.getSelectedItem();
            if (account.equals(exchange.account())) return;
            perform(() -> {
                pauseOffers();
                exchange.selectAccount(account);
            }, "Selected " + account + ". Offers are paused; set a rate or fill a chunk to continue.");
        });
        place.addActionListener(event -> perform(() -> exchange.place(selectedSlot, buy.isSelected(),
            selectedItemId(), value(quantity), value(price)), "Offer placed. Set a rate or fill it manually."));
        applyPrice.addActionListener(event -> perform(() -> exchange.setFillPrice(selectedSlot, value(fillPrice)),
            "Fill price updated. Future fills use this price."));
        applyRate.addActionListener(event -> perform(() -> exchange.setRate(selectedSlot, value(rate)),
            "Fill rate updated. A rate of 0 pauses the offer."));
        fillChunk.addActionListener(event -> perform(() -> exchange.fill(selectedSlot, value(chunk)), "Chunk filled."));
        fillRemaining.addActionListener(event -> perform(() -> {
            OfferEvent offer = exchange.offer(selectedSlot);
            if (offer == null) throw new IllegalStateException("Select an active offer first.");
            exchange.fill(selectedSlot, offer.getTotalQuantityInTrade() - offer.getCurrentQuantityInTrade());
        }, "Remaining quantity filled. Collect the offer to reuse its slot."));
        cancel.addActionListener(event -> perform(() -> exchange.cancel(selectedSlot), "Offer canceled. Collect it to reuse its slot."));
        collect.addActionListener(event -> perform(() -> exchange.collect(selectedSlot), "Offer collected. The slot is available."));

        timer = new Timer(1000, event -> {
            if (closed) return;
            try {
                exchange.advanceSecond();
                refresh();
            } catch (RuntimeException error) {
                timerFailure(error);
            }
        });
        refresh();
        syncOfferInputs();
        message("Select a slot to place an offer. Loaded offers start paused.", false);
        timer.start();
    }

    private JPanel newOfferPanel() {
        JPanel panel = panel(new BorderLayout(0, 8));
        JLabel title = new JLabel("New offer");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        panel.add(title, BorderLayout.NORTH);
        item.setEditable(true);
        item.setPreferredSize(new Dimension(240, 28));
        item.setMinimumSize(new Dimension(0, 28));
        identify(item, "Offer item", "Choose a saved or common item, or type a numeric item ID.");
        item.getEditor().getEditorComponent().getAccessibleContext().setAccessibleName("Offer item ID or name");
        ButtonGroup side = new ButtonGroup();
        side.add(buy);
        side.add(sell);
        buy.setForeground(BUY);
        sell.setForeground(SELL);
        identify(buy, "Buy offer", "Place a buy offer in the selected empty slot.");
        identify(sell, "Sell offer", "Place a sell offer in the selected empty slot.");
        JPanel direction = panel(new GridLayout(1, 2, 4, 0));
        direction.add(buy);
        direction.add(sell);
        JPanel itemRow = panel(new BorderLayout(8, 0));
        itemRow.add(field("Item / ID", item), BorderLayout.CENTER);
        itemRow.add(direction, BorderLayout.EAST);
        JPanel inputs = panel(new BorderLayout(0, 8));
        inputs.add(itemRow, BorderLayout.NORTH);
        identify(quantity, "Offer quantity", "Total number of items in the new offer.");
        identify(price, "Offer unit price", "Initial price per item in gold pieces.");
        identify(place, "Place offer", "Place the offer in the selected empty slot.");
        JPanel amounts = panel(new GridLayout(1, 3, 8, 0));
        amounts.add(field("Quantity", quantity));
        amounts.add(field("Price (gp)", price));
        amounts.add(place);
        inputs.add(amounts, BorderLayout.SOUTH);
        panel.add(inputs, BorderLayout.CENTER);
        return panel;
    }

    private JPanel selectedOfferPanel() {
        JPanel panel = panel(new BorderLayout(0, 8));
        selectedTitle.setFont(selectedTitle.getFont().deriveFont(Font.BOLD));
        selectedInfo.setForeground(MUTED);
        JPanel summary = panel(new GridLayout(3, 1, 0, 4));
        summary.add(selectedTitle);
        summary.add(selectedInfo);
        progress.setStringPainted(true);
        progress.getAccessibleContext().setAccessibleName("Selected offer progress");
        summary.add(progress);
        panel.add(summary, BorderLayout.NORTH);

        identify(fillPrice, "Fill price", "Gold pieces per item for future fills. Set a positive price if a loaded offer has no saved price.");
        identify(applyPrice, "Apply fill price", "Use this price for future fills of the selected offer.");
        identify(rate, "Items per second", "Number of items to fill each second. Zero pauses the offer.");
        identify(applyRate, "Set fill rate", "Apply the selected offer's automatic fill rate.");
        JPanel automatic = panel(new GridLayout(2, 1, 0, 6));
        JPanel priceRow = field("Fill price (gp/item)", fillPrice);
        priceRow.add(applyPrice, BorderLayout.EAST);
        JPanel rateRow = field("Items / second", rate);
        rateRow.add(applyRate, BorderLayout.EAST);
        automatic.add(priceRow);
        automatic.add(rateRow);

        identify(chunk, "Fill chunk quantity", "Number of items to fill immediately.");
        identify(fillChunk, "Fill chunk", "Immediately fill the specified number of items.");
        identify(fillRemaining, "Fill remaining", "Immediately fill all remaining items in the selected offer.");
        identify(cancel, "Cancel offer", "Cancel the unfilled part of the selected offer.");
        identify(collect, "Collect offer", "Clear a completed or canceled offer so the slot can be reused.");
        JPanel manual = panel(new GridLayout(1, 3, 8, 0));
        manual.add(field("Chunk", chunk));
        manual.add(fillChunk);
        manual.add(fillRemaining);
        JPanel finish = panel(new GridLayout(1, 2, 8, 0));
        finish.add(cancel);
        finish.add(collect);
        JPanel controls = panel(new BorderLayout(0, 8));
        controls.add(automatic, BorderLayout.NORTH);
        controls.add(manual, BorderLayout.CENTER);
        controls.add(finish, BorderLayout.SOUTH);
        panel.add(controls, BorderLayout.CENTER);
        return panel;
    }

    private void refresh() {
        syncAccounts();
        for (int index = 0; index < slots.length; index++) {
            OfferEvent offer = exchange.offer(index);
            String side = offer == null ? "Empty" : offer.isBuy() ? "Buy" : "Sell";
            String name = offer == null ? "Ready for an offer" : itemName(offer);
            String state = offer == null ? "Choose this slot" : numbers.format(offer.getCurrentQuantityInTrade())
                + " / " + numbers.format(offer.getTotalQuantityInTrade()) + " · " + offerState(offer, index);
            String cardProgress = offer == null ? "Choose this slot" : compact(offer.getCurrentQuantityInTrade())
                + " / " + compact(offer.getTotalQuantityInTrade());
            String cardState = offer == null ? "&nbsp;" : offer.isCancelled() ? "Canceled" : offer.isComplete() ? "Complete"
                : exchange.rate(index) == 0 ? "Paused" : compact(exchange.rate(index)) + " / sec";
            slots[index].setText("<html><b>Slot " + (index + 1) + " · " + side + "</b><br>"
                + escape(shorten(name, 18)) + "<br>" + escape(cardProgress) + "<br>" + cardState + "</html>");
            slots[index].setForeground(offer == null ? MUTED : offer.isBuy() ? BUY : SELL);
            slots[index].setBackground(ColorScheme.DARK_GRAY_COLOR);
            slots[index].setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(
                index == selectedSlot ? new Color(223, 190, 111) : ColorScheme.MEDIUM_GRAY_COLOR, 2),
                new EmptyBorder(4, 8, 4, 8)));
            slots[index].setToolTipText("Slot " + (index + 1) + ": " + side + " " + name + ", " + state);
            slots[index].getAccessibleContext().setAccessibleName(slots[index].getToolTipText());
        }
        OfferEvent offer = exchange.offer(selectedSlot);
        boolean active = offer != null && !offer.isComplete();
        boolean available = offer == null && exchange.account() != null;
        selectedTitle.setText("Slot " + (selectedSlot + 1) + " · " + (offer == null ? "Empty" : offerState(offer, selectedSlot)));
        selectedInfo.setText(offer == null ? "Choose an item above to place a buy or sell offer."
            : shorten(itemName(offer), 35) + " · Fill price: " + (exchange.fillPrice(selectedSlot) == 0
                ? "set a price below" : numbers.format(exchange.fillPrice(selectedSlot)) + " gp/item"));
        selectedInfo.setToolTipText(offer == null ? null : itemName(offer));
        progress.setMaximum(offer == null ? 1 : Math.max(1, offer.getTotalQuantityInTrade()));
        progress.setValue(offer == null ? 0 : offer.getCurrentQuantityInTrade());
        progress.setString(offer == null ? "No offer" : numbers.format(offer.getCurrentQuantityInTrade())
            + " / " + numbers.format(offer.getTotalQuantityInTrade()) + " filled");
        progress.setForeground(offer != null && !offer.isBuy() ? SELL : BUY);
        for (JComponent control : new JComponent[]{item, buy, sell, quantity, price, place}) control.setEnabled(available);
        for (JComponent control : new JComponent[]{fillPrice, applyPrice, rate, applyRate, chunk, fillChunk, fillRemaining, cancel}) {
            control.setEnabled(active);
        }
        collect.setEnabled(offer != null && offer.isComplete());
    }

    private void syncAccounts() {
        List<String> latest = exchange.accounts();
        String account = exchange.account();
        boolean changed = !java.util.Objects.equals(account, shownAccount);
        syncingAccounts = true;
        try {
            if (!latest.equals(accountNames)) {
                accountNames = new ArrayList<>(latest);
                accounts.setModel(new DefaultComboBoxModel<>(latest.toArray(new String[0])));
            }
            accounts.setSelectedItem(account);
            accounts.setEnabled(!latest.isEmpty());
        } finally {
            syncingAccounts = false;
        }
        if (changed || items.isEmpty()) {
            shownAccount = account;
            items = new ArrayList<>(exchange.items());
            Object previous = item.getEditor().getItem();
            item.setModel(new DefaultComboBoxModel<>(items.toArray(new SandboxExchange.Item[0])));
            if (previous != null && !previous.toString().trim().isEmpty()) item.getEditor().setItem(previous);
            syncOfferInputs();
        }
    }

    private void syncOfferInputs() {
        OfferEvent offer = exchange.offer(selectedSlot);
        rate.setValue(offer == null ? 0 : exchange.rate(selectedSlot));
        fillPrice.setValue(offer == null ? 0 : exchange.fillPrice(selectedSlot));
    }

    private int selectedItemId() {
        Object selected = item.getEditor().getItem();
        if (selected instanceof SandboxExchange.Item) return ((SandboxExchange.Item) selected).id;
        String text = selected == null ? "" : selected.toString().trim();
        for (SandboxExchange.Item candidate : items) {
            if (text.equalsIgnoreCase(candidate.name) || text.equalsIgnoreCase(candidate.toString())) return candidate.id;
        }
        try {
            int id = Integer.parseInt(text);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Choose an item or enter a positive numeric item ID.");
    }

    private String itemName(OfferEvent offer) {
        if (offer.getItemName() != null && !offer.getItemName().trim().isEmpty()) return offer.getItemName();
        for (SandboxExchange.Item candidate : items) if (candidate.id == offer.getItemId()) return candidate.name;
        return "Item " + offer.getItemId();
    }

    private String offerState(OfferEvent offer, int slot) {
        if (offer.isCancelled()) return "Canceled · collect";
        if (offer.isComplete()) return "Complete · collect";
        int speed = exchange.rate(slot);
        return speed == 0 ? "Paused" : numbers.format(speed) + " / sec";
    }

    private void perform(Runnable action, String success) {
        if (closed) return;
        try {
            action.run();
            refresh();
            syncOfferInputs();
            message(success, false);
            if (!timer.isRunning()) timer.start();
        } catch (IllegalArgumentException | IllegalStateException error) {
            message(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), true);
        } catch (RuntimeException error) {
            timerFailure(error);
        }
    }

    private void pauseOffers() {
        RuntimeException failure = null;
        for (int slot = 0; slot < slots.length; slot++) {
            try { exchange.setRate(slot, 0); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private void timerFailure(RuntimeException failure) {
        timer.stop();
        try { pauseOffers(); }
        catch (RuntimeException pauseFailure) { failure.addSuppressed(pauseFailure); }
        try { refresh(); }
        catch (RuntimeException refreshFailure) { failure.addSuppressed(refreshFailure); }
        message("Automatic fills paused: " + (failure.getMessage() == null
            ? failure.getClass().getSimpleName() : failure.getMessage()), true);
    }

    private void message(String text, boolean error) {
        status.setForeground(error ? ERROR : MUTED);
        status.setText(text);
        status.setCaretPosition(0);
        status.setToolTipText(text);
    }

    private static JSpinner number(int initial, int minimum) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, minimum, Integer.MAX_VALUE, 1));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#,##0"));
        spinner.setMinimumSize(new Dimension(65, 26));
        return spinner;
    }

    private static int value(JSpinner spinner) {
        try { spinner.commitEdit(); }
        catch (ParseException error) { throw new IllegalArgumentException("Enter a whole number for "
            + spinner.getAccessibleContext().getAccessibleName().toLowerCase() + "."); }
        return ((Number) spinner.getValue()).intValue();
    }

    private static JPanel field(String text, JComponent input) {
        JPanel row = panel(new BorderLayout(6, 0));
        JLabel label = new JLabel(text);
        label.setLabelFor(input);
        row.add(label, BorderLayout.WEST);
        row.add(input, BorderLayout.CENTER);
        return row;
    }

    private static JPanel panel(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static void identify(JComponent component, String name, String tooltip) {
        component.setName(name);
        component.getAccessibleContext().setAccessibleName(name);
        component.setToolTipText(tooltip);
    }

    private static JLabel pathLabel(String title, String path) {
        JLabel label = new JLabel(title + ": " + shorten(path, 75));
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(11f));
        label.setToolTipText(path);
        label.getAccessibleContext().setAccessibleName(title + ": " + path);
        return label;
    }

    private static String shorten(String text, int maximum) {
        return text.length() <= maximum ? text : text.substring(0, maximum - 1) + "…";
    }

    private String compact(int value) {
        if (value < 1000) return numbers.format(value);
        if (value < 1000000) return String.format(java.util.Locale.ROOT, "%.1fk", value / 1000.0);
        if (value < 1000000000) return String.format(java.util.Locale.ROOT, "%.1fm", value / 1000000.0);
        return String.format(java.util.Locale.ROOT, "%.1fb", value / 1000000000.0);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override public void close() {
        closed = true;
        timer.stop();
    }
}
