package com.flippingutilities.model;

import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.flippingutilities.utilities.TimeRange;
import org.junit.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class RecipeFlipGroupRangeTest
{
	private final Instant now = Instant.now();

	@Test
	public void resettingARangeKeepsFlipsCreatedOutsideIt()
	{
		TimeRange range = TimeRange.between(now.minus(10, ChronoUnit.DAYS), now);
		OfferEvent offer = offer("offer-1", now.minus(5, ChronoUnit.DAYS));

		RecipeFlip insideRange = flip(now.minus(1, ChronoUnit.DAYS), offer.getUuid());
		RecipeFlip outsideRange = flip(now.plus(1, ChronoUnit.DAYS), offer.getUuid());
		RecipeFlipGroup group = group(insideRange, outsideRange);

		group.deleteFlipsWithDeletedOffers(Collections.singletonList(offer), range);

		List<RecipeFlip> remaining = group.getFlipsInInterval(TimeRange.ALL);
		assertEquals(1, remaining.size());
		assertSame(outsideRange, remaining.get(0));
	}

	@Test
	public void deletingASingleOfferStillRemovesEveryFlipThatUsesIt()
	{
		OfferEvent offer = offer("offer-1", now.minus(5, ChronoUnit.DAYS));

		RecipeFlip insideRange = flip(now.minus(1, ChronoUnit.DAYS), offer.getUuid());
		RecipeFlip outsideRange = flip(now.plus(1, ChronoUnit.DAYS), offer.getUuid());
		RecipeFlipGroup group = group(insideRange, outsideRange);

		group.deleteFlipsWithDeletedOffers(Collections.singletonList(offer));

		assertEquals(0, group.getFlipsInInterval(TimeRange.ALL).size());
	}

	private static OfferEvent offer(String uuid, Instant time)
	{
		OfferEvent offer = new OfferEvent();
		offer.setUuid(uuid);
		offer.setTime(time);
		return offer;
	}

	private static RecipeFlip flip(Instant creationTime, String offerUuid)
	{
		Map<String, PartialOffer> byOfferId = new HashMap<>();
		byOfferId.put(offerUuid, new PartialOffer(offerUuid, 1));
		Map<Integer, Map<String, PartialOffer>> inputs = Collections.singletonMap(1, byOfferId);
		return new RecipeFlip(creationTime, Collections.emptyMap(), inputs, 0);
	}

	private static RecipeFlipGroup group(RecipeFlip... flips)
	{
		Recipe recipe = new Recipe(Collections.singletonList(new RecipeItem(1, 1)),
			Collections.singletonList(new RecipeItem(2, 1)), "Test recipe");
		return new RecipeFlipGroup(recipe, new ArrayList<>(Arrays.asList(flips)));
	}
}
