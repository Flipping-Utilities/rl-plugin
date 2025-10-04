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

package com.flippingutilities.ui.statistics;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.*;
import com.flippingutilities.ui.statistics.items.FlippingItemPanel;
import com.flippingutilities.ui.statistics.items.FlippingItemContainerPanel;
import com.flippingutilities.ui.statistics.recipes.RecipeFlipGroupPanel;
import com.flippingutilities.ui.statistics.recipes.RecipeGroupContainerPanel;
import com.flippingutilities.ui.uiutilities.*;
import com.flippingutilities.utilities.SORT;
import com.flippingutilities.utilities.Searchable;
import com.google.common.base.Strings;
import net.runelite.client.ui.components.TitleCaseListCellRenderer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.StyleContext;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class StatsPanel extends JPanel
{
	private static final String[] TIME_INTERVAL_STRINGS = {"-1h (Past Hour)", "-4h (Past 4 Hours)", "-12h (Past 12 Hours)", "-1d (Past Day)", "-1w (Past Week)", "-1m (Past Month)", "Session", "All"};
	private static final Dimension ICON_SIZE = new Dimension(16, 16);

	private FlippingPlugin plugin;

	//Holds the sub info labels.
	private JPanel subInfoPanel;

	//Combo box that selects the time interval that startOfInterval contains.
	private JComboBox<String> timeIntervalDropdown = this.createTimeIntervalDropdown();

	//Represents the total profit made in the selected time interval.
	private JLabel totalProfitVal = new JLabel();

	/* Subinfo text labels */
	private final JLabel hourlyProfitText = new JLabel("Hourly Profit: ");
	private final JLabel roiText = new JLabel("ROI: ");
	private final JLabel totalFlipsText = new JLabel("Total Flips Made: ");
	private final JLabel taxPaidText = new JLabel("Tax paid: ");
	private final JLabel sessionTimeText = new JLabel("Session Time: ");
	private final JLabel autoSaveText = new JLabel("Next auto-save: ");
	private final JLabel[] textLabelArray = {hourlyProfitText, roiText, totalFlipsText, taxPaidText, sessionTimeText};

	/* Subinfo value labels */
	private final JLabel hourlyProfitVal = new JLabel("", SwingConstants.RIGHT);
	private final JLabel roiVal = new JLabel("", SwingConstants.RIGHT);
	private final JLabel totalFlipsVal = new JLabel("", SwingConstants.RIGHT);
	private final JLabel taxPaidVal = new JLabel("", SwingConstants.RIGHT);
	private final JLabel sessionTimeVal = new JLabel("", SwingConstants.RIGHT);
	private final JLabel autoSaveVal = new JLabel("", SwingConstants.RIGHT);
	private final JLabel[] valLabelArray = {hourlyProfitVal, roiVal, totalFlipsVal, taxPaidVal, sessionTimeVal};

	private final JPanel hourlyProfitPanel = new JPanel(new BorderLayout());
	private final JPanel roiPanel = new JPanel(new BorderLayout());
	private final JPanel totalFlipsPanel = new JPanel(new BorderLayout());
	private final JPanel taxPaidPanel = new JPanel(new BorderLayout());
	private final JPanel sessionTimePanel = new JPanel(new BorderLayout());
	private final JPanel autoSavePanel = new JPanel(new BorderLayout());
	private final JPanel[] subInfoPanelArray = {hourlyProfitPanel, roiPanel, totalFlipsPanel, taxPaidPanel, sessionTimePanel};

	//Contains the unix time of the start of the interval.
	@Getter
	private Instant startOfInterval;
	@Getter
	private String startOfIntervalName = "Session";

	@Getter
	private SORT selectedSort = SORT.TIME;

	@Getter
	private Set<String> expandedItems = new HashSet<>();
	@Getter
	private Set<String> expandedTradeHistories = new HashSet<>();
	@Getter
	private Set<Integer> itemsWithOffersTabSelected = new HashSet<>();

	private boolean currentlySearching;
	private IconTextField searchBar;
	private FlippingItemContainerPanel flippingItemContainerPanel;
	private RecipeGroupContainerPanel recipeGroupContainerPanel;
	/**
	 * The statistics panel shows various stats about trades the user has made over a selectable time interval.
	 * This represents the front-end Statistics Tab.
	 * It is shown when it has been selected by the tab manager.
	 *
	 * @param plugin  Used to access the config and list of trades.
	 */
	public StatsPanel(final FlippingPlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		this.startOfInterval = plugin.viewStartOfSessionForCurrentView();
		this.prepareLabels();

		searchBar = createSearchBar();
		flippingItemContainerPanel = new FlippingItemContainerPanel(plugin);
		recipeGroupContainerPanel = new RecipeGroupContainerPanel(plugin);

		JPanel mainDisplay = new JPanel();
		FastTabGroup tabGroup = createTabGroup(mainDisplay, flippingItemContainerPanel, recipeGroupContainerPanel);

		setLayout(new BorderLayout());
		add(createTopPanel(searchBar), BorderLayout.NORTH);
		add(createTabGroupContainer(tabGroup, mainDisplay), BorderLayout.CENTER);
		setBorder(new EmptyBorder(5,7,0,7));
	}

	private JPanel createTabGroupContainer(FastTabGroup tabGroup, JPanel mainDisplay) {
		JPanel tabGroupContainer = new JPanel(new BorderLayout());

		JPanel tabGroupPanel = new JPanel(new BorderLayout());
		tabGroupPanel.add(tabGroup, BorderLayout.CENTER);
		tabGroupPanel.add(createSortIcon(), BorderLayout.EAST);

		tabGroupContainer.add(tabGroupPanel, BorderLayout.NORTH);
		tabGroupContainer.add(mainDisplay, BorderLayout.CENTER);

		return tabGroupContainer;
	}

	private JLabel createSortIcon() {
		JLabel sortIcon = new JLabel(Icons.SORT);
		sortIcon.setBorder(new EmptyBorder(0,0,0,15));
		sortIcon.setToolTipText("Use this to sort the list!");

		JPopupMenu popupMenu = new JPopupMenu("Sort");
		//handles deselecting the other buttons when one is selected
		ButtonGroup group = new ButtonGroup();
		Stream.of(SORT.values()).forEach(sortEnum -> {
			JMenuItem menuItem = new JRadioButtonMenuItem(sortEnum.name().replace("_", " "));
			menuItem.setFont(new Font("Whitney", Font.PLAIN, 12));
			group.add(menuItem);
			if (sortEnum == SORT.TIME) {
				menuItem.setSelected(true);
			}
			menuItem.addItemListener(i -> {
				if (i.getStateChange() == ItemEvent.SELECTED) {
					selectedSort = sortEnum;
					this.rebuildItemsDisplay(plugin.viewItemsForCurrentView());
					this.rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());
				}
			});
			popupMenu.add(menuItem);
		});

		sortIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (SwingUtilities.isLeftMouseButton(e)) {
					popupMenu.show(sortIcon, e.getX(), e.getY());
				}
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				sortIcon.setIcon(Icons.SORT_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				sortIcon.setIcon(Icons.SORT);
			}
		});
		return sortIcon;
	}

	private FastTabGroup createTabGroup(JPanel mainDisplay, FlippingItemContainerPanel statItemTabPanel, RecipeGroupContainerPanel recipeTabPanel) {
		FastTabGroup tabGroup = new FastTabGroup(mainDisplay);
		tabGroup.setBorder(new EmptyBorder(0,16 + 15,0,0));

		MaterialTab statItemTab = new MaterialTab("Items", tabGroup, statItemTabPanel);
		MaterialTab RecipeTab = new MaterialTab("Recipes", tabGroup, recipeTabPanel);

		tabGroup.addTab(statItemTab);
		tabGroup.addTab(RecipeTab);
		tabGroup.select(statItemTab);

		return tabGroup;
	}

	public void resetPaginators() {
		flippingItemContainerPanel.resetPaginator();
		recipeGroupContainerPanel.resetPaginator();
	}

	public void rebuildItemsDisplay(List<FlippingItem> flippingItems) {
		SwingUtilities.invokeLater(() -> {
			List<FlippingItem> itemsToDisplay = getItemsToDisplay(flippingItems);
			flippingItemContainerPanel.rebuild(itemsToDisplay);
			updateCumulativeDisplays(itemsToDisplay, getRecipeFlipGroupsToDisplay(plugin.viewRecipeFlipGroupsForCurrentView()));
			if (itemsToDisplay.isEmpty() && currentlySearching) flippingItemContainerPanel.showPanel(createEmptySearchPanel());
			revalidate();
			repaint();
		});
	}

	public void rebuildRecipesDisplay(List<RecipeFlipGroup> recipeFlipGroups) {
		SwingUtilities.invokeLater(() -> {
			List<RecipeFlipGroup> recipeFlipGroupsToDisplay = getRecipeFlipGroupsToDisplay(recipeFlipGroups);
			recipeGroupContainerPanel.rebuild(recipeFlipGroupsToDisplay);
			updateCumulativeDisplays(getItemsToDisplay(plugin.viewItemsForCurrentView()), recipeFlipGroupsToDisplay);
			if (recipeFlipGroupsToDisplay.isEmpty() && currentlySearching) recipeGroupContainerPanel.showPanel(createEmptySearchPanel());
			revalidate();
			repaint();
		});
	}

	/**
	 * The panel shown when a user's search query returns no results.
	 */
	private JPanel createEmptySearchPanel() {
		JPanel emptySearchPanel = new JPanel(new DynamicGridLayout(2,1));
		emptySearchPanel.setBorder(new EmptyBorder(10,0,0,0));
		String lookup = searchBar.getText().toLowerCase();
		JLabel searchLabel = new JLabel(String.format(
				"<html><body style='text-align: center'>The search for <br> <b><u>%s</u></b> <br> yielded no results :(</html>", lookup),
				SwingConstants.CENTER);
		searchLabel.setFont(new Font("Whitney", Font.PLAIN, 12));
		searchLabel.setBorder(new EmptyBorder(0,0,10,0));

		emptySearchPanel.add(searchLabel);
		emptySearchPanel.add(new JLabel(Icons.GNOME_CHILD));

		return emptySearchPanel;
	}

	private void updateSearch(IconTextField searchBar)
	{
		String lookup = searchBar.getText().toLowerCase();

		//When the clear button is pressed, this is run.
		if (Strings.isNullOrEmpty(lookup)) {
			searchBar.setIcon(IconTextField.Icon.SEARCH);
			currentlySearching = false;
			this.flippingItemContainerPanel.resetPaginator();
			this.recipeGroupContainerPanel.resetPaginator();
			this.rebuildItemsDisplay(plugin.viewItemsForCurrentView());
			this.rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());
			return;
		}
		currentlySearching = true;
		this.flippingItemContainerPanel.resetPaginator();
		this.recipeGroupContainerPanel.resetPaginator();
		this.rebuildItemsDisplay(plugin.viewItemsForCurrentView());
		this.rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());
	}

	private <T extends Searchable> List<T> getSearchResults(List<T> objs, String lookup) {
		return objs.stream().filter(
			obj ->
				obj.getNameForSearch().toLowerCase().contains(lookup) && obj.isInInterval(startOfInterval)).
			collect(Collectors.toList());
	}

	/**
	 * Filters the items based on what is currently being searched for. If nothing is being searched
	 * for, it just returns the original list
	 * @param objs all the objs (flipping item or recipe flip group) that could be in the current view
	 */
	private <T extends Searchable> List<T> getResultsForCurrentSearchQuery(List<T> objs) {
		String lookup = searchBar.getText().toLowerCase();
		if (currentlySearching && !Strings.isNullOrEmpty(lookup)) {
			return getSearchResults(objs, lookup);
		}
		return objs;
	}

	public List<FlippingItem> getItemsToDisplay(List<FlippingItem> items) {
		return plugin.sortItems(getResultsForCurrentSearchQuery(getObjsInInterval(items)), selectedSort, startOfInterval);
	}

	public List<RecipeFlipGroup> getRecipeFlipGroupsToDisplay(List<RecipeFlipGroup> recipeFlipGroups) {
		return plugin.sortRecipeFlipGroups(getResultsForCurrentSearchQuery(getObjsInInterval(recipeFlipGroups)), selectedSort, startOfInterval);
	}

	private IconTextField createSearchBar() {
		IconTextField searchBar = UIUtilities.createSearchBar(plugin.getExecutor(), this::updateSearch);
		searchBar.setBorder(BorderFactory.createMatteBorder(1,1,1,1, ColorScheme.DARKER_GRAY_COLOR.darker()));
		return searchBar;
	}

	private JComboBox createTimeIntervalDropdown() {
		JComboBox<String> timeIntervalDropdown = new JComboBox<>(TIME_INTERVAL_STRINGS);
		timeIntervalDropdown.setRenderer(new TitleCaseListCellRenderer());
		timeIntervalDropdown.setEditable(true);
		timeIntervalDropdown.setBorder(BorderFactory.createMatteBorder(1,1,1,1, ColorScheme.DARKER_GRAY_COLOR.darker()));
		timeIntervalDropdown.setBackground(CustomColors.DARK_GRAY_LIGHTER);
		//setting the selected item as session before the item listener is attached so it doesn't fire a rebuildItemsDisplay.
		timeIntervalDropdown.setSelectedItem("Session");
		timeIntervalDropdown.setToolTipText("Specify the time span you would like to see the statistics of");
		timeIntervalDropdown.addItemListener(event ->
		{
			if (event.getStateChange() == ItemEvent.SELECTED)
			{
				String interval = (String) event.getItem();
				if (interval == null) {
					return;
				}
				//remove the helper text. so something like "1w (Past week)" becomes just "1w"
				String justTheInterval = interval.split(" \\(")[0];
				ItemListener[] itemListeners = timeIntervalDropdown.getItemListeners();
				//have to remove item listeners so setSelectedItem doesn't cause another rebuildItemsDisplay.
				for (ItemListener listener : itemListeners) {
					timeIntervalDropdown.removeItemListener(listener);
				}
				timeIntervalDropdown.setSelectedItem(justTheInterval);
				for (ItemListener itemListener : itemListeners) {
					timeIntervalDropdown.addItemListener(itemListener);
				}
				setTimeInterval(justTheInterval);
			}
		});
		return timeIntervalDropdown;
	}

	public void updateCumulativeDisplays(List<FlippingItem> tradesList, List<RecipeFlipGroup> recipeFlipGroups)
	{
		subInfoPanel.remove(autoSavePanel);

		if (!Objects.equals(timeIntervalDropdown.getSelectedItem(), "Session"))
		{
			subInfoPanel.remove(sessionTimePanel);
			subInfoPanel.remove(hourlyProfitPanel);
		}
		else {
			subInfoPanel.add(sessionTimePanel);
			subInfoPanel.add(hourlyProfitPanel);
		}

		long totalProfit = 0;
		long totalExpenses = 0;
		long totalFlips = 0;
		long taxPaid = 0;

		for (FlippingItem item : tradesList)
		{
			Map<String, PartialOffer> offerIdToPartialOffer = plugin.getOfferIdToPartialOffer(item.getItemId());
			List<OfferEvent> intervalHistory = item.getIntervalHistory(startOfInterval);
			if (intervalHistory.isEmpty()) {
				continue;
			}

			List<OfferEvent> adjustedOffers = FlippingItem.getPartialOfferAdjustedView(intervalHistory, offerIdToPartialOffer);

			taxPaid += adjustedOffers.stream().mapToLong(OfferEvent::getTaxPaid).sum();
			totalProfit += FlippingItem.getProfit(adjustedOffers);
			totalExpenses += FlippingItem.getValueOfMatchedOffers(adjustedOffers, true);
			totalFlips += FlippingItem.getFlips(adjustedOffers).size();
		}

		for (RecipeFlipGroup recipeFlipGroup : recipeFlipGroups) {
			List<RecipeFlip> recipeFlips = recipeFlipGroup.getFlipsInInterval(startOfInterval);
			if (recipeFlips.isEmpty()) continue;
			taxPaid += recipeFlips.stream().mapToLong(RecipeFlip::getTaxPaid).sum();
			totalProfit += recipeFlips.stream().mapToLong(RecipeFlip::getProfit).sum();
			totalExpenses += recipeFlips.stream().mapToLong(RecipeFlip::getExpense).sum();
			totalFlips += recipeFlips.size();
		}

		updateTotalProfitDisplay(totalProfit);
		if (Objects.equals(timeIntervalDropdown.getSelectedItem(), "Session"))
		{
			Duration accumulatedTime = plugin.viewAccumulatedTimeForCurrentView();
			updateSessionTimeDisplay(accumulatedTime);
			updateHourlyProfitDisplay(totalProfit, accumulatedTime);
		}
		updateRoiDisplay(totalProfit, totalExpenses);
		updateTotalFlipsDisplay(totalFlips);
		updateTaxPaidDisplay(taxPaid);
		updateAutoSaveDisplay();
	}

	/**
	 * Responsible for updating the total profit label at the very top.
	 * Sets the new total profit value from the items in tradesList from {@link FlippingPlugin#getItemsForCurrentView()}.
	 */
	private void updateTotalProfitDisplay(long totalProfit)
	{
		if (plugin.viewItemsForCurrentView() == null)
		{
			totalProfitVal.setText("0");
			totalProfitVal.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
			totalProfitVal.setToolTipText("Total Profit: 0 gp");
			return;
		}

		totalProfitVal.setText(((totalProfit >= 0) ? "" : "-") + UIUtilities.quantityToRSDecimalStack(Math.abs(totalProfit), true) + " gp");
		totalProfitVal.setToolTipText("Total Profit: " + QuantityFormatter.formatNumber(totalProfit) + " gp");

		//Reproduce the RuneScape stack size colors
		if (totalProfit < 0)
		{
			//]-inf, 0[
			totalProfitVal.setForeground(CustomColors.OUTDATED_COLOR);
		}
		else if (totalProfit <= 100000)
		{
			//[0,100k)
			totalProfitVal.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		}
		else if (totalProfit <= 10000000)
		{
			//[100k,10m)
			totalProfitVal.setForeground(CustomColors.OFF_WHITE);
		}
		else
		{
			//[10m,inf)
			totalProfitVal.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		}
	}

	/**
	 * Updates the hourly profit value display. Also checks and sets the font color according to profit/loss.
	 */
	private void updateHourlyProfitDisplay(long totalProfit, Duration accumulatedTime)
	{
		String profitString;
		double divisor = accumulatedTime.toMillis() / 1000 * 1.0 / (60 * 60);

		if (divisor != 0)
		{
			profitString = UIUtilities.quantityToRSDecimalStack((long) (totalProfit / divisor), true);
		}
		else
		{
			profitString = "0";
		}

		hourlyProfitVal.setText(profitString + " gp/hr");
		hourlyProfitVal.setForeground(totalProfit >= 0 ? ColorScheme.GRAND_EXCHANGE_PRICE : CustomColors.OUTDATED_COLOR);
		hourlyProfitPanel.setToolTipText("Hourly profit as determined by the session time");
	}

	/**
	 * Updates the total ROI value display. Also checks and sets the font color according to profit/loss.
	 */
	private void updateRoiDisplay(long totalProfit, long totalExpenses)
	{
		float roi = (float) totalProfit / totalExpenses * 100;

		if (totalExpenses == 0)
		{
			roiVal.setText("0.00%");
			roiVal.setForeground(CustomColors.TOMATO);
			return;
		}
		else
		{
			roiVal.setText(String.format("%.2f", (float) totalProfit / totalExpenses * 100) + "%");
		}

		roiVal.setForeground(UIUtilities.gradiatePercentage(roi, plugin.getConfig().roiGradientMax()));
		roiPanel.setToolTipText("<html>Return on investment:<br>Percentage of profit relative to gp invested</html>");
	}

	private void updateTotalFlipsDisplay(long totalFlips)
	{
		totalFlipsVal.setText(QuantityFormatter.formatNumber(totalFlips));
		totalFlipsVal.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		totalFlipsPanel.setToolTipText("<html>Total amount of flips completed" +
			"<br>Does not count margin checks</html>");
	}

	private void updateTaxPaidDisplay(long taxPaid)
	{
		String taxPaidText = UIUtilities.quantityToRSDecimalStack(taxPaid, true) + " gp";
		taxPaidVal.setText(taxPaidText);
		taxPaidVal.setToolTipText("Tax paid after its implementation");
		taxPaidVal.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		taxPaidPanel.setToolTipText("<html>Tax paid after its implementation</html>");
	}

	/**
	 * This is called every second by the executor service in FlippingPlugin
	 */
	public void updateTimeDisplay()
	{
		flippingItemContainerPanel.updateTimeDisplay();
		recipeGroupContainerPanel.updateTimeDisplay();
	}

	/**
	 * This is called by updateSessionTime in FlippingPlugin, which itself is called every second by
	 * the executor service.
	 *
	 * @param accumulatedTime The total time the user has spent flipping since the client started up.
	 */
	public void updateSessionTimeDisplay(Duration accumulatedTime)
	{
		sessionTimeVal.setText(TimeFormatters.formatDuration(accumulatedTime));
	}

	/**
	 * Invalidates a FlippingItems offers for the currently picked time interval.
	 * This means the panel will not be built upon the next rebuildItemsDisplay calls of StatPanel for that time interval.
	 *
	 * @param itemPanel The panel which holds the FlippingItem to be terminated.
	 */
	public void deleteItemPanel(FlippingItemPanel itemPanel) {
		FlippingItem item = itemPanel.getItem();
		plugin.deleteOffers(item.getIntervalHistory(startOfInterval), item);
		this.rebuildItemsDisplay(plugin.viewItemsForCurrentView());
		this.rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());
	}

	public void deleteRecipeFlipGroupPanel(RecipeFlipGroupPanel recipeFlipGroupPanel) {
		recipeFlipGroupPanel.getRecipeFlipGroup().deleteFlips(startOfInterval);
		plugin.setUpdateSinceLastRecipeFlipGroupAccountWideBuild(true);
		plugin.markAccountTradesAsHavingChanged(plugin.getAccountCurrentlyViewed());
		this.rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());
		this.rebuildItemsDisplay(plugin.viewItemsForCurrentView());
	}

	/**
	 * Gets called every time the time interval combobox has its selection changed.
	 * Sets the start interval of the profit calculation.
	 *
	 * @param selectedInterval The string from TIME_INTERVAL_STRINGS that is selected in the time interval combobox
	 */
	public void setTimeInterval(String selectedInterval)
	{
		if (selectedInterval == null)
		{
			return;
		}

		Instant timeNow = Instant.now();

		if (selectedInterval.equals("Session")) {
			startOfInterval = plugin.viewStartOfSessionForCurrentView();
			startOfIntervalName = "Session";
		}
		else if (selectedInterval.equals("All")) {
			startOfInterval = Instant.EPOCH;
			startOfIntervalName = "All";
		}
		else {
			if (selectedInterval.length() < 3) {
				JOptionPane.showMessageDialog(timeIntervalDropdown, "Invalid input. Valid input is a negative whole number followed by an abbreviated unit of time. For example," +
						"-123h or -2d or -55w or -2m or -1y are valid inputs.", "Invalid Input",  JOptionPane.ERROR_MESSAGE);
				return;
			}

			String timeUnitString = String.valueOf(selectedInterval.charAt(selectedInterval.length() - 1));
			if (!TimeFormatters.stringToTimeUnit.containsKey(timeUnitString)) {
				JOptionPane.showMessageDialog(timeIntervalDropdown, "Invalid input. Valid input is a negative whole number followed by an abbreviated unit of time. For example," +
						"-123h or -2d or -55w or -2m or -1y are valid inputs.", "Invalid Input",  JOptionPane.ERROR_MESSAGE);
				return;
			}

			try {
				int amountToSubtract = Integer.parseInt(selectedInterval.substring(1, selectedInterval.length() - 1)) * (int) TimeFormatters.stringToTimeUnit.get(timeUnitString);
				startOfInterval = timeNow.minus(amountToSubtract, ChronoUnit.HOURS);
				startOfIntervalName = selectedInterval;

			} catch (NumberFormatException e) {
				JOptionPane.showMessageDialog(timeIntervalDropdown, "Invalid input. Valid input is a negative whole number followed by an abbreviated unit of time. For example," +
						"-123h or -2d or -55w or -2m or -1y are valid inputs.", "Invalid Input",  JOptionPane.ERROR_MESSAGE);
				return;
			}
		}
		this.flippingItemContainerPanel.resetPaginator();
		this.recipeGroupContainerPanel.resetPaginator();
		this.rebuildItemsDisplay(plugin.viewItemsForCurrentView());
		this.rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());
	}

	/**
	 * Chooses the font that is used for the sub information based on user config.
	 */
	private void updateSubInfoFont()
	{
		for (int i = 0; i < textLabelArray.length; i++)
		{
			textLabelArray[i].setFont(plugin.getFont());
			valLabelArray[i].setFont(plugin.getFont());
		}
	}

	private <T extends Searchable> List<T> getObjsInInterval(List<T> objs) {
		if (objs == null) {
			return new ArrayList<>();
			}

		return objs.stream().filter(obj -> obj != null && obj.isInInterval(startOfInterval)).collect(Collectors.toList());
	}

	private JLabel createResetButton() {
		JLabel resetIcon = new JLabel(Icons.TRASH_ICON_OFF);
		resetIcon.setBorder(new EmptyBorder(0,12,0,0));
		resetIcon.setPreferredSize(Icons.ICON_SIZE);
		resetIcon.setToolTipText("Reset Statistics");
		resetIcon.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					//Display warning message
					final int result = JOptionPane.showOptionDialog(resetIcon, "<html>Are you sure you want to reset the statistics?" +
									"<br>This only resets the statistics within the currently selected time interval</html>",
							"Are you sure?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
							null, new String[]{"Yes", "No"}, "No");

					//If the user pressed "Yes"
					if (result == JOptionPane.YES_OPTION)
					{
						plugin.deleteOffers(startOfInterval);
						StatsPanel.this.rebuildItemsDisplay(plugin.viewItemsForCurrentView());
						StatsPanel.this.rebuildRecipesDisplay(plugin.viewRecipeFlipGroupsForCurrentView());
					}
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				resetIcon.setIcon(Icons.TRASH_ICON);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				resetIcon.setIcon(Icons.TRASH_ICON_OFF);
			}
		});
		return resetIcon;
	}

	private JLabel createDownloadButton() {
		JPanel parent = this;
		JLabel downloadIcon = new JLabel(Icons.DONWLOAD_ICON_OFF);
		downloadIcon.setBorder(new EmptyBorder(0,12,0,0));
		downloadIcon.setPreferredSize(Icons.ICON_SIZE);
		downloadIcon.setToolTipText("Export to CSV");
		downloadIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				JFileChooser f = new JFileChooser();
				f.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				f.showSaveDialog(parent);
				File selectedDirectory = f.getSelectedFile();
				if (selectedDirectory == null) {
					return;
				}
				log.info("exporting to csv in folder {}", f.getSelectedFile());
				try {
					plugin.exportToCsv(f.getSelectedFile(), startOfInterval, startOfIntervalName);
					JOptionPane.showMessageDialog(
							parent,
							String.format("Successfully saved csv file to %s/%s.csv", f.getSelectedFile().toString(), plugin.getAccountCurrentlyViewed()),
							"Successfully saved CSV!",
							JOptionPane.INFORMATION_MESSAGE
					);
				}
				catch (Exception exc) {
					JOptionPane.showMessageDialog(
							parent,
							String.format("Could not save CSV file. Error: %s", exc.toString()),
							"Could not save csv file",
							JOptionPane.ERROR_MESSAGE);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				downloadIcon.setIcon(Icons.DOWNLOAD_ICON);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				downloadIcon.setIcon(Icons.DONWLOAD_ICON_OFF);
			}
		});
		return downloadIcon;
	}

	private JPanel createTopPanel(IconTextField searchBar) {
		JPanel topPanel = new JPanel(new BorderLayout());

		JPanel searchAndDownloadPanel = new JPanel(new BorderLayout());
		searchAndDownloadPanel.add(searchBar, BorderLayout.CENTER);
		searchAndDownloadPanel.add(createResetButton(), BorderLayout.EAST);
		searchAndDownloadPanel.setBorder(new EmptyBorder(5,0,0,0));

		JPanel timeIntervalDropdownAndResetPanel = new JPanel(new BorderLayout());
		timeIntervalDropdownAndResetPanel.add(timeIntervalDropdown, BorderLayout.CENTER);
		timeIntervalDropdownAndResetPanel.add(createDownloadButton(), BorderLayout.EAST);

		topPanel.add(timeIntervalDropdownAndResetPanel, BorderLayout.NORTH);
		topPanel.add(searchAndDownloadPanel, BorderLayout.CENTER);
		topPanel.add(createProfitAndSubInfoContainer(), BorderLayout.SOUTH);
		topPanel.setBorder(new EmptyBorder(0,0,2,0));

		return topPanel;
	}

	private void prepareLabels() {
		Arrays.stream(textLabelArray).forEach(l -> l.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH));

		sessionTimeVal.setText(TimeFormatters.formatDuration(plugin.viewAccumulatedTimeForCurrentView()));
		sessionTimeVal.setPreferredSize(new Dimension(200, 0));
		sessionTimeVal.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);

		autoSaveText.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		autoSaveVal.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);

		//Profit total over the selected time interval
		totalProfitVal.setFont(StyleContext.getDefaultStyleContext()
			.getFont(FontManager.getRunescapeBoldFont().getName(), Font.PLAIN, 28));
		totalProfitVal.setHorizontalAlignment(SwingConstants.CENTER);
		totalProfitVal.setToolTipText("");

		updateSubInfoFont();
	}

	private JPanel createTotalProfitPanel(JPanel subInfoPanel) {
		//Title text for the big total profit label.
		final JLabel profitText = new JLabel("Total Profit: ", SwingConstants.CENTER);
		profitText.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		profitText.setFont(FontManager.getRunescapeBoldFont());

		JLabel arrowIcon = new JLabel(Icons.OPEN_ICON);
		arrowIcon.setPreferredSize(ICON_SIZE);

		//Make sure the profit label is centered
		JLabel padLabel = new JLabel();
		padLabel.setPreferredSize(ICON_SIZE);

		//Formats the profit text and value.
		JPanel profitTextAndVal = new JPanel(new BorderLayout());
		profitTextAndVal.setBackground(CustomColors.DARK_GRAY);
		profitTextAndVal.setBorder(new EmptyBorder(5,0,3,0));
		profitTextAndVal.add(totalProfitVal, BorderLayout.CENTER);
		profitTextAndVal.add(profitText, BorderLayout.NORTH);

		//Contains the total profit information.
		JPanel totalProfitPanel = new JPanel(new BorderLayout());
		totalProfitPanel.setBackground(CustomColors.DARK_GRAY);
		totalProfitPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,1,0,1, ColorScheme.DARKER_GRAY_COLOR.darker()),
				new EmptyBorder(7,0,7,0)));
		totalProfitPanel.add(profitTextAndVal, BorderLayout.CENTER);
		totalProfitPanel.add(arrowIcon, BorderLayout.EAST);
		totalProfitPanel.add(padLabel, BorderLayout.WEST);

		//Controls the collapsible sub info function
		MouseAdapter collapseOnClick = new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					if (subInfoPanel.isVisible())
					{
						//Collapse sub info
						arrowIcon.setIcon(Icons.CLOSE_ICON);
						subInfoPanel.setVisible(false);
						totalProfitPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,1,1,1, ColorScheme.DARKER_GRAY_COLOR.darker()),
								new EmptyBorder(7,0,7,0)));
					}
					else
					{
						//Expand sub info
						arrowIcon.setIcon(Icons.OPEN_ICON);
						subInfoPanel.setVisible(true);
						totalProfitPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,1,0,1, ColorScheme.DARKER_GRAY_COLOR.darker()),
								new EmptyBorder(7,0,7,0)));
					}
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				totalProfitPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				profitTextAndVal.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				totalProfitPanel.setBackground(CustomColors.DARK_GRAY);
				profitTextAndVal.setBackground(CustomColors.DARK_GRAY);
			}
		};

		totalProfitPanel.addMouseListener(collapseOnClick);
		totalProfitVal.addMouseListener(collapseOnClick);

		return totalProfitPanel;
	}

	private JPanel createSubInfoPanel() {
		JPanel subInfoPanel = new JPanel();
		/* Subinfo represents the less-used general historical stats */
		subInfoPanel.setLayout(new DynamicGridLayout(0, 1));

		for (JPanel panel : subInfoPanelArray)
		{
			panel.setBorder(new EmptyBorder(4, 2, 4, 2));
			panel.setBackground(CustomColors.DARK_GRAY);
			//these are added in update displays if the time interval is set to "Session"
			if (panel != hourlyProfitPanel && panel != sessionTimePanel) {
				subInfoPanel.add(panel);
			}
		}

		//All labels should already be sorted in their arrays.
		for (int i = 0; i < subInfoPanelArray.length; i++)
		{
			subInfoPanelArray[i].add(textLabelArray[i], BorderLayout.WEST);
			subInfoPanelArray[i].add(valLabelArray[i], BorderLayout.EAST);
		}

		autoSavePanel.setBorder(new EmptyBorder(4, 2, 4, 2));
		autoSavePanel.setBackground(CustomColors.DARK_GRAY);
		autoSavePanel.add(autoSaveText, BorderLayout.WEST);
		autoSavePanel.add(autoSaveVal, BorderLayout.EAST);

		subInfoPanel.setBackground(CustomColors.DARK_GRAY);
		subInfoPanel.setBorder(new EmptyBorder(9, 5, 5, 5));
		subInfoPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0,2,2,2, ColorScheme.DARKER_GRAY_COLOR.darker()),
				new EmptyBorder(2, 5, 5, 5)));
		return subInfoPanel;
	}

	private JPanel createProfitAndSubInfoContainer() {
		subInfoPanel = this.createSubInfoPanel();
		JPanel profitAndSubInfoContainer = new JPanel(new BorderLayout());
		profitAndSubInfoContainer.add(this.createTotalProfitPanel(subInfoPanel), BorderLayout.NORTH);
		profitAndSubInfoContainer.add(subInfoPanel, BorderLayout.SOUTH);
		profitAndSubInfoContainer.setBorder(new EmptyBorder(5, 0, 5, 0));
		return profitAndSubInfoContainer;
	}

	public void updateAutoSaveDisplay() {
		if (!plugin.getConfig().autoSaveEnabled() || !plugin.getConfig().showAutoSaveDisplay()) {
			subInfoPanel.remove(autoSavePanel);
			revalidate();
			repaint();
			return;
		}

		String displayText = calculateAutoSaveDisplayText();
		autoSaveVal.setText(displayText);

		subInfoPanel.add(autoSavePanel);
		revalidate();
		repaint();
	}

	private String calculateAutoSaveDisplayText() {
		Instant nextSave = plugin.getNextScheduledAutoSave();
		if (nextSave == null) {
			return "Pending";
		}

		long secondsUntilNextSave = calculateSecondsUntilNextSave(nextSave);
		return formatCountdownTime(secondsUntilNextSave);
	}

	private long calculateSecondsUntilNextSave(Instant nextScheduledSave) {
		long seconds = Duration.between(Instant.now(), nextScheduledSave).getSeconds();
		return Math.max(0, seconds);
	}

	private String formatCountdownTime(long secondsUntilNextSave) {
		if (secondsUntilNextSave <= 0) {
			return "00:00";
		}
		long minutes = secondsUntilNextSave / 60;
		long seconds = secondsUntilNextSave % 60;
		return String.format("%02d:%02d", minutes, seconds);
	}
}