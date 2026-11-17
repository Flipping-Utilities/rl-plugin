package com.flippingutilities.model;

import com.google.gson.Gson;
import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OfferExactPriceTest
{
	//before the GE tax existed, so post-tax == pre-tax
	private static final Instant UNTAXED = Instant.parse("2020-01-01T00:00:00Z");

	@Test
	public void exactAverageIsOnlyKeptWhenItHasAFraction()
	{
		assertEquals(4.4, OfferEvent.exactAveragePrice(22, 5), 1e-9);
		assertNull(OfferEvent.exactAveragePrice(20, 5));
		assertNull(OfferEvent.exactAveragePrice(0, 0));
	}

	@Test
	public void valuesUseTheExactAverage()
	{
		//3 @ 4gp + 2 @ 5gp: the GE reports 22gp spent for 5 items
		OfferEvent buy = offer(true, 5, 22);

		assertEquals(4L, buy.getPrice());
		assertEquals(4.4, buy.getExactPrice(), 1e-9);
		assertEquals(22L, buy.getTotalValue());
		assertEquals(9L, buy.getValueOf(2));
	}

	@Test
	public void offersWithoutAnExactPriceFallBackToTheWholeGpPrice()
	{
		OfferEvent legacy = offer(true, 5, 22);
		legacy.setExactPreTaxPrice(null);

		assertEquals(4.0, legacy.getExactPrice(), 0);
		assertEquals(20L, legacy.getTotalValue());
	}

	@Test
	public void profitAndFlipsUseTheExactSpend()
	{
		List<OfferEvent> offers = Arrays.asList(
			offer(true, 5, 22),
			offer(false, 5, 33));

		//33gp received - 22gp spent; the truncated prices (6 and 4) would give 10gp
		assertEquals(11L, FlippingItem.getProfit(offers));
		assertEquals(22L, FlippingItem.getValueOfMatchedOffers(offers, true));

		Flip flip = FlippingItem.getFlips(offers).get(0);
		assertEquals(4.4, flip.getBuyPrice(), 1e-9);
		assertEquals(6.6, flip.getSellPrice(), 1e-9);
	}

	@Test
	public void partiallyConsumedOffersKeepTheExactAveragePerItem()
	{
		OfferEvent buy = offer(true, 5, 22);
		OfferEvent remaining = new PartialOffer(buy, 2).toRemainingOfferEvent();

		assertEquals(3, remaining.getCurrentQuantityInTrade());
		assertEquals(13L, remaining.getTotalValue());
	}

	@Test
	public void taxIsTakenOffTheExactAverage()
	{
		//2 items sold for 201gp total; the 2% tax on the 100gp whole price is 2gp each
		OfferEvent sell = offer(false, 2, 201);
		sell.setTime(Instant.now());
		sell.setItemId(4151);

		assertEquals(98.5, sell.getExactPrice(), 1e-9);
		assertEquals(197L, sell.getTotalValue());
	}

	@Test
	public void exactPriceIsSavedOnlyWhenPresent()
	{
		Gson gson = new Gson();
		OfferEvent exact = new OfferEvent();
		exact.setPrice(4);
		exact.setExactPreTaxPrice(4.4);
		OfferEvent whole = new OfferEvent();
		whole.setPrice(4);

		String exactJson = gson.toJson(exact);
		assertTrue(exactJson.contains("\"pE\":4.4"));
		assertFalse(gson.toJson(whole).contains("pE"));
		assertEquals(4.4, gson.fromJson(exactJson, OfferEvent.class).getExactPreTaxPrice(), 1e-9);
		assertEquals(4.0, gson.fromJson("{\"p\":4}", OfferEvent.class).getExactPreTaxPrice(), 0);
	}

	@Test
	public void cloneKeepsTheExactPrice()
	{
		assertEquals(4.4, offer(true, 5, 22).clone().getExactPreTaxPrice(), 1e-9);
	}

	private OfferEvent offer(boolean buy, int quantity, long spent)
	{
		OfferEvent offer = new OfferEvent();
		offer.setTime(UNTAXED);
		offer.setMadeBy("a");
		offer.setBuy(buy);
		offer.setCurrentQuantityInTrade(quantity);
		offer.setPrice(spent / quantity);
		offer.setExactPreTaxPrice(OfferEvent.exactAveragePrice(spent, quantity));
		return offer;
	}
}
