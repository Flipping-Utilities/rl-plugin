package com.flippingutilities.model;

import com.flippingutilities.utilities.TimeRange;
import org.junit.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FlippingItemRangeTest
{
	private final Instant now = Instant.now();

	@Test
	public void intervalHistoryKeepsOnlyOffersInsideBothBounds()
	{
		FlippingItem item = itemWithOffersAt(
			now.minus(3, ChronoUnit.HOURS),
			now.minus(2, ChronoUnit.HOURS),
			now.minus(1, ChronoUnit.HOURS));

		TimeRange range = TimeRange.between(now.minus(150, ChronoUnit.MINUTES), now.minus(90, ChronoUnit.MINUTES));

		assertEquals(1, item.getIntervalHistory(range).size());
		assertTrue(item.isInInterval(range));
	}

	@Test
	public void offersOutsideTheRangeAreExcluded()
	{
		FlippingItem item = itemWithOffersAt(
			now.minus(3, ChronoUnit.HOURS),
			now.minus(1, ChronoUnit.HOURS));

		TimeRange range = TimeRange.between(now.minus(150, ChronoUnit.MINUTES), now.minus(90, ChronoUnit.MINUTES));

		assertTrue(item.getIntervalHistory(range).isEmpty());
		assertFalse(item.isInInterval(range));
	}

	@Test
	public void offerExactlyAtTheRangeEndIsIncluded()
	{
		Instant offerTime = now.minus(1, ChronoUnit.HOURS);
		FlippingItem item = itemWithOffersAt(offerTime);

		TimeRange range = TimeRange.between(now.minus(2, ChronoUnit.HOURS), offerTime);

		assertEquals(1, item.getIntervalHistory(range).size());
	}

	@Test
	public void offerExactlyAtTheRangeStartIsIncluded()
	{
		Instant offerTime = now.minus(2, ChronoUnit.HOURS);
		FlippingItem item = itemWithOffersAt(offerTime);

		TimeRange range = TimeRange.between(offerTime, now.minus(1, ChronoUnit.HOURS));

		assertEquals(1, item.getIntervalHistory(range).size());
	}

	@Test
	public void openEndedRangeStillIgnoresOffersBeforeItsStart()
	{
		FlippingItem item = itemWithOffersAt(
			now.minus(3, ChronoUnit.HOURS),
			now.minus(1, ChronoUnit.HOURS));

		assertEquals(1, item.getIntervalHistory(TimeRange.from(now.minus(2, ChronoUnit.HOURS))).size());
	}

	private static FlippingItem itemWithOffersAt(Instant... offerTimes)
	{
		FlippingItem item = new FlippingItem(1, "Item", 100, "account");
		for (Instant offerTime : offerTimes)
		{
			OfferEvent offer = new OfferEvent();
			offer.setTime(offerTime);
			item.getHistory().getCompressedOfferEvents().add(offer);
		}
		return item;
	}
}
