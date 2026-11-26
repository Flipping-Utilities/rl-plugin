package com.flippingutilities.controller;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.model.RecipeFlip;
import com.flippingutilities.model.RecipeFlipGroup;
import com.flippingutilities.utilities.Recipe;
import com.flippingutilities.utilities.RecipeItem;
import com.flippingutilities.utilities.SORT;
import com.flippingutilities.utilities.TimeRange;
import com.google.gson.Gson;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class SortByRangeTest
{
	private final Instant now = Instant.now();

	@Test
	public void itemTimeSortUsesActivityInsideTheRange()
	{
		TimeRange range = TimeRange.between(now.minus(30, ChronoUnit.DAYS), now);

		//B's in-range offer is the newest activity that is actually shown for this range
		FlippingItem hidesNewerActivity = item("A", now.minus(20, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS));
		FlippingItem visibleNewestActivity = item("B", now.minus(10, ChronoUnit.DAYS));

		List<FlippingItem> sorted = new FlippingItemHandler(null)
			.sortItems(Arrays.asList(hidesNewerActivity, visibleNewestActivity), SORT.TIME, range);

		assertEquals("B", sorted.get(0).getItemName());
		assertEquals("A", sorted.get(1).getItemName());
	}

	@Test
	public void recipeTimeSortUsesFlipsInsideTheRange()
	{
		TimeRange range = TimeRange.between(now.minus(30, ChronoUnit.DAYS), now);

		RecipeFlipGroup hidesNewerFlip = group("A", now.minus(20, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS));
		RecipeFlipGroup visibleNewestFlip = group("B", now.minus(10, ChronoUnit.DAYS));

		List<RecipeFlipGroup> sorted = new RecipeHandler(new Gson(), offlineClient(), Collections.emptyList())
			.sortRecipeFlipGroups(Arrays.asList(hidesNewerFlip, visibleNewestFlip), SORT.TIME, range);

		assertSame(visibleNewestFlip, sorted.get(0));
		assertSame(hidesNewerFlip, sorted.get(1));
	}

	private static FlippingItem item(String name, Instant... offerTimes)
	{
		FlippingItem item = new FlippingItem(1, name, 100, "account");
		for (Instant offerTime : offerTimes)
		{
			OfferEvent offer = new OfferEvent();
			offer.setTime(offerTime);
			item.getHistory().getCompressedOfferEvents().add(offer);
		}
		item.hydrate(100);
		return item;
	}

	private static RecipeFlipGroup group(String name, Instant... flipTimes)
	{
		Recipe recipe = new Recipe(Collections.singletonList(new RecipeItem(1, 1)),
			Collections.singletonList(new RecipeItem(2, 1)), name);
		List<RecipeFlip> flips = new ArrayList<>();
		for (Instant flipTime : flipTimes)
		{
			flips.add(new RecipeFlip(flipTime, Collections.emptyMap(), Collections.emptyMap(), 0));
		}
		return new RecipeFlipGroup(recipe, flips);
	}

	/**
	 * The recipe handler loads its recipe list in the constructor, so answer every request locally
	 * instead of reaching the network.
	 */
	private static OkHttpClient offlineClient()
	{
		return new OkHttpClient.Builder()
			.addInterceptor(chain -> new Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), "[]"))
				.build())
			.build();
	}
}
