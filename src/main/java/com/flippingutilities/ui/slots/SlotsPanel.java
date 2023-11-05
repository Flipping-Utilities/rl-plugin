package com.flippingutilities.ui.slots;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.QuickLookPanel;
import com.flippingutilities.utilities.WikiRequest;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Container that holds the SlotPanels which contain the visual representation of the ge slots.
 * The SlotPanels are only created once. Their visibility and contents are changed as updates come in.
 */
@Slf4j
public class SlotsPanel extends JPanel {
    FlippingPlugin plugin;
    private List<SlotPanel> slotPanels;
    private ItemManager itemManager;
    JLabel statusText = new JLabel();
    JPopupMenu popup = new JPopupMenu();
    QuickLookPanel quickLookPanel = new QuickLookPanel();

    public SlotsPanel(FlippingPlugin plugin, ItemManager im) {
        popup.add(quickLookPanel);
        this.plugin = plugin;
        itemManager = im;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        slotPanels = new ArrayList<>();
        JPanel slotPanelsContainer = new JPanel();
        slotPanelsContainer.setLayout(new BoxLayout(slotPanelsContainer, BoxLayout.Y_AXIS));

        for (int i = 0; i < 8; i++) {
            Component verticalGap = Box.createVerticalStrut(10);
            SlotPanel slotPanel = new SlotPanel(plugin, verticalGap, popup, quickLookPanel);
            slotPanels.add(slotPanel);
            slotPanelsContainer.add(slotPanel);
            slotPanelsContainer.add(verticalGap);
        }

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new EmptyBorder(10,10,10,10));
        wrapper.add(slotPanelsContainer, BorderLayout.NORTH);

        JScrollPane jScrollPane = new JScrollPane(wrapper);
        jScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(5, 0));

        statusText.setAlignmentX(JLabel.CENTER_ALIGNMENT);
        statusText.setBorder(new EmptyBorder(20,0,0,0));
        statusText.setFont(FontManager.getRunescapeSmallFont());
        statusText.setText("No currently active slots");
        add(statusText, BorderLayout.NORTH);
        add(jScrollPane, BorderLayout.CENTER);
    }

    public void updateTimerDisplays(int slotIndex, String timeString) {
        slotPanels.get(slotIndex).updateTimer(timeString);
    }

    public void onWikiRequest(WikiRequest wikiRequest) {
        for (SlotPanel slotPanel: slotPanels) {
            slotPanel.onWikiRequest(wikiRequest);
        }
    }

    public void update(OfferEvent newOfferEvent) {
        int slot = newOfferEvent.getSlot();
        SlotPanel slotPanel = slotPanels.get(slot);
        //only update if there is some actual change in the slot
        if (slotPanel.shouldNotUpdate(newOfferEvent)) {
            return;
        }

        slotPanel.offerEvent = newOfferEvent;

        if (newOfferEvent.isCausedByEmptySlot()) {
            slotPanel.reset();
        }
        //no need to create image if slot still has same item in it
        else if (slotPanel.itemId == newOfferEvent.getItemId()) {
            slotPanel.update(null, null, newOfferEvent);
        } else {
            ItemComposition itemComposition = itemManager.getItemComposition(newOfferEvent.getItemId());
            boolean shouldStack = itemComposition.isStackable() || newOfferEvent.getTotalQuantityInTrade() > 1;
            BufferedImage itemImage = itemManager.getImage(newOfferEvent.getItemId(), newOfferEvent.getTotalQuantityInTrade(), shouldStack);
            String itemName = itemComposition.getName();
            slotPanel.update(itemImage, itemName, newOfferEvent);
        }
        boolean activeSlots = slotPanels.stream().anyMatch(s -> s.itemId != 0);
        statusText.setVisible(!activeSlots);
    }
}
