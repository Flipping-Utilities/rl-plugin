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

package com.flippingutilities.model;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;

/**
 * This class is the representation of an item that a user is flipping. It contains information about the
 * margin of the item (buying and selling price), the latest buy and sell times, and the history of the item
 * which is all of the offers that make up the trade history of that item. This history is managed by the
 * {@link HistoryManager} and is used to get the profits for this item, how many more of it you can buy
 * until the ge limit refreshes, and when the next ge limit refreshes.
 * <p>
 * This class is the model behind a FlippingItemPanel as its data is used to create the contents
 * of a panel which is then displayed.
 */
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
public class FlippingItem
{

	@SerializedName("id")
	@Getter
	private int itemId;

	@SerializedName("name")
	@Getter
	@Setter
	private String itemName;

	@SerializedName("tGL")
	@Getter
	@Setter
	private int totalGELimit;

	@SerializedName("h")
	@Getter
	@Setter
	private HistoryManager history = new HistoryManager();

	@SerializedName("fB")
	@Getter
	private String flippedBy;

	//whether the item should be on the flipping panel or not.
	@SerializedName("vFPI")
	@Getter
	@Setter
	private Boolean validFlippingPanelItem;

	@Getter
	@Setter
	private boolean favorite;

	@Getter
	@Setter
	private String favoriteCode = "1";

	//non persisted fields start here.
	@Setter
	@Getter
	private transient Optional<OfferEvent> latestInstaBuy;

	@Setter
	@Getter
	private transient Optional<OfferEvent> latestInstaSell;

	@Setter
	@Getter
	private transient Optional<OfferEvent> latestBuy;

	@Setter
	@Getter
	private transient Optional<OfferEvent> latestSell;

	//does not have to Optional because a flipping item always has at least one offer, which establishes
	//latestActivityTime.
	@Getter
	private transient Instant latestActivityTime;

	@Getter
	@Setter
	private transient Boolean expand;

	public FlippingItem(int itemId, String itemName, int totalGeLimit, String flippedBy)
	{
		this.latestInstaBuy = Optional.empty();
		this.latestInstaSell = Optional.empty();
		this.latestBuy = Optional.empty();
		this.latestSell = Optional.empty();
		this.itemName = itemName;
		this.itemId = itemId;
		this.totalGELimit = totalGeLimit;
		this.flippedBy = flippedBy;
	}

	public FlippingItem shallowClone() {
		return new FlippingItem(
				itemId,
				itemName,
				totalGELimit,
				history,
				flippedBy,
				validFlippingPanelItem,
				favorite,
				favoriteCode,
				latestInstaBuy,
				latestInstaSell,
				latestBuy,
				latestSell,
				latestActivityTime,
				expand);
	}

	public FlippingItem clone()
	{
		return new FlippingItem(
				itemId,
				itemName,
				totalGELimit,
				history.clone(),
				flippedBy,
				validFlippingPanelItem,
				favorite,
				favoriteCode,
				latestInstaBuy,
				latestInstaSell,
				latestBuy,
				latestSell,
				latestActivityTime,
				expand);
	}

	/**
	 * This method updates the history of a FlippingItem. This history is used to calculate profits,
	 * next ge limit refresh, and how many items were bought during this limit window.
	 *
	 * @param newOffer the new offer that just came in
	 */
	public void updateHistory(OfferEvent newOffer)
	{
		newOffer.setItemName(itemName);
		history.updateHistory(newOffer);
	}

	/**
	 * Updates the latest margin check/buy/sell offers. Technically, we don't need this and we can just
	 * query the history manager, but this saves us from querying the history manager which would have
	 * to search through the offers.
	 *
	 * @param newOffer new offer just received
	 */
	public void updateLatestProperties(OfferEvent newOffer)
	{
		if (newOffer.isBuy())
		{
			if (newOffer.isMarginCheck())
			{
				latestInstaBuy = Optional.of(newOffer);
			}
			latestBuy = Optional.of(newOffer);
		}
		else
		{
			if (newOffer.isMarginCheck())
			{
				latestInstaSell = Optional.of(newOffer);
			}
			latestSell = Optional.of(newOffer);
		}
		latestActivityTime = newOffer.getTime();
	}

