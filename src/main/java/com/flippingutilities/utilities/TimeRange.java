package com.flippingutilities.utilities;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * An inclusive interval of time, {@code [from, to]}. Ranges that only have a lower bound use
 * {@link Instant#MAX} as their end, so existing "since X" intervals keep working unchanged.
 */
public final class TimeRange
{
	public static final TimeRange ALL = new TimeRange(Instant.EPOCH, Instant.MAX);

	private final Instant from;
	private final Instant to;

	private TimeRange(Instant from, Instant to)
	{
		this.from = from;
		this.to = to;
	}

	/**
	 * A range that starts at the given instant and has no upper bound.
	 */
	public static TimeRange from(Instant from)
	{
		return new TimeRange(from, Instant.MAX);
	}

	public static TimeRange between(Instant from, Instant to)
	{
		if (from.isAfter(to))
		{
			throw new IllegalArgumentException("range start must not be after range end");
		}
		return new TimeRange(from, to);
	}

	/**
	 * A range covering every moment of the given local dates, both days included.
	 */
	public static TimeRange ofLocalDates(LocalDate from, LocalDate to, ZoneId zone)
	{
		return between(
			from.atStartOfDay(zone).toInstant(),
			to.atTime(LocalTime.MAX).atZone(zone).toInstant());
	}

	public boolean contains(Instant time)
	{
		return !time.isBefore(from) && !time.isAfter(to);
	}

	public Instant getFrom()
	{
		return from;
	}

	public Instant getTo()
	{
		return to;
	}

	/**
	 * Whether this range has no upper bound, i.e. it covers everything up to now.
	 */
	public boolean isOpenEnded()
	{
		return to.equals(Instant.MAX);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof TimeRange))
		{
			return false;
		}
		TimeRange other = (TimeRange) o;
		return from.equals(other.from) && to.equals(other.to);
	}

	@Override
	public int hashCode()
	{
		return 31 * from.hashCode() + to.hashCode();
	}

	@Override
	public String toString()
	{
		return isOpenEnded() ? "[" + from + ", now]" : "[" + from + ", " + to + "]";
	}
}
