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

package com.flippingutilities.ui.flipping;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.offereditor.OfferEditorContainerPanel;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.Paginator;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import com.flippingutilities.utilities.Constants;
import com.flippingutilities.utilities.Searchable;
import com.flippingutilities.utilities.WikiRequest;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.http.api.item.ItemStats;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class FlippingPanel extends JPanel
{
	@Getter
	private static final String WELCOME_PANEL = "WELCOME_PANEL";
	private static final String ITEMS_PANEL = "ITEMS_PANEL";

	private final FlippingPlugin plugin;
	private final ItemManager itemManager;

	public final CardLayout cardLayout = new CardLayout();

	private final JPanel flippingItemsPanel = new JPanel();
	public final JPanel flippingItemContainer = new JPanel(cardLayout);

	//Keeps track of all items currently displayed on the panel.
	private ArrayList<FlippingItemPanel> activePanels = new ArrayList<>();

	@Getter
	@Setter
	private boolean itemHighlighted = false;

	@Getter
	private Paginator paginator;

	@Getter
	private OfferEditorContainerPanel offerEditorContainerPanel;
	private IconTextField searchBar;

	private boolean currentlySearching;
	private boolean favoriteSelected;

	public FlippingPanel(final FlippingPlugin plugin)
	{
		super(false);

		this.plugin = plugin;
		this.itemManager = plugin.getItemManager();


		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		//Holds all the item panels
		flippingItemsPanel.setLayout(new BoxLayout(flippingItemsPanel, BoxLayout.Y_AXIS));
		flippingItemsPanel.setBorder((new EmptyBorder(0, 8, 0, 7)));
		flippingItemsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.add(flippingItemsPanel, BorderLayout.NORTH);

		JScrollPane scrollPane = new JScrollPane(wrapper);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(2, 0));

		//Contains a greeting message when the items panel is empty.
		JPanel welcomeWrapper = new JPanel(new BorderLayout());
		welcomeWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		PluginErrorPanel welcomePanel = new PluginErrorPanel();
		welcomeWrapper.add(welcomePanel, BorderLayout.NORTH);

		//The welcome panel instructs the user on how to use the plugin
		//Shown whenever there are no items on the panel
		welcomePanel.setContent("Flipping Utilities",
			"Make offers for items to show up!");

		flippingItemContainer.add(scrollPane, ITEMS_PANEL);
		flippingItemContainer.add(welcomeWrapper, WELCOME_PANEL);

		searchBar = UIUtilities.createSearchBar(plugin.getExecutor(),
				(sBar) -> plugin.getClientThread().invoke(() -> this.updateSearch(sBar)));
		searchBar.setBorder(BorderFactory.createMatteBorder(1,1,1,1, ColorScheme.DARKER_GRAY_COLOR.darker()));

		final JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBorder(new EmptyBorder(0,8,2,10));
		topPanel.add(searchBar, BorderLayout.CENTER);
		topPanel.add(this.createFavoriteButton(), BorderLayout.EAST);

		paginator = new Paginator(() -> rebuild(plugin.viewItemsForCurrentView()));
		paginator.setBackground(ColorScheme.DARK_GRAY_COLOR);
		paginator.setBorder(new MatteBorder(1,0,0,2, ColorScheme.DARK_GRAY_COLOR.darker()));
		paginator.setPageSize(10);

		//To switch between greeting and items panels
		cardLayout.show(flippingItemContainer, WELCOME_PANEL);
		add(topPanel, BorderLayout.NORTH);
		add(flippingItemContainer, BorderLayout.CENTER);
		add(paginator, BorderLayout.SOUTH);
		setBorder(new EmptyBorder(5,0,0,0));
	}

	/**
	 * Creates and renders the panel using the flipping items in the listed parameter.
	 * An item is only displayed if it contains a valid OfferInfo object in its history.
	 *
	 * @param flippingItems List of flipping items that the rebuildItemsDisplay will render.
	 */
	public void rebuild(List<FlippingItem> flippingItems)
	{
		SwingUtilities.invokeLater(() ->
		{
			activePanels.forEach(p -> p.popup.setVisible(false));
			activePanels.clear();
			flippingItemsPanel.removeAll();
			if (flippingItems == null)
			{
				cardLayout.show(flippingItemContainer, WELCOME_PANEL);
				return;
			}
			int vGap = 8;
			cardLayout.show(flippingItemContainer, ITEMS_PANEL);
			List<FlippingItem> itemsToDisplay = getItemsToDisplay(flippingItems);
			List<FlippingItem> itemsThatShouldHavePanels = itemsToDisplay.stream().filter(item -> item.getValidFlippingPanelItem()).collect(Collectors.toList());
			paginator.updateTotalPages(itemsThatShouldHavePanels.size());
			List<FlippingItem> itemsOnCurrentPage = paginator.getCurrentPageItems(itemsThatShouldHavePanels);
			List<FlippingItemPanel> newPanels = itemsOnCurrentPage.stream().map(item -> new FlippingItemPanel(plugin, itemManager.getImage(item.getItemId()), item)).collect(Collectors.toList());
			flippingItemsPanel.add(Box.createVerticalStrut(vGap));
			UIUtilities.stackPanelsVertically((List) newPanels, flippingItemsPanel, vGap);
			flippingItemsPanel.add(Box.createVerticalStrut(vGap));
			activePanels.addAll(newPanels);

			if (isItemHighlighted()) {
				offerEditorContainerPanel = new OfferEditorContainerPanel(plugin);
				offerEditorContainerPanel.selectPriceEditor();
				flippingItemsPanel.add(offerEditorContainerPanel);
				flippingItemsPanel.add(Box.createVerticalStrut(vGap));
			}

			if (activePanels.isEmpty() && !itemHighlighted)
			{
				cardLayout.show(flippingItemContainer, WELCOME_PANEL);
			}

			revalidate();
			repaint();
		});

	}

	/**
	 * Handles rebuilding the flipping panel when a new offer event comes in. There are several cases
	 * where we don't want to rebuildItemsDisplay either because it is unnecessary or visually annoying for a user.
	 * @param offerEvent the new offer that just came in
	 */
	public void onNewOfferEventRebuild(OfferEvent offerEvent) {
		boolean newOfferEventAlreadyAtTop = activePanels.size() > 0 && activePanels.get(0).getFlippingItem().getItemId() == offerEvent.getItemId();
		if (newOfferEventAlreadyAtTop) {
			refreshPricesForFlippingItemPanel(offerEvent.getItemId());
			return;
		}
		//it's annoying when you have searched an item up or an item is
		//highlighted and then the panel is rebuilt due to an offer coming in. This guard prevents that.
		if (!isItemHighlighted() && !currentlySearching) {
			rebuild(plugin.viewItemsForCurrentView());
		}
	}

	private List<FlippingItem> getItemsToDisplay(List<FlippingItem> tradeList) {
		List<FlippingItem> result = new ArrayList<>(tradeList);
//		if (result.isEmpty()) {
//			return result;
//		}
		if (favoriteSelected && !isItemHighlighted()) {
			result = result.stream().filter(FlippingItem::isFavorite).collect(Collectors.toList());
		}
		result = getResultsForCurrentSearchQuery(result);
		sortByTime(result);
		return result;
	}

	private List<FlippingItem> getResultsForCurrentSearchQuery(List<FlippingItem> items) {
		String lookup = searchBar.getText().toLowerCase();
		if (!currentlySearching || Strings.isNullOrEmpty(lookup)) {
			return items;
		}
		Map<Integer, FlippingItem> currentFlippingItems = items.stream().collect(Collectors.toMap(f -> f.getItemId(), f -> f));
		List<FlippingItem> matchesInHistory = new ArrayList<>();
		List<FlippingItem> matchesNotInHistory = new ArrayList<>();
		for (ItemPrice itemInfo:  itemManager.search(lookup)) {
			if (currentFlippingItems.containsKey(itemInfo.getId())) {
				FlippingItem flippingItem = currentFlippingItems.get(itemInfo.getId());
				flippingItem.setValidFlippingPanelItem(true);
				matchesInHistory.add(flippingItem);
			}
			else {
				FlippingItem dummyFlippingItem = new FlippingItem(itemInfo.getId(), itemInfo.getName(), 0, Constants.DUMMY_ITEM);
				dummyFlippingItem.setValidFlippingPanelItem(true);
				matchesNotInHistory.add(dummyFlippingItem);
			}
		}

		List<FlippingItem> allMatches = new ArrayList<>();
		allMatches.addAll(matchesInHistory);
		allMatches.addAll(matchesNotInHistory);
		return allMatches;
	}

	private void sortByTime(List<FlippingItem> items) {
		items.sort((item1, item2) ->
		{
			if (item1 == null || item2 == null)
			{
				return -1;
			}
			if (item1.getLatestActivityTime() == null || item2.getLatestActivityTime() == null) {
				return -1;
			}

			return item2.getLatestActivityTime().compareTo(item1.getLatestActivityTime());
		});
	}

	//Clears all other items, if the item in the offer setup slot is presently available on the panel
	public void highlightItem(FlippingItem item)
	{
		SwingUtilities.invokeLater(() -> {
			paginator.setPageNumber(1);
			itemHighlighted = true;
			rebuild(Collections.singletonList(item));
		});
	}

	private JLabel createFavoriteButton() {
		JLabel favoriteButton = new JLabel(Icons.SMALL_STAR_OFF_ICON);
		favoriteButton.setBorder(new EmptyBorder(0,5,0,0));
		favoriteButton.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (favoriteSelected) {
					favoriteButton.setIcon(Icons.SMALL_STAR_OFF_ICON);
				}
				else {
					favoriteButton.setIcon(Icons.SMALL_STAR_ON_ICON);
				}
				favoriteSelected = !favoriteSelected;
				rebuild(plugin.viewItemsForCurrentView());
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				if (!favoriteSelected) {
					favoriteButton.setIcon(Icons.SMALL_STAR_HOVER_ICON);
				}
			}

			@Override
			public void mouseExited(MouseEvent e) {
				if (favoriteSelected) {
					favoriteButton.setIcon(Icons.SMALL_STAR_ON_ICON);
				}
				else {
					favoriteButton.setIcon(Icons.SMALL_STAR_OFF_ICON);
				}
			}
		});
		return favoriteButton;
	}

	//This is run whenever the PlayerVar containing the GE offer slot changes to its empty value (-1)
	// or if the GE is closed/history tab opened
	public void dehighlightItem()
	{
		if (!itemHighlighted)
		{
			return;
		}
		itemHighlighted = false;
		rebuild(plugin.viewItemsForCurrentView());
	}

	/**
	 * Checks if a FlippingItem's margins (buy and sell price) are outdated and updates the tooltip.
	 * This method is called in FlippingPlugin every second by the scheduler.
	 */
	public void updateTimerDisplays()
	{
		for (FlippingItemPanel activePanel : activePanels)
		{
			activePanel.updateTimerDisplays();
			activePanel.updateWikiTimeLabels();
		}
	}

	public void updateWikiDisplays(WikiRequest wikiRequest, Instant timeOfRequestCompletion) {
		activePanels.forEach(panel -> panel.updateWikiLabels(wikiRequest, timeOfRequestCompletion));
	}


	private void updateSearch(IconTextField searchBar)
	{
		String lookup = searchBar.getText().toLowerCase();

		//Just so we don't mess with the highlight.
		if (isItemHighlighted())
		{
			currentlySearching = false;
			return;
		}

		//When the clear button is pressed, this is run.
		if (Strings.isNullOrEmpty(lookup))
		{
			currentlySearching = false;
			rebuild(plugin.viewItemsForCurrentView());
			return;
		}

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		currentlySearching = true;
		rebuild(plugin.viewItemsForCurrentView());
	}

	public void refreshPricesForFlippingItemPanel(int itemId) {
		for (FlippingItemPanel panel:activePanels) {
			if (panel.getFlippingItem().getItemId() == itemId) {
				panel.setValueLabels();
			}
		}
	}
}