	/**
	 * combines two flipping items together (this only makes sense if they are for the same item) by adding
	 * their histories together and retaining the other properties of the latest active item.
	 *
	 * @return merged flipping item
	 */
	public static FlippingItem merge(FlippingItem item1, FlippingItem item2)
	{
		if (item1 == null)
		{
			return item2;
		}

		if (item1.getLatestActivityTime().compareTo(item2.getLatestActivityTime()) >= 0)
		{
			item1.getHistory().getCompressedOfferEvents().addAll(item2.getHistory().getCompressedOfferEvents());
			item1.setFavorite(item1.isFavorite() || item2.isFavorite());
			item1.getHistory().getPersonalCombinationFlips().addAll(item2.getHistory().getPersonalCombinationFlips());
			item1.getHistory().getParentCombinationFlips().addAll(item2.getHistory().getParentCombinationFlips());
			return item1;
		}
		else
		{
			item2.getHistory().getCompressedOfferEvents().addAll(item1.getHistory().getCompressedOfferEvents());
			item2.setFavorite(item2.isFavorite() || item1.isFavorite());
			item2.getHistory().getPersonalCombinationFlips().addAll(item1.getHistory().getPersonalCombinationFlips());
			item2.getHistory().getParentCombinationFlips().addAll(item1.getHistory().getParentCombinationFlips());
			return item2;
		}
	}

	public long getProfit(List<OfferEvent> tradeList)
	{
		return history.getProfit(tradeList);
	}

	public long getValueOfMatchedOffers(List<OfferEvent> tradeList, boolean buyState)
	{
		return history.getValueOfMatchedOffers(tradeList, buyState);
	}

	public long getTotalRevenueOrExpense(List<OfferEvent> tradeList, boolean isBuy)
	{
		return history.getTotalRevenueOrExpense(tradeList, isBuy);
	}

	public int countFlipQuantity(List<OfferEvent> tradeList)
	{
		return history.countFlipQuantity(tradeList);
	}

	public List<Flip> getFlips(List<OfferEvent> tradeList)
	{
		return history.getFlips(tradeList);
	}

	public List<OfferEvent> getPartialOfferAdjustedView(List<OfferEvent> tradeList) {
		return history.getPartialOfferAdjustedView(tradeList);
	}

	public List<CombinationFlip> getCombinationFlips(Instant earliestTime) {
		return history.getCombinationFlips(earliestTime);
	}

	public List<CombinationFlip> getPersonalCombinationFlips(Instant earliestTime) {
		return history.getPersonalCombinationFlips(earliestTime);
	}

	public ArrayList<OfferEvent> getIntervalHistory(Instant earliestTime)
	{
		return history.getIntervalsHistory(earliestTime);
	}

	public int getRemainingGeLimit()
	{
		return totalGELimit - history.getItemsBoughtThisLimitWindow();
	}

	public int getItemsBoughtThisLimitWindow()
	{
		return history.getItemsBoughtThisLimitWindow();
	}

	public Instant getGeLimitResetTime()
	{
		return history.getNextGeLimitRefresh();
	}

	public void validateGeProperties()
	{
		history.validateGeProperties();
	}

	public boolean hasValidOffers()
	{
		return history.hasValidOffers();
	}

	public boolean hasOfferInInterval(Instant earliestTime) {
		return history.hasOfferInInterval(earliestTime);
	}

	public void removeInvalidOffers() {
		history.removeInvalidOffers();
	}

	/**
	 * see the documentation for HistoryManager.invalidateOffers
	 */
	public void invalidateOffers(List<OfferEvent> offerList, List<FlippingItem> items)
	{
		history.invalidateOffers(offerList, items);
	}
	public void setValidFlippingPanelItem(boolean isValid)
	{
		validFlippingPanelItem = isValid;
		if (!isValid)
		{
			latestInstaBuy = Optional.empty();
			latestInstaSell = Optional.empty();
			latestBuy = Optional.empty();
			latestSell = Optional.empty();
		}
	}

