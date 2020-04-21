package com.flippingutilities;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Getter;
import net.runelite.api.events.GrandExchangeOfferChanged;

/**
 * Manages the history for an item. This class is responsible for figuring out how much profit a user made for
 * an item along with tracking how many items they bought since the last ge limit refresh and when the
 * next ge limit refresh for this an item will be.
 */
public class HistoryManager
{
	//contains the history for each slot so that when a new offer comes in for a slot, we can use the
	//slot history to figure out how many new items were bought/sold. When a offer with a state that is
	//complete (bought/sold/cancelled buy/cancelled sell) comes in, the history for that slot is removed
	//as the slot is now empty.
	private Map<Integer, List<OfferInfo>> slotHistory = new HashMap<>();

	//a list of standardizedOffers. A standardizedOffer is an offer with a quantity that represents the
	//quantity bought since the last offer. A regular offer just has info from an offerEvent, which gives
	//you the current quantity bought/sold overall in the trade.
	@Getter
	private List<OfferInfo> standardizedOffers = new ArrayList<>();

	@Getter
	private Instant nextGeLimitRefresh;

	//the number of items bought since the last ge limit reset.
	@Getter
	private int itemsBoughtThisLimitWindow;

	/**
	 * This method takes in every new offer that comes and updates the standardized offer list along with
	 * other properties related to the history of an item such as how many items were bought since the last
	 * ge limit refresh and how when the ge limit will reset again. The standardized offer list is used to
	 * calculate profit for the item.
	 *
	 * @param newOffer the OfferInfo object created from the {@link GrandExchangeOfferChanged} event that
	 *                 onGrandExchangeOfferChanged (in FlippingPlugin) receives
	 */
	public void updateHistory(OfferInfo newOffer)
	{
		storeStandardizedOffer(newOffer);
		updateGeProperties();

	}

	/**
	 * Receives an offer, turns it into a standardized offer, and adds it to the standardized offer list.
	 * Standardizing an offer refers to making it reflect the quantity bought/sold since last offer rather
	 * than the current amount bought/sold overall in the trade as is the default information in the OfferInfo
	 * constructed from a grandExchangeOfferChanged event.
	 *
	 * @param newOffer the OfferInfo object created from the {@link GrandExchangeOfferChanged} event that
	 *                 onGrandExchangeOfferChanged (in FlippingPlugin) receives. It is crucial to note that
	 *                 This OfferInfo object contains the current quantity bought/sold for the trade currently.
	 */
	private void storeStandardizedOffer(OfferInfo newOffer)
	{
		int newOfferSlot = newOffer.getSlot();

		//if there are currently trades in progress in that slot
		if (slotHistory.containsKey(newOfferSlot))
		{

			List<OfferInfo> currentTradesForSlot = slotHistory.get(newOfferSlot);
			OfferInfo lastOffer = currentTradesForSlot.get(currentTradesForSlot.size() - 1);
			OfferInfo standardizedOffer = newOffer.standardizeOffer(lastOffer);
			standardizedOffers.add(standardizedOffer);
			currentTradesForSlot.add(newOffer);

			//if the offer is complete, clear the history for that slot.
			if (newOffer.isComplete())
			{
				slotHistory.remove(newOfferSlot);
			}
		}

		//its the first trade for that slot!
		else
		{
			//don't need to standardize as its quantity represents the quantity bought as its the first
			//trade in that slot.
			standardizedOffers.add(newOffer);

			//if the offer was a complete offer there's no need to add it to the slot history as a complete
			//offer means the slot history is over.
			if (!newOffer.isComplete())
			{
				slotHistory.put(newOfferSlot, new ArrayList<>(Arrays.asList(newOffer)));

			}
		}

	}

	/**
	 * Updates when the ge limit will refresh and how many items have been bought since the last
	 * ge limit refresh.
	 */
	private void updateGeProperties()
	{
		OfferInfo mostRecentOffer = standardizedOffers.get(standardizedOffers.size() - 1);
		if (!mostRecentOffer.isBuy())
		{
			return;
		}
		// when the time of the last offer (most recent offer) is greater than nextGeLimitRefresh,
		// you know the ge limits have refreshed. Since this is the first offer after the ge limits
		// have refreshed, the next refresh will be four hours after this offer's buy time.
		if (nextGeLimitRefresh == null || mostRecentOffer.getTime().compareTo(nextGeLimitRefresh) > 0)
		{
			nextGeLimitRefresh = mostRecentOffer.getTime().plus(4, ChronoUnit.HOURS);
			itemsBoughtThisLimitWindow = mostRecentOffer.getQuantity();
		}
		//if the last offer (most recent offer) is before the next ge limit refresh, add its quantity to the
		//amount bought this limit window.
		else
		{
			itemsBoughtThisLimitWindow += mostRecentOffer.getQuantity();
		}

	}

	//TODO:
	// return a summary, not just the profit. A summary will include the profit, and the quantity of buys/sells
	// and the individual prices (only if they are different)

