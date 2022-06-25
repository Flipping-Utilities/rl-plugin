package com.flippingutilities.ui.widgets;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.GeSpriteLoader;
import com.flippingutilities.ui.uiutilities.TimeFormatters;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.FontID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.SpriteID;
import net.runelite.api.widgets.*;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;
import org.apache.commons.lang3.tuple.Pair;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;

/**
 * This class is responsible for enhancing slots in the ge interface. It
 * 1. adds color to slots to mark the offers as competitive or not
 * 2. adds a widget that shows some preview info on the competitiveness of the offer
 * 3. adds a widget to show how long the offer has been competitive since the last time
 *    it was updated.
 *
 * This class requires three pieces/types of data for drawing on/enhancing the slots.
 * 1. remote slots from the server
 * 2. wiki margins
 * 3. actual slot widgets
 *
 * It is fed this data by the plugin (sourcing it from other components like the jobs). Everytime
 * it is fed new data, it builds a representation of what it should draw (see getCombinedSlotStates),
 * and then draws it on the slot (see drawWrapper)
 */
@Slf4j
public class SlotStateDrawer {
    List<RemoteAccountSlots> remoteAccountSlots = new ArrayList<>();
    FlippingPlugin plugin;
    WikiRequest wikiRequest;
    Widget[] slotWidgets;
    Map<Integer, Widget> slotIdxToTimeInRangeWidget = new HashMap<>();
    Map<Integer, Widget> slotIdxToQuickLookWidget = new HashMap<>();
    JPopupMenu popup = new JPopupMenu();
    QuickLookPanel quickLookPanel = new QuickLookPanel();
    List<Optional<RemoteSlot>> unifiedSlotStates = new ArrayList<>();

    public SlotStateDrawer(FlippingPlugin plugin) {
        this.plugin = plugin;
        popup.add(quickLookPanel);
    }

    public void setRemoteAccountSlots(List<RemoteAccountSlots> remoteAccountSlots) {
        this.remoteAccountSlots = remoteAccountSlots;
        drawWrapper();
    }

    public void setWikiRequest(WikiRequest wikiRequest) {
        this.wikiRequest = wikiRequest;
        drawWrapper();
    }

    public void setSlotWidgets(Widget[] slotWidgets) {
        if (slotWidgets == null) {
            return;
        }
        this.slotWidgets = slotWidgets;
        drawWrapper();
    }

    /**
     * Thin wrapper around draw to decide if drawing should take place.
     */
    private void drawWrapper() {
        if (
            slotWidgets == null ||
            plugin.getCurrentlyLoggedInAccount() == null ||
            !plugin.getApiAuthHandler().isPremium() ||
            !plugin.shouldEnhanceSlots()
        ) {
            return;
        }
        this.unifiedSlotStates = getCombinedSlotStates();
        plugin.getClientThread().invokeLater(() -> draw(unifiedSlotStates));
    }

    /**
     * Draws the enhancements on the slot
     */
    private void draw(List<Optional<RemoteSlot>> unifiedSlots) {
        for (int i = 0; i < unifiedSlots.size(); i++) {
            Optional<RemoteSlot> maybeUnifiedSlot = unifiedSlots.get(i);
            Widget slotWidget = slotWidgets[i + 1];
            if (!maybeUnifiedSlot.isPresent()) {
                resetSlot(i, slotWidget);
            } else {
                drawOnSlot(maybeUnifiedSlot.get(), slotWidget);
            }
        }
    }

    public void resetAllSlots() {
        if (slotWidgets == null) {
            return;
        }
        for (int i=0; i< 8; i++) {
            Widget slotWidget = slotWidgets[i + 1];
            resetSlot(i, slotWidget);
        }
    }

