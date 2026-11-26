package com.flippingutilities.utilities;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimeRangeTest
{
	private static final ZoneId ZONE = ZoneId.of("Europe/Brussels");

	@Test
	public void containsIncludesBothBounds()
	{
		Instant from = Instant.parse("2026-11-01T00:00:00Z");
		Instant to = Instant.parse("2026-11-20T00:00:00Z");
		TimeRange range = TimeRange.between(from, to);

		assertTrue(range.contains(from));
		assertTrue(range.contains(to));
		assertTrue(range.contains(from.plusSeconds(60)));
		assertFalse(range.contains(from.minusSeconds(1)));
		assertFalse(range.contains(to.plusSeconds(1)));
	}

	@Test
	public void openEndedRangeCoversEverythingFromItsStart()
	{
		Instant from = Instant.parse("2026-11-01T00:00:00Z");
		TimeRange range = TimeRange.from(from);

		assertTrue(range.isOpenEnded());
		assertFalse(range.contains(from.minusSeconds(1)));
		assertTrue(range.contains(from));
		assertTrue(range.contains(Instant.now()));
	}

	@Test
	public void allStartsAtTheEpochAndHasNoEnd()
	{
		assertTrue(TimeRange.ALL.isOpenEnded());
		assertEquals(Instant.EPOCH, TimeRange.ALL.getFrom());
		assertTrue(TimeRange.ALL.contains(Instant.EPOCH));
		assertTrue(TimeRange.ALL.contains(Instant.now()));
	}

	@Test
	public void localDateRangeIncludesTheWholeEndDay()
	{
		LocalDate firstDay = LocalDate.of(2026, 11, 1);
		LocalDate lastDay = LocalDate.of(2026, 11, 20);
		TimeRange range = TimeRange.ofLocalDates(firstDay, lastDay, ZONE);

		Instant startOfFirstDay = firstDay.atStartOfDay(ZONE).toInstant();
		Instant lastMomentOfLastDay = lastDay.atTime(LocalTime.of(23, 59, 59)).atZone(ZONE).toInstant();
		Instant startOfDayAfterLast = lastDay.plusDays(1).atStartOfDay(ZONE).toInstant();

		assertTrue(range.contains(startOfFirstDay));
		assertTrue(range.contains(lastMomentOfLastDay));
		assertFalse(range.contains(startOfDayAfterLast));
		assertFalse(range.contains(startOfFirstDay.minusMillis(1)));
		assertFalse(range.isOpenEnded());
	}

	@Test
	public void localDateRangeKeepsTheRepeatedFinalHourOfTheEndDay()
	{
		//Santiago ends DST at midnight, so the last hour of 4 April 2026 happens twice
		ZoneId santiago = ZoneId.of("America/Santiago");
		LocalDate endDay = LocalDate.of(2026, 4, 4);
		TimeRange range = TimeRange.ofLocalDates(endDay, endDay, santiago);

		assertTrue(range.contains(Instant.parse("2026-04-05T02:30:00Z")));
		assertTrue(range.contains(Instant.parse("2026-04-05T03:30:00Z")));
		assertFalse(range.contains(Instant.parse("2026-04-05T04:00:00Z")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void invertedRangeIsRejected()
	{
		TimeRange.between(Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));
	}
}