	/**
	 * Calculates profit for this item by looking at the standardizedOffers list, filtering out offers
	 * that are older than the earliest time, putting buy offers and sell offers into a different list, and then
	 * returning the difference in value of the sell list and the buy list.
	 *
	 * @param earliestTime the earliest time the user wants trades to impact profit for this item for.
	 * @return profit
	 */
	public long currentProfit(Instant earliestTime)
	{
		List<OfferInfo> itemsInInterval = getIntervalsHistory(earliestTime);

		//return the value of the sell list - the value of the buy list. This is the profit.
		return getCashflow(itemsInInterval, false) - getCashflow(itemsInInterval, true);
	}

	/**
	 * This method finds the value of a list of offers. The boolean parameter determines if we calculate
	 * from buyList or sellList.
	 *
	 * @param offers     The list of standardized offers whose cashflow we want the value of.
	 * @param getExpense Options parameter that calculates, if true, the total expenses accrued
	 *                   and, if false, the total revenues accrued from the trades.
	 * @return Returns a long value based on the boolean parameter provided.
	 */
	public long getCashflow(List<OfferInfo> offers, boolean getExpense)
	{
		return getValueOfTrades(getSaleList(offers, getExpense), countItemsFlipped(offers));
	}

	/**
	 * Gets the quantity of flipped items that has been done in a list of offers.
	 * The quantity flipped is determined by the lowest of either number of items bought or sold.
	 *
	 * @param tradeList The list of items that the item count is based on
	 * @return An integer representing the total quantity of items flipped in the list of offers
	 */
	public int countItemsFlipped(List<OfferInfo> tradeList)
	{
		int numBoughtItems = 0;
		int numSoldItems = 0;

		for (OfferInfo standardizedOffer : tradeList)
		{
			if (standardizedOffer.isBuy())
			{
				numBoughtItems += standardizedOffer.getQuantity();
			}
			else
			{
				numSoldItems += standardizedOffer.getQuantity();
			}
		}

		return Math.min(numBoughtItems, numSoldItems);
	}

	/**
	 * Gets the list of trades of either buy or sell states from a list of trades.
	 *
	 * @param tradeList The list of trades that will be checked.
	 * @param buyState  true will return offers that have been bought and false will return offers that have been sold.
	 * @return A list of items either sold or bought over a period of time.
	 */
	private ArrayList<OfferInfo> getSaleList(List<OfferInfo> tradeList, boolean buyState)
	{
		ArrayList<OfferInfo> results = new ArrayList<>();

		for (OfferInfo standardizedOffer : tradeList)
		{
			if (standardizedOffer.isBuy() == buyState)
			{
				results.add(standardizedOffer);
			}
		}

		return results;
	}

	/**
	 * Calculates the amount of money spent on either a buy or sell list, up to the amount of items
	 * specified by the limit.
	 *
	 * @param tradeList a buy or a sell list
	 * @param itemLimit the amount of items to calculate the value up until. This is for the case
	 *                  when a user has an unequal amount of buys/sells in which case you want to return the
	 *                  profit the items only up until the buys and sells are equal.
	 * @return the amount of money spent on the offer list, up to the amount of items specified by the
	 * limit
	 */
	private long getValueOfTrades(List<OfferInfo> tradeList, int itemLimit)
	{
		int itemsSeen = 0;
		long moneySpent = 0;

		for (OfferInfo offer : tradeList)
		{
			if (itemsSeen + offer.getQuantity() >= itemLimit)
			{
				moneySpent += (itemLimit - itemsSeen) * offer.getPrice();
				break;
			}
			else
			{
				moneySpent += offer.getQuantity() * offer.getPrice();
				itemsSeen += offer.getQuantity();
			}
		}

		return moneySpent;
	}

	/**
	 * Returns the history of the item that were traded between earliestTime and now.
	 *
	 * @param earliestTime the earliest time that trades from the trade history are added to the resulting list.
	 * @return A list of offers that were within the interval of earliestTime and now.
	 */
	public ArrayList<OfferInfo> getIntervalsHistory(Instant earliestTime)
	{
		ArrayList<OfferInfo> result = new ArrayList<>();

		for (OfferInfo offer : standardizedOffers)
		{
			if (offer.getTime().isAfter(earliestTime))
			{
				result.add(offer);
			}
		}

		return result;
	}

	/**
	 * This is to prevent old values from remaining for items that a user has bought and whose
	 * refresh times have already passed. If the user buys the item again, the values will be up to date,
	 * so this method wouldn't be needed, but there is no guarantee the user buys the item again after the
	 * limit refreshes. This method should be called periodically to ensure no old values will remain.
	 */
	public void validateGeProperties()
	{

		if (nextGeLimitRefresh == null)
		{
			return;
		}

		if (Instant.now().compareTo(nextGeLimitRefresh) >= 0)

		{
			nextGeLimitRefresh = null;
			itemsBoughtThisLimitWindow = 0;
		}
	}

