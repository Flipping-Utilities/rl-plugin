/*
 * Copyright (c) 2020, Belieal <https://github.com/Belieal>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.flippingutilities;

import com.flippingutilities.ui.TabManager;
import com.flippingutilities.ui.flipping.FlippingItemWidget;
import com.flippingutilities.ui.flipping.FlippingPanel;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.VarClientInt;
import static net.runelite.api.VarPlayer.CURRENT_GE_ITEM;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetHiddenChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.account.AccountSession;
import net.runelite.client.account.SessionManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.SessionClose;
import net.runelite.client.events.SessionOpen;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.item.ItemStats;

@Slf4j
@PluginDescriptor(
	name = "Flipping Utilities",
	description = "Provides utilities for GE flipping"
)

public class FlippingPlugin extends Plugin
{
	private static final int GE_HISTORY_TAB_WIDGET_ID = 149;
	private static final int GE_BACK_BUTTON_WIDGET_ID = 30474244;
	private static final int GE_OFFER_INIT_STATE_CHILD_ID = 18;

	public static final String CONFIG_GROUP = "flipping";
	public static final String ITEMS_CONFIG_KEY = "items";
	public static final String TIME_INTERVAL_CONFIG_KEY = "selectedinterval";

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ScheduledExecutorService executor;
	private ScheduledFuture timeUpdateFuture;
	@Inject
	private ClientToolbar clientToolbar;
	private NavigationButton navButton;

	@Inject
	private ConfigManager configManager;
	@Inject
	private SessionManager sessionManager;
	@Inject
	@Getter
	private FlippingConfig config;

	@Inject
	private ItemManager itemManager;

	private FlippingPanel flippingPanel;
	@Getter
	private StatsPanel statPanel;
	private FlippingItemWidget flippingWidget;

	private TabManager tabManager;

	//Stores all bought or sold trades.
	@Getter
	private ArrayList<FlippingItem> tradesList = new ArrayList<>();

	//Ensures we don't rebuild constantly when highlighting
	@Setter
	private int prevHighlight;


	//will store the last seen events for each GE slot and so that we can screen out duplicate/bad events
	private Map<Integer, OfferInfo> lastOffers = new HashMap<>();

	private boolean previouslyLoggedIn;

	@Override
	protected void startUp()
	{
		//Main visuals.
		flippingPanel = new FlippingPanel(this, itemManager, executor);
		statPanel = new StatsPanel(this, itemManager);

		//Represents the panel navigation that switches between panels using tabs at the top.
		tabManager = new TabManager(flippingPanel, statPanel);

		// I wanted to put it below the GE plugin, but can't as the GE and world switcher button have the same priority...
		navButton = NavigationButton.builder()
			.tooltip("Flipping Utilities")
			.icon(ImageUtil.getResourceStreamFromClass(getClass(), "/graph_icon_green.png"))
			.priority(3)
			.panel(tabManager)
			.build();

		clientToolbar.addNavigation(navButton);

		clientThread.invokeLater(() ->
		{
			switch (client.getGameState())
			{
				case STARTING:
				case UNKNOWN:
					return false;
			}
			//Loads tradesList with data from previous sessions.
			if (config.storeTradeHistory())
			{
				loadConfig();
			}

			executor.submit(() -> clientThread.invokeLater(() -> SwingUtilities.invokeLater(() ->
			{
				statPanel.rebuild(tradesList);
				if (tradesList != null)
				{
					for (FlippingItem flippingItem : tradesList)
					{
						//it may have been four hours since the first time the user bought the item, so
						//it might be displaying old values, so this is a way to clear them on start up.
						flippingItem.validateGeProperties();
					}
					flippingPanel.rebuildFlippingPanel(tradesList);
					statPanel.rebuild(tradesList);
				}
			})));
			return true;
		});

		//Ensures the panel displays for the margin check being outdated and the next ge reset
		//are updated every second.
		timeUpdateFuture = executor.scheduleAtFixedRate(() ->
		{
			flippingPanel.updateActivePanelsPriceOutdatedDisplay();
			flippingPanel.updateActivePanelsGePropertiesDisplay();
			statPanel.updateDisplays();
		}, 100, 1000, TimeUnit.MILLISECONDS);
	}

	@Subscribe(priority = 101)
	public void onClientShutdown(ClientShutdown event)
	{
		log.info("client is being shutdown, saving config");
		updateConfig();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGGED_IN) {
			log.info("user logged in");
			previouslyLoggedIn = true;
		}

		if (event.getGameState() == GameState.LOGIN_SCREEN && previouslyLoggedIn) {
			log.info("user logged out, saving config");
			updateConfig();
		}
	}

	@Override
	protected void shutDown()
	{
		if (timeUpdateFuture != null)
		{
			//Stop all timers
			timeUpdateFuture.cancel(true);
			timeUpdateFuture = null;
		}

		clientToolbar.removeNavigation(navButton);
	}

	@Subscribe
	public void onSessionOpen(SessionOpen event)
	{
		//Load new account config
		final AccountSession session = sessionManager.getAccountSession();
		if (session != null && session.getUsername() != null)
		{
			clientThread.invokeLater(() ->
			{
				loadConfig();
				SwingUtilities.invokeLater(() -> flippingPanel.rebuildFlippingPanel(tradesList));
				return true;
			});
		}
	}

	@Subscribe
	public void onSessionClose(SessionClose event)
	{
		//Config is now locally stored
		clientThread.invokeLater(() ->
		{
			loadConfig();
			SwingUtilities.invokeLater(() -> flippingPanel.rebuildFlippingPanel(tradesList));
			return true;
		});
	}

	/**
	 * This method is invoked every time the plugin receives a GrandExchangeOfferChanged event.
	 * The events are handled in one of two ways:
	 * <p>
	 * if the offer is deemed a margin check, its either added
	 * to the tradesList (if it doesn't exist), or, if the item exists, it is updated to reflect the margins as
	 * discovered by the margin check.
	 * <p>
	 * The second way events are handled is in all other cases except for margin checks. If an offer is
	 * not a margin check and the offer exists, you don't need to update the margins of the item, but you do need
	 * to update its history (which updates its ge limit/reset time and the profit a user made for that item.
	 * <p>
	 * The history of a flipping item is updated in every branch of this method.
	 *
	 * @param newOfferEvent the offer event that represents when an offer is updated
	 *                      (buying, selling, bought, sold, cancelled sell, or cancelled buy)
	 */
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged newOfferEvent)
	{
		OfferInfo newOffer = extractRelevantInfo(newOfferEvent);

		if (isBadOffer(newOffer))
		{
			return;
		}

		System.out.println(newOffer.toString());

		Optional<FlippingItem> flippingItem = findItemInTradesList(newOffer.getItemId());

		if (newOffer.isMarginCheck())
		{
			if (flippingItem.isPresent())
			{
				flippingItem.get().update(newOffer);
				flippingItem.get().updateMargin(newOffer);
				tradesList.remove(flippingItem.get());
				tradesList.add(0, flippingItem.get());
			}
			else
			{
				addToTradesList(newOffer);
			}

			flippingPanel.rebuildFlippingPanel(tradesList);
		}

		//if its not a margin check and the item isn't present, you don't know what to put as the buy/sell price
		else if (flippingItem.isPresent())
		{
			flippingItem.get().update(newOffer);
		}

		statPanel.rebuild(tradesList);
		flippingPanel.updateActivePanelsGePropertiesDisplay();
	}

	/**
	 * Runelite has some wonky events at times. For example, every empty/buy/sell/cancelled buy/cancelled sell
	 * spawns two identical events. And when you fully buy/sell item, it also spawns two events (a
	 * buying/selling event and a bought/sold event). This method screens out the unwanted events/duplicate
	 * events and also sets the ticks since the first offer in that slot to help with figuring out whether
	 * an offer is a margin check.
	 *
	 * @param newOffer
	 * @return a boolean representing whether the offer should be passed on or discarded
	 */
	private boolean isBadOffer(OfferInfo newOffer)
	{
		//i am mutating offers and they are being passed around, so i'm cloning to avoid passing the same reference around.
		OfferInfo clonedNewOffer = newOffer.clone();

		//Check empty offers.
		if (clonedNewOffer.getItemId() == 0 || clonedNewOffer.getState() == GrandExchangeOfferState.EMPTY)
		{
			return true;
		}

		//this is always the start of any offer (when you first put in an offer)
		if (clonedNewOffer.getCurrentQuantityInTrade() == 0)
		{
			lastOffers.put(clonedNewOffer.getSlot(), clonedNewOffer);//tickSinceFirstOffer is 0 here
			return true;
		}

		//when an offer is complete, two events are generated: a buying/selling event and a bought/sold event.
		//this clause ignores the buying/selling event as it conveys the same info. We can tell its the buying/selling
		//event right before a bought/sold event due to the currentQuantityInTrade of the offer being == to the total currentQuantityInTrade of the offer.
		if ((clonedNewOffer.getState() == GrandExchangeOfferState.BUYING || clonedNewOffer.getState() == GrandExchangeOfferState.SELLING) && clonedNewOffer.getCurrentQuantityInTrade() == newOffer.getTotalQuantityInTrade())
		{
			return true;
		}

		OfferInfo lastOfferForSlot = lastOffers.get(clonedNewOffer.getSlot());

		//if its a duplicate as the last seen event
		if (lastOfferForSlot.equals(clonedNewOffer))
		{
			return true;
		}

		int tickDiffFromLastOffer = clonedNewOffer.getTickArrivedAt() - lastOfferForSlot.getTickArrivedAt();
		clonedNewOffer.setTicksSinceFirstOffer(tickDiffFromLastOffer + lastOfferForSlot.getTicksSinceFirstOffer());
		lastOffers.put(clonedNewOffer.getSlot(), clonedNewOffer);
		newOffer.setTicksSinceFirstOffer(tickDiffFromLastOffer + lastOfferForSlot.getTicksSinceFirstOffer());
		return false; //not a bad event

	}

	/**
	 * This method extracts the data from the GrandExchangeOfferChanged event, which is a nested
	 * data structure, and puts it into a flat data structure- OfferInfo. It also adds time to the offer.
	 *
	 * @param newOfferEvent new offer event just received
	 * @return an OfferInfo with the relevant information.
	 */
	private OfferInfo extractRelevantInfo(GrandExchangeOfferChanged newOfferEvent)
	{
		GrandExchangeOffer offer = newOfferEvent.getOffer();

		boolean isBuy = offer.getState() == GrandExchangeOfferState.BOUGHT
			|| offer.getState() == GrandExchangeOfferState.CANCELLED_BUY
			|| offer.getState() == GrandExchangeOfferState.BUYING;

		OfferInfo offerInfo = new OfferInfo(
			isBuy,
			offer.getItemId(),
			offer.getQuantitySold(),
			offer.getQuantitySold() == 0 ? 0 : offer.getSpent() / offer.getQuantitySold(),
			Instant.now(),
			newOfferEvent.getSlot(),
			offer.getState(),
			client.getTickCount(),
			0,
			offer.getTotalQuantity(),
			0);

		return offerInfo;
	}

	private Optional<FlippingItem> findItemInTradesList(int itemIdToFind)
	{
		return tradesList.stream().filter((item) -> item.getItemId() == itemIdToFind).findFirst();
	}


	/**
	 * Given a new offer, this method creates a FlippingItem, the data structure that represents an item
	 * you are currently flipping, and adds it to the tradesList. The tradesList is a crucial part of the state
	 * of the flippingPlugin, as only items from the tradesList are rendered and updated.
	 *
	 * @param newOffer new offer just received
	 */
	private void addToTradesList(OfferInfo newOffer)
	{
		int tradeItemId = newOffer.getItemId();
		String itemName = itemManager.getItemComposition(tradeItemId).getName();

		ItemStats itemStats = itemManager.getItemStats(tradeItemId, false);
		int geLimit = itemStats != null ? itemStats.getGeLimit() : 0;

		//Initialized with sub info being collapsed.
		FlippingItem flippingItem = new FlippingItem(tradeItemId, itemName, geLimit);

		flippingItem.updateMargin(newOffer);
		flippingItem.update(newOffer);

		tradesList.add(0, flippingItem);
	}

	@Provides
	FlippingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlippingConfig.class);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getIndex() == CURRENT_GE_ITEM.getId() &&
			client.getVar(CURRENT_GE_ITEM) != -1 && client.getVar(CURRENT_GE_ITEM) != 0)
		{
			highlightOffer();
		}
	}

	@Subscribe
	public void onWidgetHiddenChanged(WidgetHiddenChanged event)
	{
		Widget widget = event.getWidget();
		// If the back button is no longer visible, we know we aren't in the offer setup.
		if (flippingPanel.isItemHighlighted() && widget.isHidden() && widget.getId() == GE_BACK_BUTTON_WIDGET_ID)
		{
			flippingPanel.dehighlightItem();
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		// The player opens the trade history tab. Necessary since the back button isn't considered hidden here.
		if (event.getGroupId() == GE_HISTORY_TAB_WIDGET_ID && flippingPanel.isItemHighlighted())
		{
			flippingPanel.dehighlightItem();
		}
	}

	//TODO: Refactor this with a search on the search bar
	private void highlightOffer()
	{
		int currentGEItemId = client.getVar(CURRENT_GE_ITEM);
		if (currentGEItemId == prevHighlight || flippingPanel.isItemHighlighted())
		{
			return;
		}
		prevHighlight = currentGEItemId;
		flippingPanel.highlightItem(currentGEItemId);
	}

	//Functionality to the top right reset button.
	public void resetTradeHistory()
	{
		tradesList.clear();
		flippingPanel.setItemHighlighted(false);
		configManager.unsetConfiguration(CONFIG_GROUP, ITEMS_CONFIG_KEY);
		flippingPanel.cardLayout.show(flippingPanel.getCenterPanel(), FlippingPanel.getWELCOME_PANEL());
		flippingPanel.rebuildFlippingPanel(tradesList);
	}

	//Stores all the session trade data in config.
	public void updateConfig()
	{
		if (tradesList.isEmpty())
		{
			return;
		}

		final Gson gson = new Gson();
		final String json = gson.toJson(tradesList);
		configManager.setConfiguration(CONFIG_GROUP, ITEMS_CONFIG_KEY, json);

		if (statPanel.getSelectedInterval() != null)
		{
			configManager.setConfiguration(CONFIG_GROUP, TIME_INTERVAL_CONFIG_KEY, statPanel.getSelectedInterval());
		}
	}

	//Loads previous session data to tradeList.
	public void loadConfig()
	{
		log.info("Loading Flipping config");
		final String json = configManager.getConfiguration(CONFIG_GROUP, ITEMS_CONFIG_KEY);
		statPanel.setTimeInterval(configManager.getConfiguration(CONFIG_GROUP, TIME_INTERVAL_CONFIG_KEY));

		if (json == null)
		{
			return;
		}

		try
		{
			final Gson gson = new Gson();
			Type type = new TypeToken<ArrayList<FlippingItem>>()
			{

			}.getType();
			tradesList = gson.fromJson(json, type);
		}
		catch (Exception e)
		{
			log.info("Error loading flipping data: " + e);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		//Ensure that user configs are updated after being changed
		if (event.getGroup().equals(CONFIG_GROUP))
		{
			if (event.getKey().equals(ITEMS_CONFIG_KEY) || event.getKey().equals(TIME_INTERVAL_CONFIG_KEY))
			{
				return;
			}

			statPanel.rebuild(tradesList);
			flippingPanel.rebuildFlippingPanel(tradesList);
		}
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		//Check that it was the chat input that got enabled.
		if (event.getIndex() != VarClientInt.INPUT_TYPE.getIndex()
			|| client.getWidget(WidgetInfo.CHATBOX_TITLE) == null
			|| client.getVarcIntValue(VarClientInt.INPUT_TYPE.getIndex()) != 7)
		{
			return;
		}

		clientThread.invokeLater(() ->
		{

			flippingWidget = new FlippingItemWidget(client.getWidget(WidgetInfo.CHATBOX_CONTAINER), client);


			FlippingItem selectedItem = null;
			//Check that if we've recorded any data for the item.
			for (FlippingItem item : tradesList)
			{
				if (item.getItemId() == client.getVar(CURRENT_GE_ITEM))
				{
					selectedItem = item;
					break;
				}
			}

			String chatInputText = client.getWidget(WidgetInfo.CHATBOX_TITLE).getText();
			String offerText = client.getWidget(WidgetInfo.GRAND_EXCHANGE_OFFER_CONTAINER).getChild(GE_OFFER_INIT_STATE_CHILD_ID).getText();
			if (chatInputText.equals("How many do you wish to buy?"))
			{
				//No recorded data; default to total GE limit
				if (selectedItem == null)
				{
					ItemStats itemStats = itemManager.getItemStats(client.getVar(CURRENT_GE_ITEM), false);
					int itemGELimit = itemStats != null ? itemStats.getGeLimit() : 0;
					flippingWidget.showWidget("setCurrentQuantityInTrade", itemGELimit);
				}
				else
				{
					flippingWidget.showWidget("setCurrentQuantityInTrade", selectedItem.remainingGeLimit());
				}
			}
			else if (chatInputText.equals("Set a price for each item:"))
			{
				if (offerText.equals("Buy offer"))
				{
					//No recorded data; hide the widget
					if (selectedItem == null || selectedItem.getMarginCheckBuyPrice() == 0)
					{
						flippingWidget.showWidget("reset", 0);
					}
					else
					{
						flippingWidget.showWidget("setBuyPrice", selectedItem.getMarginCheckBuyPrice());
					}
				}
				else if (offerText.equals("Sell offer"))
				{
					//No recorded data; hide the widget
					if (selectedItem == null || selectedItem.getMarginCheckSellPrice() == 0)
					{
						flippingWidget.showWidget("reset", 0);
					}
					else
					{
						flippingWidget.showWidget("setSellPrice", selectedItem.getMarginCheckSellPrice());
					}
				}
			}
		});
	}
}