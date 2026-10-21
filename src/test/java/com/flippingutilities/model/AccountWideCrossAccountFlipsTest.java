package com.flippingutilities.model;

import com.flippingutilities.utilities.MathUtils;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

/**
 * Reproduces a reported issue with the account-wide stats of a player who funnels items
 * across their own accounts: several alts buy earth runes at 4 gp and a main account sells
 * them at 5 gp.
 * <p>
 * The reported totals were 1,907,078,609 bought and 1,283,457,544 sold across accounts, and
 * the account-wide item panel showed:
 * <ul>
 *     <li>Total Profit: 126.0M gp (should be ~1.28b gp: 1,283,457,544 sold x 1 gp profit ea)</li>
 *     <li>Avg. Profit ea.: 1 gp (correct)</li>
 *     <li>Avg. ROI: ~25% (correct)</li>
 *     <li>Quantity Flipped: 125,999,310 (should be 1,283,457,544)</li>
 *     <li>Quantity Bought: 1,907,078,609 / Quantity Sold: 1,283,457,544 (correct)</li>
 * </ul>
 * <p>
 * The scenario is assembled through the real account-wide pipeline: each account owns a
 * hydrated {@link FlippingItem}, and the items are merged with {@link FlippingItem#merge}
 * the same way {@code FlippingItemHandler.createAccountWideFlippingItemList} does it. The
 * stats are then computed the same way {@code FlippingItemPanel.updateLabels} does.
 * <p>
 * The main account bought 125,999,310 runes itself, which is why the reported "Quantity
 * Flipped" equals exactly its within-account min(bought, sold); every rune bought on an alt
 * and sold on the main is invisible to the current per-account flip counting.
 */
public class AccountWideCrossAccountFlipsTest
{
	private static final int EARTH_RUNE_ID = 1442;
	private static final String MAIN = "main";
	private static final String ALT_1 = "alt1";
	private static final String ALT_2 = "alt2";
	private static final String ALT_3 = "alt3";
	private static final long TOTAL_BOUGHT = 1_907_078_609L;
	private static final long TOTAL_SOLD = 1_283_457_544L;
	private static final long MAIN_BOUGHT = 125_999_310L;

	private static final Instant BASE_TIME = Instant.parse("2020-06-01T00:00:00Z"); // before GE tax

	private long totalItemsBought;
	private long totalItemsSold;
	private long itemCountFlipped;
	private long revenueFromFlippedItems;
	private long expenseFromFlippedItems;
	private long totalRevenue;
	private long totalExpense;
	private long profit;

	@Before
	public void setUp()
	{
		FlippingItem main = accountItem(MAIN, Arrays.asList(
			earthRuneOffer(true, 62_999_310, 4, 0),
			earthRuneOffer(true, 63_000_000, 4, 1),
			earthRuneOffer(false, 320_864_386, 5, 2),
			earthRuneOffer(false, 320_864_386, 5, 3),
			earthRuneOffer(false, 320_864_386, 5, 4),
			earthRuneOffer(false, 320_864_386, 5, 5)));

		// 700,000,000 + 700,000,000 + 381,079,299 = 1,781,079,299 runes bought on alts, never sold there.
		FlippingItem alt1 = accountItem(ALT_1, Arrays.asList(
			earthRuneOffer(true, 350_000_000, 4, 0),
			earthRuneOffer(true, 350_000_000, 4, 1)));
		FlippingItem alt2 = accountItem(ALT_2, Arrays.asList(
			earthRuneOffer(true, 350_000_000, 4, 0),
			earthRuneOffer(true, 350_000_000, 4, 1)));
		FlippingItem alt3 = accountItem(ALT_3, Arrays.asList(
			earthRuneOffer(true, 381_079_299, 4, 0)));

		FlippingItem accountWideItem = Arrays.asList(main, alt1, alt2, alt3)
			.stream()
			.map(FlippingItem::clone)
			.reduce(FlippingItem::merge)
			.get();

		//the merged history the stats panel sees for the account-wide view
		List<OfferEvent> offers = accountWideItem.getIntervalHistory(Instant.EPOCH);

		//the exact computations FlippingItemPanel.updateLabels performs
		totalItemsBought = offers.stream().filter(OfferEvent::isBuy).mapToLong(OfferEvent::getCurrentQuantityInTrade).sum();
		totalItemsSold = offers.stream().filter(o -> !o.isBuy()).mapToLong(OfferEvent::getCurrentQuantityInTrade).sum();
		itemCountFlipped = FlippingItem.countFlipQuantity(offers);
		revenueFromFlippedItems = FlippingItem.getValueOfMatchedOffers(offers, false);
		expenseFromFlippedItems = FlippingItem.getValueOfMatchedOffers(offers, true);
		totalRevenue = FlippingItem.getTotalRevenueOrExpense(offers, false);
		totalExpense = FlippingItem.getTotalRevenueOrExpense(offers, true);
		profit = revenueFromFlippedItems - expenseFromFlippedItems;
	}

	@Test
	public void quantityFlippedCountsItemsFunnelledAcrossAccounts()
	{
		assertEquals("Quantity Flipped should reflect that every sold rune was bought on one of the accounts",
			TOTAL_SOLD, itemCountFlipped);
	}

	@Test
	public void totalProfitCountsItemsFunnelledAcrossAccounts()
	{
		assertEquals("Total Profit should be 1 gp x every rune sold at 5 gp after being bought at 4 gp",
			TOTAL_SOLD * 1, profit);
	}

	@Test
	public void quantityAndPerItemLabelsLookCorrect()
	{
		//these labels were correct in the report; they pin down that only the totals are off
		assertEquals(TOTAL_BOUGHT, totalItemsBought);
		assertEquals(TOTAL_SOLD, totalItemsSold);
		assertEquals(4, totalExpense / totalItemsBought);
		assertEquals(5, totalRevenue / totalItemsSold);

		long profitEach = itemCountFlipped > 0 ? MathUtils.roundedQuotient(profit, itemCountFlipped) : 0;
		assertEquals(1, profitEach);

		double roi = expenseFromFlippedItems > 0 ? (double) profit / expenseFromFlippedItems * 100 : 0;
		assertEquals(25.0, roi, 0.01);
	}

	/**
	 * Builds a flipping item the way an account's trades are set up at startup: a history
	 * of complete offers, hydrated so every offer is marked as made by this account.
	 */
	private FlippingItem accountItem(String account, List<OfferEvent> offers)
	{
		FlippingItem item = new FlippingItem(EARTH_RUNE_ID, "Earth rune", 20_000, account);
		item.setHistory(new HistoryManager(offers, null, 0, 0));
		item.hydrate(20_000);
		return item;
	}

	/**
	 * A complete earth rune offer. ticksSinceFirstOffer is high so that no offer is ever
	 * mistaken for a margin check.
	 */
	private static OfferEvent earthRuneOffer(boolean buy, int quantity, long price, int minutesAfterBase)
	{
		GrandExchangeOfferState state = buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD;
		return new OfferEvent(
			UUID.randomUUID().toString(),
			buy,
			EARTH_RUNE_ID,
			quantity,
			price,
			BASE_TIME.plus(minutesAfterBase, ChronoUnit.MINUTES),
			0, //slot is irrelevant once the offer is complete
			state,
			0,
			100,
			quantity,
			null,
			false,
			null,
			null,
			0,
			0);
	}
}
