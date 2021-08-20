package com.flippingutilities.controller;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.widgets.TradeActivityTimer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.http.api.item.ItemStats;
import org.graalvm.compiler.nodes.calc.IntegerDivRemNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class NewOfferEventPipelineHandler {
    FlippingPlugin plugin;

    NewOfferEventPipelineHandler(FlippingPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * This method is invoked every time the plugin receives a GrandExchangeOfferChanged event which is
     * when the user set an offer, cancelled an offer, or when an offer was updated (items bought/sold partially
     * or completely).
     *
     * @param offerChangedEvent the offer event that represents when an offer is updated
     *                          (buying, selling, bought, sold, cancelled sell, or cancelled buy)
     */
    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerChangedEvent) {
        if (plugin.getCurrentlyLoggedInAccount() == null) {
            OfferEvent newOfferEvent = createOfferEvent(offerChangedEvent);
            //event came in before account was fully logged in. This means that the offer actually came through
            //sometime when the account was logged out, at an undetermined time. We need to mark the offer as such to
            //avoid adjusting ge limits and slot timers incorrectly (cause we don't know exactly when the offer came in)
            newOfferEvent.setBeforeLogin(true);
            plugin.getEventsReceivedBeforeFullLogin().add(newOfferEvent);
            return;
        }
        OfferEvent newOfferEvent = createOfferEvent(offerChangedEvent);
        if (newOfferEvent.getTickArrivedAt() == plugin.getLoginTickCount()) {
            log.info("this is triggering an awful lot");
            newOfferEvent.setBeforeLogin(true);
        }
        onNewOfferEvent(newOfferEvent);
    }

    public void onNewOfferEvent(OfferEvent newOfferEvent) {
        String currentlyLoggedInAccount = plugin.getCurrentlyLoggedInAccount();
        if (currentlyLoggedInAccount != null) {
            newOfferEvent.setMadeBy(currentlyLoggedInAccount);
        }

        Optional<OfferEvent> screenedOfferEvent = screenOfferEvent(newOfferEvent);

        Map<Integer, OfferEvent> lastOfferEventForEachSlot = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();
        for (int i =0;i < 8; i++) {
            log.info("slot {}, event: {}", i, lastOfferEventForEachSlot.get(i));
        }

        if (!screenedOfferEvent.isPresent()) {
            return;
        }

        OfferEvent finalizedOfferEvent = screenedOfferEvent.get();

        List<FlippingItem> currentlyLoggedInAccountsTrades = plugin.getDataHandler().getAccountData(currentlyLoggedInAccount).getTrades();

        Optional<FlippingItem> flippingItem = currentlyLoggedInAccountsTrades.stream().filter(item -> item.getItemId() == finalizedOfferEvent.getItemId()).findFirst();

        updateTradesList(currentlyLoggedInAccountsTrades, flippingItem, finalizedOfferEvent.clone());

        plugin.setUpdateSinceLastAccountWideBuild(true);

        rebuildDisplayAfterOfferEvent(flippingItem, finalizedOfferEvent);
    }

    /**
     * There is no point rebuilding either the stats panel or flipping panel when the user is looking at the trades list of
     * one of their accounts that isn't logged in as that trades list won't be being updated anyway.
     * <p>
     * Only rebuild flipping panel if the FlippingItem is not already present or if the offer is a margin check. We need to rebuild
     * when the item isn't present as that means a new FlippingItemPanel had to be created to represent the
     * new FlippingItem. We also need to rebuild if the offer is a margin check because a margin check offer causes
     * a reordering of the FlippingItemPanels as the FlippingItemPanel representing the recently margin checked item
     * floats to the top.
     * <p>
     * In the case when the FlippingItem is present and the offer is not a margin check, we don't have to do a full
     * flipping panel rebuild as we only update the Jlabels that specify the latest buy/sell price. No new panels
     * are created and nothing is reordered, hence a full rebuild would be wasteful.
     *
     * @param flippingItem represents whether the FlippingItem existed in the currently logged in account's tradeslist when
     *                     the offer came in.
     * @param offerEvent   offer event just received
     */
    private void rebuildDisplayAfterOfferEvent(Optional<FlippingItem> flippingItem, OfferEvent offerEvent) {

        if (!(plugin.getAccountCurrentlyViewed().equals(plugin.getCurrentlyLoggedInAccount()) ||
                plugin.getAccountCurrentlyViewed().equals(FlippingPlugin.ACCOUNT_WIDE))) {
            return;
        }

        if (!flippingItem.isPresent() || flippingItem.isPresent() && offerEvent.isMarginCheck()) {
            plugin.getFlippingPanel().rebuild(plugin.viewTradesForCurrentView());
        } else if (flippingItem.isPresent() && !offerEvent.isMarginCheck()) {
            plugin.getFlippingPanel().refreshPricesForFlippingItemPanel(flippingItem.get().getItemId());
        }

        plugin.getStatPanel().rebuild(plugin.viewTradesForCurrentView());
    }

    /**
     * Runelite has some wonky events. For example, every empty/buy/sell/cancelled buy/cancelled sell
     * spawns two identical events. And when you fully buy/sell item, it spawns two events (a
     * buying/selling event and a bought/sold event). This method screens out the unwanted events/duplicate
     * events and sets the ticksSinceFirstOffer field correctly on new OfferEvents. This method is also responsible
     * for broadcasting the event to any components that need it, such as the slot panel, the slot timer widgets, etc.
     *
     * @param newOfferEvent event that just occurred
     * @return an optional containing an OfferEvent.
     */
    public Optional<OfferEvent> screenOfferEvent(OfferEvent newOfferEvent) {
        plugin.getSlotsPanel().update(newOfferEvent);
        Map<Integer, OfferEvent> lastOfferEventForEachSlot = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();
        List<TradeActivityTimer> tradeActivityTimers = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getSlotTimers();

        OfferEvent lastOfferEvent = lastOfferEventForEachSlot.get(newOfferEvent.getSlot());

        //user has no history
        if (lastOfferEvent == null) {
            log.info("last offer event was null");
            if (newOfferEvent.isCausedByEmptySlot()) {
                return Optional.empty();
            }
            else {
                lastOfferEventForEachSlot.put(newOfferEvent.getSlot(), newOfferEvent);
                return Optional.of(newOfferEvent);
            }
        }

        //we get essentially every offer event twice..
        if (lastOfferEvent.isDuplicate(newOfferEvent)) {
            log.info("new offer event {} is dup", newOfferEvent);
            return Optional.empty();
        }

        //get empty slot offer events on login, reject them. Just make sure offer isn't complete as we don't want to
        //re-add a non complete offer as that will mess up its "ticksSinceFirstOffer"
        if (newOfferEvent.isCausedByEmptySlot() && !lastOfferEvent.isComplete()) {
            log.info("empty slot event on login, not removing: {}", newOfferEvent);
            return Optional.empty();
        }

        //empty offer event after collecting an offer, mark the slot as being empty by removing the offer event for it.
        //this will technically also trigger on login when we get those empty slot events if the last event
        //had a complete state, but in that case we will just re-add that offer event on the next tick when we get an
        //event for it
        if (newOfferEvent.isCausedByEmptySlot() && lastOfferEvent.isComplete()) {
            log.info("empty slot event after offer completed: {}", newOfferEvent);
            lastOfferEventForEachSlot.remove(newOfferEvent.getSlot());
            return Optional.empty();
        }

        if (newOfferEvent.getCurrentQuantityInTrade() == 0) {
            tradeActivityTimers.get(newOfferEvent.getSlot()).setCurrentOffer(newOfferEvent);
            log.info("start of offer event: {} actual event: {}", newOfferEvent.isStartOfOffer(),  newOfferEvent);
            lastOfferEventForEachSlot.put(newOfferEvent.getSlot(), newOfferEvent); //tickSinceFirstOffer is 0 here
            return Optional.empty();
        }

        if (newOfferEvent.isRedundantEventBeforeOfferCompletion()) {
            log.info("redundant offer before completion: {}", newOfferEvent);
            return Optional.empty();
        }

        log.info("offer event: {} going through!", newOfferEvent);
        newOfferEvent.setTicksSinceFirstOffer(lastOfferEvent);
        lastOfferEventForEachSlot.put(newOfferEvent.getSlot(), newOfferEvent);
        tradeActivityTimers.get(newOfferEvent.getSlot()).setCurrentOffer(newOfferEvent);
        return Optional.of(newOfferEvent);
    }

    /**
     * We get offer events that mark the start of an offer on login, even though we already received them prior to logging
     * out. This method is used to identify them.
     *
     * @param offerEvent
     * @return whether or not this trade event is a duplicate "start of trade" event
     */
    private boolean isDuplicateStartOfOfferEvent(OfferEvent offerEvent) {
        Map<Integer, OfferEvent> loggedInAccsLastOffers = plugin.getDataHandler().viewAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();
        return loggedInAccsLastOffers.containsKey(offerEvent.getSlot()) &&
                loggedInAccsLastOffers.get(offerEvent.getSlot()).getCurrentQuantityInTrade() == 0 &&
                loggedInAccsLastOffers.get(offerEvent.getSlot()).getState() == offerEvent.getState();
    }

    /**
     * Creates an OfferEvent object out of a GrandExchangeOfferChanged event and adds additional attributes such as
     * tickArrivedAt to help identify margin check offers.
     *
     * @param newOfferEvent event that we subscribe to.
     * @return an OfferEvent object with the relevant information from the event.
     */
    private OfferEvent createOfferEvent(GrandExchangeOfferChanged newOfferEvent) {
        OfferEvent offer = OfferEvent.fromGrandExchangeEvent(newOfferEvent);
        offer.setTickArrivedAt(plugin.getClient().getTickCount());
        offer.setMadeBy(plugin.getCurrentlyLoggedInAccount());
        return offer;
    }

    /**
     * This method updates the given trade list in response to an OfferEvent
     *
     * @param trades       the trades list to update
     * @param flippingItem the flipping item to be updated in the tradeslist, if it even exists
     * @param newOffer     new offer that just came in
     */
    private void updateTradesList(List<FlippingItem> trades, Optional<FlippingItem> flippingItem, OfferEvent newOffer) {
        if (flippingItem.isPresent()) {
            FlippingItem item = flippingItem.get();
            if (newOffer.isMarginCheck()) {
                trades.remove(item);
                trades.add(0, item);
            }
            //if a user buys/sells an item they previously deleted from the flipping panel, show the panel again.
            if (!item.getValidFlippingPanelItem()) {
                item.setValidFlippingPanelItem(true);
                trades.remove(item);
                trades.add(0, item);
            }

            item.updateHistory(newOffer);
            item.updateLatestProperties(newOffer);
        } else {
            addToTradesList(trades, newOffer);
        }
    }

    /**
     * Constructs a FlippingItem, the data structure that represents an item the user is currently flipping, and
     * adds it to the given trades list. This method is invoked when we receive an offer event for an item that isn't
     * currently present in the trades list.
     *
     * @param tradesList the trades list to be updated
     * @param newOffer   the offer to update the trade list with
     */
    private void addToTradesList(List<FlippingItem> tradesList, OfferEvent newOffer) {
        int tradeItemId = newOffer.getItemId();
        String itemName = plugin.getItemManager().getItemComposition(tradeItemId).getName();

        ItemStats itemStats = plugin.getItemManager().getItemStats(tradeItemId, false);
        int geLimit = itemStats != null ? itemStats.getGeLimit() : 0;

        FlippingItem flippingItem = new FlippingItem(tradeItemId, itemName, geLimit, plugin.getCurrentlyLoggedInAccount());
        flippingItem.setValidFlippingPanelItem(true);
        flippingItem.updateHistory(newOffer);
        flippingItem.updateLatestProperties(newOffer);

        tradesList.add(0, flippingItem);
    }
}