	public Optional<Integer> getPotentialProfit(boolean includeMarginCheck, boolean shouldUseRemainingGeLimit)
	{
		if (!getLatestInstaBuy().isPresent() || !getLatestInstaSell().isPresent()) {
			return Optional.empty();
		}

		int profitEach = getCurrentProfitEach().get();
		int remainingGeLimit = getRemainingGeLimit();
		int geLimit = shouldUseRemainingGeLimit ? remainingGeLimit : totalGELimit;
		int profitTotal = geLimit * profitEach;
		if (includeMarginCheck)
		{
			profitTotal -= profitEach;
		}
		return Optional.of(profitTotal);
	}

	public List<OfferEvent> getOfferMatches(OfferEvent offerEvent, int limit)
	{
		return history.getOfferMatches(offerEvent, limit);
	}

	public Optional<Float> getCurrentRoi() {
		return getCurrentProfitEach().isPresent()?
				Optional.of((float)getCurrentProfitEach().get() / getLatestInstaSell().get().getPrice() * 100) : Optional.empty();
	}

	public Optional<Integer> getCurrentProfitEach() {
		return getLatestInstaBuy().isPresent() && getLatestInstaSell().isPresent()?
				Optional.of(getLatestInstaBuy().get().getPrice() - getLatestInstaSell().get().getPrice()) : Optional.empty();
	}

	/**
	 * When the plugin starts up, the flipping items are constructed, but they are going to be missing
	 * values for certain fields that aren't persisted. I chose not to persist those fields as those fields
	 * can be constructed using the history that is already persisted. The downside is that I have to
	 * manually sync state when flipping items are created at plugin startup.
	 */
	private void syncState() {
		latestBuy = history.getLatestOfferThatMatchesPredicate(offer -> offer.isBuy());
		latestSell = history.getLatestOfferThatMatchesPredicate(offer -> !offer.isBuy());
		latestInstaBuy = history.getLatestOfferThatMatchesPredicate(offer -> offer.isBuy() & offer.isMarginCheck());
		latestInstaSell = history.getLatestOfferThatMatchesPredicate(offer -> !offer.isBuy() & offer.isMarginCheck());
		latestActivityTime = history.getCompressedOfferEvents().size() == 0? Instant.now() : history.getCompressedOfferEvents().get(history.getCompressedOfferEvents().size()-1).getTime();
	}

	private void setOfferMadeBy() {
		history.setOfferMadeBy(flippedBy);
	}

	private void setOfferIds() {
		history.getCompressedOfferEvents().forEach(o -> {
			if (o.getUuid() == null) {
				o.setUuid(UUID.randomUUID().toString());
			}
		});
	}

	private void setOfferNames(Map<Integer, String> idToItemName) {
		history.setOfferNames(itemName);
		history.setCombinationFlipOfferNames(idToItemName);
	}

	public void addPersonalCombinationFlip(CombinationFlip combinationFlip) {
		history.addPersonalCombinationFlip(combinationFlip);
	}

	public void addParentCombinationFlip(CombinationFlip combinationFlip) {
		history.addParentCombinationFlip(combinationFlip);
	}

	public void deleteParentCombinationFlip(CombinationFlip combinationFlip) {
		history.deleteParentCombinationFlip(combinationFlip);
	}

	public void deletePersonalCombinationFlip(CombinationFlip combinationFlip) {
		history.deletePersonalCombinationFlip(combinationFlip);
	}

	public Map<String, PartialOffer> getOfferIdToPartialOfferInPersonalComboFlips() {
		return history.getOfferIdToPartialOfferInPersonalComboFlips();
	}

	public Map<String, PartialOffer> getOfferIdToPartialOfferInComboFlips() {
		return history.getOfferIdToPartialOfferInComboFlips();
	}

	/**
	 * There are several fields we don't persist in offer events, so we need to fill them in
	 * at plugin start. Additionally, due to schema evolution such as fields being added, we have to
	 * fill those new fields with default values. I think gson should do this when deserializing already, but
	 * I ran into some issues with it some time ago and am too lazy to re-explore...
	 */
	public void hydrate(Map<Integer, String> idToItemName, int geLimit) {
		setTotalGELimit(geLimit);
		syncState();
		setOfferIds();
		setOfferNames(idToItemName);
		setOfferMadeBy();
		//when this change was made the field will not exist and will be null
		if (validFlippingPanelItem == null)
		{
			validFlippingPanelItem = true;
		}
	}
}