	/**
	 * This method serves as a way of segmenting a series of trades into separate flips.
	 * <p>
	 * Each buy trade is tallied up and price mapped with their quantities. Afterwards, we
	 * compartmentalize the quantities into respective completed sell offers. Completed sell offers
	 * act as a conclusion to a flip and thus each can only generate one flip. If we have a higher
	 * sell than bought quantity, we skim off the remaining sell offers as we are not able to
	 * determine where they originated and thus cannot accurately produce a flip.
	 *
	 * @param earliestTime The earliest time that new trades are treated as flips.
	 * @return A list of compartmentalized flips from the interval between now and the parameter.
	 */
	public ArrayList<Flip> getFlips(Instant earliestTime)
	{
		//The resulting flips
		ArrayList<Flip> flips = new ArrayList<>();

		//Fetch relevant history
		ArrayList<OfferInfo> intervalHistory = getIntervalsHistory(earliestTime);

		List<OfferInfo> buyMarginChecks = intervalHistory.stream().filter(offer -> offer.isBuy() && offer.isMarginCheck()).collect(Collectors.toList());
		List<OfferInfo> nonMarginCheckBuys = intervalHistory.stream().filter(offer -> offer.isBuy() && !offer.isMarginCheck()).collect(Collectors.toList());

		List<OfferInfo> sellMarginChecks = intervalHistory.stream().filter(offer -> !offer.isBuy() && offer.isMarginCheck()).collect(Collectors.toList());
		List<OfferInfo> nonMarginCheckSells = intervalHistory.stream().filter(offer -> !offer.isBuy() && !offer.isMarginCheck()).collect(Collectors.toList());

		flips.addAll(groupMarginChecks(buyMarginChecks, sellMarginChecks));

		List<OfferInfo> consolidatedBuys = consolidateList(nonMarginCheckBuys);
		List<OfferInfo> consolidatedSells = consolidateList(nonMarginCheckSells);

		int buyListPointer = 0;
		int sellListPointer = 0;
		while (buyListPointer < consolidatedBuys.size() && sellListPointer < consolidatedSells.size())
		{
			OfferInfo buy = consolidatedBuys.get(buyListPointer);
			OfferInfo sell = consolidatedSells.get(sellListPointer);

			if (sell.getQuantity() >= buy.getQuantity())
			{
				sell.setQuantity(sell.getQuantity() - buy.getQuantity());
				buyListPointer++;
				flips.add(new Flip(buy.getPrice(), sell.getPrice(), buy.getQuantity(), sell.getTime()));
			}

			else
			{
				buy.setQuantity(buy.getQuantity() - sell.getQuantity());
				sellListPointer++;
				flips.add(new Flip(buy.getPrice(), sell.getPrice(), sell.getQuantity(), sell.getTime()));
			}
		}

		flips.sort(Comparator.comparing(flip -> flip.getTime()));
		Collections.reverse(flips);
		return flips;
	}

	//hashmap based iterative version, i think this is easier to understand
	private ArrayList<OfferInfo> consolidateList(List<OfferInfo> offers)
	{
		LinkedHashMap<List<Integer>, OfferInfo> slotAndPriceToOffer = new LinkedHashMap();
		ArrayList<OfferInfo> consolidatedOffers = new ArrayList<>();

		for (OfferInfo offer : offers)
		{
			int slot = offer.getSlot();
			int price = offer.getPrice();
			List<Integer> slotAndPrice = new ArrayList<>(Arrays.asList(slot, price));

			if (slotAndPriceToOffer.containsKey(slotAndPrice))
			{
				OfferInfo consolidatedOffer = slotAndPriceToOffer.get(slotAndPrice);
				consolidatedOffer.setQuantity(consolidatedOffer.getQuantity() + offer.getQuantity());
				consolidatedOffer.setTime(offer.getTime());
				if (offer.isComplete())
				{
					consolidatedOffers.add(consolidatedOffer);
					slotAndPriceToOffer.remove(slotAndPrice);
				}

			}
			else
			{
				if (offer.isComplete())
				{
					consolidatedOffers.add(offer.clone());
				}
				else
				{
					slotAndPriceToOffer.put(slotAndPrice, offer.clone());
				}

			}
		}

		return consolidatedOffers;
	}


	private ArrayList<Flip> groupMarginChecks(List<OfferInfo> buyMarginChecks, List<OfferInfo> sellMarginChecks)
	{
		ArrayList<Flip> marginCheckFlips = new ArrayList<>();
		int minSize = Math.min(buyMarginChecks.size(), sellMarginChecks.size());
		for (int i = 0; i < minSize; i++)
		{
			OfferInfo buyMarginCheck = buyMarginChecks.get(i);
			OfferInfo sellMarginCheck = sellMarginChecks.get(i);
			Flip flip = new Flip(buyMarginCheck.getPrice(), sellMarginCheck.getPrice(), 1, sellMarginCheck.getTime());
			marginCheckFlips.add(flip);
		}
		return marginCheckFlips;
	}

}
