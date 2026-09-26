package com.flippingutilities.controller;

import com.flippingutilities.model.*;
import com.flippingutilities.utilities.*;
import com.google.gson.Gson;
import okhttp3.*;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

public class RecipeFinancialSortTest {
    @Test public void unknownRatiosSortLastOnlyWhenTheSortRequiresExecutionCounts() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create(MediaType.get("application/json"), "[]")).build()).build();
        try {
            RecipeHandler handler = new RecipeHandler(new Gson(), client, Collections.emptyList());
            OfferEvent sale = new OfferEvent();
            sale.setTime(Instant.EPOCH);
            sale.setPrice(100);
            RecipeFlip profitable = new RecipeFlip(Instant.now(), Collections.singletonMap(1,
                Collections.singletonMap("sale", new PartialOffer(sale, 6))), Collections.emptyMap(), 100);
            RecipeFlipGroup unknown = new RecipeFlipGroup((String) null);
            unknown.addRecipeFlip(profitable);
            unknown.synthesizeRecipe(null);
            Recipe recipe = new Recipe(Collections.emptyList(), Collections.singletonList(new RecipeItem(1, 2)), "Known");
            RecipeFlipGroup known = new RecipeFlipGroup(recipe, Collections.singletonList(
                new RecipeFlip(Instant.now(), profitable.getOutputs(), Collections.emptyMap(), 1000)));

            for (SORT sort : new SORT[]{SORT.FLIP_COUNT, SORT.PROFIT_EACH}) {
                assertEquals(Arrays.asList(known, unknown), handler.sortRecipeFlipGroups(
                    Arrays.asList(unknown, known), sort, Instant.EPOCH));
            }
            for (SORT sort : new SORT[]{SORT.TOTAL_PROFIT, SORT.ROI}) {
                assertEquals(Arrays.asList(unknown, known), handler.sortRecipeFlipGroups(
                    Arrays.asList(known, unknown), sort, Instant.EPOCH));
            }
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    @Test public void financialSortsPutMissingOffersAfterKnownLossesAndHandleEmptyIntervals() {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
            .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(ResponseBody.create(MediaType.get("application/json"), "[]")).build()).build();
        try {
            RecipeHandler handler = new RecipeHandler(new Gson(), client, Collections.emptyList());
            Recipe recipe = new Recipe(Collections.emptyList(), Collections.singletonList(new RecipeItem(1, 1)), "Test");
            RecipeFlip unknown = new RecipeFlip(Instant.now(),
                Collections.singletonMap(1, Collections.singletonMap("missing", new PartialOffer("missing", 1))),
                Collections.emptyMap(), 0);
            OfferEvent zero = new OfferEvent();
            zero.setTime(Instant.now());
            RecipeFlip loss = new RecipeFlip(Instant.now(),
                Collections.singletonMap(1, Collections.singletonMap("zero", new PartialOffer(zero, 1))),
                Collections.emptyMap(), 100);
            RecipeFlipGroup missing = new RecipeFlipGroup(recipe, Collections.singletonList(unknown));
            RecipeFlipGroup known = new RecipeFlipGroup(recipe, Collections.singletonList(loss));
            for (SORT sort : new SORT[]{SORT.TOTAL_PROFIT, SORT.PROFIT_EACH, SORT.ROI}) {
                assertEquals(Arrays.asList(known, missing), handler.sortRecipeFlipGroups(
                    Arrays.asList(missing, known), sort, Instant.EPOCH));
                assertEquals(2, handler.sortRecipeFlipGroups(Arrays.asList(missing, known), sort,
                    Instant.now().plusSeconds(60)).size());
            }
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