    /**
     * Hides all the slot enhancements that were previously drawn on the slot. This is
     * done when the slot was once populated with an offer but is now empty.
     */
    private void resetSlot(int slotIdx, Widget slotWidget) {
        Map<Integer, Integer> spriteIdMap = GeSpriteLoader.CHILDREN_IDX_TO_DEFAULT_SPRITE_ID;
        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            int spriteId = spriteIdMap.get(idx);
            child.setSpriteId(spriteId);
        });

        Widget timeInRangeWidget = slotIdxToTimeInRangeWidget.get(slotIdx);
        if (timeInRangeWidget != null) {
            timeInRangeWidget.setHidden(true);
        }

        Widget quickLookWidget = slotIdxToQuickLookWidget.get(slotIdx);
        if (quickLookWidget != null) {
            quickLookWidget.setHidden(true);
        }
    }

    private void drawOnSlot(RemoteSlot slot, Widget slotWidget) {
        if (slotWidget.isHidden()) {
            return;
        }
        Map<Integer, Integer> spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID;
        if (slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_BLUE_SPRITE_ID;
        } else if (slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_RED_SPRITE_ID;
        } else if (slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            spriteMap = GeSpriteLoader.CHILDREN_IDX_TO_GREEN_SPRITE_ID;
        }

        Map<Integer, Integer> finalSpriteMap = spriteMap;
        GeSpriteLoader.DYNAMIC_CHILDREN_IDXS.forEach(idx -> {
            Widget child = slotWidget.getChild(idx);
            int spriteId = finalSpriteMap.get(idx);
            child.setSpriteId(spriteId);
        });

        long secondsSinceLastUpdate =
            slot.getPredictedState() == SlotPredictedState.IN_RANGE
                ? Instant.now().getEpochSecond() - slot.getLatestPredictedFilledTimestamp()
                : 0;
        long latestTimeInRange = slot.getTimeInRange() + secondsSinceLastUpdate;

        if (latestTimeInRange > 0) {
            addTimeInRangeWidget(slotWidget, slot.getIndex(), latestTimeInRange);
        }
        addQuicklookWidget(slotWidget, slot);
    }

    /**
     * This is the image widget (the magnifying glass widget) that a user can hover over to
     * see some quick details about the competitiveness of their offer.
     */
    private void addQuicklookWidget(Widget slotWidget, RemoteSlot slot) {
        Widget existingQuickLookWidget = slotIdxToQuickLookWidget.get(slot.getIndex());
        if (existingQuickLookWidget == null || !isWidgetStillAttached(existingQuickLookWidget)) {
            Widget quicklookWidget = createQuicklookWidget(slotWidget, slot);
            slotIdxToQuickLookWidget.put(slot.getIndex(), quicklookWidget);
        } else {
            existingQuickLookWidget.setHidden(false);
        }
    }

    /**
     * This is the text widget that shows how long the offer has been competitive since
     * the last time it was updated.
     */
    private void addTimeInRangeWidget(Widget slotWidget, int slotIdx, long latestTimeInRange) {
        Widget existingWidget = slotIdxToTimeInRangeWidget.get(slotIdx);
        if (existingWidget == null || !isWidgetStillAttached(existingWidget)) {
            Widget timeInRangeWidget = createTimeInRangeWidget(slotWidget);
            timeInRangeWidget.setText(TimeFormatters.formatDuration(Duration.ofSeconds(latestTimeInRange)));
            slotIdxToTimeInRangeWidget.put(slotIdx, timeInRangeWidget);
        } else {
            existingWidget.setHidden(false);
            existingWidget.setText(TimeFormatters.formatDuration(Duration.ofSeconds(latestTimeInRange)));
        }
    }

    private Widget createQuicklookWidget(Widget slotWidget, RemoteSlot slot) {
        Widget quickLookWidget = slotWidget.createChild(-1, WidgetType.GRAPHIC);
        quickLookWidget.setFontId(FontID.PLAIN_11);
        quickLookWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        quickLookWidget.setOriginalX(90);
        quickLookWidget.setOriginalY(52);
        quickLookWidget.setSpriteId(SpriteID.BANK_SEARCH);
        quickLookWidget.setWidthMode(WidgetSizeMode.ABSOLUTE);
        quickLookWidget.setOriginalHeight(22);
        quickLookWidget.setOriginalWidth(22);
        quickLookWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
        quickLookWidget.setXTextAlignment(WidgetTextAlignment.LEFT);
        quickLookWidget.setTextShadowed(true);
        quickLookWidget.setHasListener(true);

        quickLookWidget.setOnMouseOverListener((JavaScriptCallback) ev -> {
            SwingUtilities.invokeLater(() -> {
                if (this.wikiRequest == null) {
                    quickLookPanel.updateDetails(null, null);
                    return;
                }
                Optional<RemoteSlot> maybeRemoteSlot = unifiedSlotStates.get(slot.getIndex());
                if (!maybeRemoteSlot.isPresent()) {
                    quickLookPanel.updateDetails(null, null);
                    return;
                }

                int itemId = maybeRemoteSlot.get().getItemId();
                WikiItemMargins margins = this.wikiRequest.getData().get(itemId);
                PointerInfo a = MouseInfo.getPointerInfo();
                Point p = a.getLocation();
                quickLookPanel.updateDetails(maybeRemoteSlot.get(), margins);
                popup.pack();
                popup.setLocation(p.x + 25, p.y);
                popup.setVisible(true);
            });
        });

        quickLookWidget.setOnMouseLeaveListener((JavaScriptCallback) ev -> {
            SwingUtilities.invokeLater(() -> {
                popup.setVisible(false);
            });
        });

        quickLookWidget.revalidate();
        return quickLookWidget;
    }

    private Widget createTimeInRangeWidget(Widget slotWidget) {
        Widget timeInRangeWidget = slotWidget.createChild(-1, WidgetType.TEXT);
        timeInRangeWidget.setTextColor(Color.WHITE.getRGB());
        timeInRangeWidget.setFontId(FontID.PLAIN_11);
        timeInRangeWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
        timeInRangeWidget.setOriginalX(35);
        timeInRangeWidget.setOriginalY(77);
        timeInRangeWidget.setWidthMode(WidgetSizeMode.MINUS);
        timeInRangeWidget.setOriginalHeight(10);
        timeInRangeWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
        timeInRangeWidget.setXTextAlignment(WidgetTextAlignment.LEFT);
        timeInRangeWidget.setTextShadowed(true);
        timeInRangeWidget.revalidate();
        return timeInRangeWidget;
    }

    /**
     * Determines if the widget is still actually on the screen. This is useful for deciding
     * whether we should reuse the old widget, or create a new one.
     */
    private boolean isWidgetStillAttached(Widget widget) {
        Widget parent = widget.getParent();
        Widget[] siblings = parent.getDynamicChildren();
        return widget.getIndex() < siblings.length && siblings[widget.getIndex()] != null;
    }

    /**
     * Builds a list of RemoteSlots that represent the most up to date state of an account's
     * offers based on the data from the server and local data. RemoteSlot is the actual structure
     * we get back from the server. However, the RemoteSlot we return from this method may only contains
     * the timeInRange data from the server sent RemoteSlot, the rest of the data is from the local slot.
     * The reason I've used RemoteSlot as the structure to carry the info, even when
     * we use local data, is because I didn't want to define a new class just for that...
     *
     * The reason we use remote slot data from the server even though local data mostly suffices is because
     * the remote slot data has the timeInRange.
     */
    private List<Optional<RemoteSlot>> getCombinedSlotStates() {
        List<Optional<RemoteSlot>> slots = new ArrayList<>();
        GrandExchangeOffer[] currentOffers = plugin.getClient().getGrandExchangeOffers();
        Map<Integer, OfferEvent> enrichedOffers = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();

        String rsn = plugin.getCurrentlyLoggedInAccount();
        Optional<RemoteAccountSlots> maybeRemoteAccountSlots = remoteAccountSlots.stream().filter(r -> r.getRsn().equals(rsn)).findFirst();
        Map<Integer, RemoteSlot> idxToRemoteSlot = maybeRemoteAccountSlots
            .map(accountSlots -> ListUtils.toMap(accountSlots.getSlots(), r -> Pair.of(r.getIndex(), r)))
            .orElseGet(HashMap::new);

        for (int i = 0; i < currentOffers.length; i++) {
            GrandExchangeOffer localSlot = currentOffers[i];
            RemoteSlot remoteslot = idxToRemoteSlot.get(i);
            OfferEvent enrichedOffer = enrichedOffers.get(i);

            if (localSlot.getState() == GrandExchangeOfferState.EMPTY || OfferEvent.isComplete(localSlot.getState())) {
                slots.add(Optional.empty());
                continue;
            }

            Optional<RemoteSlot> combinedSlot = clientGeOfferToRemoteSlot(i, localSlot, enrichedOffer);
            if (remoteslot != null & combinedSlot.isPresent()) {
                combinedSlot.get().setTimeInRange(remoteslot.getTimeInRange());
            }
            slots.add(combinedSlot);
        }
        return slots;
    }

    /**
     * Turns a local slot structure into a RemoteSlot structure. I turn everything into a RemoteSlot just because
     * its already defined and contains all the right fields.
     */
    private Optional<RemoteSlot> clientGeOfferToRemoteSlot(int index, GrandExchangeOffer offer, OfferEvent enrichedOffer) {
        if (wikiRequest == null) {
            return Optional.empty();
        }

        int itemId = offer.getItemId();
        WikiItemMargins margins = this.wikiRequest.getData().get(itemId);
        if (margins == null) {
            return Optional.empty();
        }

        if (OfferEvent.isComplete(offer.getState())) {
            return Optional.empty();
        }

        int listedPrice = offer.getPrice();
        boolean isBuy = offer.getState() == GrandExchangeOfferState.BUYING;
        SlotPredictedState predictedState = getPredictedState(isBuy, listedPrice, margins.getLow(), margins.getHigh());

        return Optional.of(new RemoteSlot(index, predictedState, 0, itemId, listedPrice, isBuy, "ACTIVE", enrichedOffer.getTime().getEpochSecond()));
    }

    SlotPredictedState getPredictedState(boolean buy, int listedPrice, int instaSell, int instaBuy) {
        boolean isBetterThanWiki = buy ? listedPrice > Math.max(instaBuy, instaSell) : listedPrice < Math.min(instaSell, instaBuy);
        boolean isInRange = buy ? listedPrice >= Math.min(instaSell, instaBuy) : listedPrice <= Math.max(instaBuy, instaSell);

        if (isBetterThanWiki) {
            return SlotPredictedState.BETTER_THAN_WIKI;
        } else if (isInRange) {
            return SlotPredictedState.IN_RANGE;
        } else {
            return SlotPredictedState.OUT_OF_RANGE;
        }
    }
}

/**
 * The panel that is displayed when you hover over the magnifying glass widget. This panel
 * contains the wiki margins of the item, and whether your offer is competitive or not
 * and what you can do to make it competitive. This essentially provides a faster way
 * to get margin info then finding the item in the sidebar (via searching or clicking on the offer).
 */
@Slf4j
class QuickLookPanel extends JPanel {
    JLabel wikiInstaBuy = new JLabel("", JLabel.RIGHT);
    JLabel wikiInstaSell = new JLabel("", JLabel.RIGHT);
    JLabel wikiInstaBuyAge = new JLabel("", JLabel.RIGHT);
    JLabel wikiInstaSellAge = new JLabel("", JLabel.RIGHT);
    JLabel offerCompetitivenessText = new JLabel("", JLabel.CENTER);
    JLabel toMakeOfferCompetitiveTest = new JLabel("", JLabel.CENTER);

    QuickLookPanel() {
        super();
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 15, 10, 15));

        offerCompetitivenessText.setFont(FontManager.getRunescapeBoldFont());
        toMakeOfferCompetitiveTest.setFont(FontManager.getRunescapeSmallFont());
        toMakeOfferCompetitiveTest.setBorder(new EmptyBorder(3,0,0,0));

        wikiInstaBuy.setForeground(Color.WHITE);
        wikiInstaSell.setForeground(Color.WHITE);

        JLabel title = new JLabel("Quick Look", JLabel.CENTER);
        title.setFont(new Font("Whitney", Font.BOLD + Font.ITALIC, 12));

        JLabel wikiInstaBuyDesc = new JLabel("Wiki Insta Buy", JLabel.LEFT);
        JLabel wikiInstaSellDesc = new JLabel("Wiki Insta Sell", JLabel.LEFT);
        JLabel wikiInstaBuyAgeDesc = new JLabel("Wiki Insta Buy Age", JLabel.LEFT);
        JLabel wikiInstaSellAgeDesc = new JLabel("Wiki Insta Sell Age", JLabel.LEFT);

        //being lazy...just want to separate the rows that hold the wiki price vals from the rows that hold the wiki time
        //vals
        wikiInstaBuyAgeDesc.setBorder(new EmptyBorder(5,0,0,0));
        wikiInstaBuyAge.setBorder(new EmptyBorder(5,0,0,0));

        Arrays.asList(wikiInstaBuyDesc, wikiInstaSellDesc, wikiInstaBuyAgeDesc, wikiInstaSellAgeDesc, wikiInstaBuy,
            wikiInstaSell, wikiInstaBuyAge, wikiInstaSellAge).forEach(l -> l.setFont(FontManager.getRunescapeSmallFont()));

        JPanel wikiPanel = new JPanel(new DynamicGridLayout(4, 2, 10, 2));
        wikiPanel.setBorder(new EmptyBorder(10,10,0,10));
        wikiPanel.add(wikiInstaBuyDesc);
        wikiPanel.add(wikiInstaBuy);
        wikiPanel.add(wikiInstaSellDesc);
        wikiPanel.add(wikiInstaSell);

        wikiPanel.add(wikiInstaBuyAgeDesc);
        wikiPanel.add(wikiInstaBuyAge);
        wikiPanel.add(wikiInstaSellAgeDesc);
        wikiPanel.add(wikiInstaSellAge);

        JPanel summaryPanel = new JPanel(new DynamicGridLayout(2,1));
        summaryPanel.setBorder(new EmptyBorder(10,0,0,0));
        summaryPanel.add(offerCompetitivenessText);
        summaryPanel.add(toMakeOfferCompetitiveTest);

        add(title, BorderLayout.NORTH);
        add(wikiPanel, BorderLayout.CENTER);
        add(summaryPanel, BorderLayout.SOUTH);
    }

    public void updateDetails(RemoteSlot slot, WikiItemMargins wikiItemInfo) {
        Arrays.asList(wikiInstaBuy, wikiInstaSell, wikiInstaBuyAge, wikiInstaSellAge).forEach(l -> l.setForeground(Color.WHITE));
        if (wikiItemInfo == null || slot == null) {
            Arrays.asList(wikiInstaBuy, wikiInstaSell, wikiInstaBuyAge, wikiInstaSellAge).forEach(l -> l.setText("No data"));
            return;
        }
        Map<Integer, JLabel> wikiMarginToLabel = new HashMap<>();
        wikiMarginToLabel.put(wikiItemInfo.getHigh(), wikiInstaBuy);
        wikiMarginToLabel.put(wikiItemInfo.getLow(), wikiInstaSell);

        wikiInstaBuyAge.setText(wikiItemInfo.getHighTime() == 0? "No data" : TimeFormatters.formatDuration(Instant.ofEpochSecond(wikiItemInfo.getHighTime())));
        wikiInstaSellAge.setText(wikiItemInfo.getLowTime() == 0? "No data":TimeFormatters.formatDuration(Instant.ofEpochSecond(wikiItemInfo.getLowTime())));
        wikiInstaBuy.setText(wikiItemInfo.getHigh() == 0 ? "No data" : QuantityFormatter.formatNumber(wikiItemInfo.getHigh()) + " gp");
        wikiInstaSell.setText(wikiItemInfo.getLow() == 0 ? "No data" : QuantityFormatter.formatNumber(wikiItemInfo.getLow()) + " gp");

        toMakeOfferCompetitiveTest.setText("");
        offerCompetitivenessText.setText("");

        int max = Math.max(wikiItemInfo.getHigh(), wikiItemInfo.getLow());
        int min = Math.min(wikiItemInfo.getHigh(), wikiItemInfo.getLow());

        if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(max), ColorScheme.GRAND_EXCHANGE_PRICE);
            offerCompetitivenessText.setText(
                String.format("<html> buy offer is ultra competitive: %s &gt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), ColorScheme.GRAND_EXCHANGE_PRICE)
                    ));
        }
        else if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(min), CustomColors.IN_RANGE);
            UIUtilities.recolorLabel(wikiMarginToLabel.get(max), ColorScheme.GRAND_EXCHANGE_PRICE);
            offerCompetitivenessText.setText(
                String.format("<html> buy offer is competitive: %s &lt= %s &lt %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), CustomColors.IN_RANGE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), ColorScheme.GRAND_EXCHANGE_PRICE)
                ));
        }
        else if (slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(min), CustomColors.TOMATO);
            offerCompetitivenessText.setText(
                String.format("<html> buy offer is not competitive: %s &lt %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), CustomColors.TOMATO)
                ));
            toMakeOfferCompetitiveTest.setText(
                String.format("<html> set price to &gt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), CustomColors.TOMATO)
                ));
        }
        else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.BETTER_THAN_WIKI) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(min), ColorScheme.GRAND_EXCHANGE_PRICE);
            offerCompetitivenessText.setText(
                String.format("<html> sell offer is ultra competitive: %s &lt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()),Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), ColorScheme.GRAND_EXCHANGE_PRICE)
                ));
        }
        else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.IN_RANGE) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(min), ColorScheme.GRAND_EXCHANGE_PRICE);
            UIUtilities.recolorLabel(wikiMarginToLabel.get(max), CustomColors.IN_RANGE);
            offerCompetitivenessText.setText(
                String.format("<html> sell offer is competitive: %s &lt %s &lt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(min), ColorScheme.GRAND_EXCHANGE_PRICE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), CustomColors.IN_RANGE)
                ));
        }
        else if (!slot.isBuyOffer() && slot.getPredictedState() == SlotPredictedState.OUT_OF_RANGE) {
            UIUtilities.recolorLabel(wikiMarginToLabel.get(max), CustomColors.TOMATO);
            offerCompetitivenessText.setText(
                String.format("<html> sell offer is not competitive: %s &gt %s</html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(slot.getOfferPrice()), Color.WHITE),
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), CustomColors.TOMATO)
                ));
            toMakeOfferCompetitiveTest.setText(
                String.format("<html> set price to &lt= %s </html>",
                    UIUtilities.colorText(QuantityFormatter.formatNumber(max), CustomColors.TOMATO)
                ));
        }
    }
}
