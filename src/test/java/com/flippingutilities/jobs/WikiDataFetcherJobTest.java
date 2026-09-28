package com.flippingutilities.jobs;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.utilities.WikiDataSource;
import com.flippingutilities.utilities.WikiRequestWrapper;
import com.google.gson.Gson;
import net.runelite.api.WorldType;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.*;

public class WikiDataFetcherJobTest {
    @Test
    public void oldWorldResponseCannotOverwriteNewWorldPrices() throws Exception {
        ManualClient client = new ManualClient();
        WikiDataFetcherJob job = job(client);
        try {
            List<WikiRequestWrapper> received = new ArrayList<>();
            job.subscribe((prices, time) -> received.add(prices));
            job.attemptToFetchWikiData(true);
            job.onWorldSwitch(EnumSet.of(WorldType.DEADMAN));
            assertTrue(client.calls.get(1).respond(200, prices(900)).closed);
            Instant completion = job.timeOfLastRequestCompletion;
            assertTrue(client.calls.get(0).respond(200, prices(100)).closed);

            assertEquals(1, received.size());
            assertEquals(WikiDataSource.DMM, received.get(0).getWikiDataSource());
            assertEquals(900L, received.get(0).getWikiRequest().getData().get(4151).getHigh());
            assertEquals(completion, job.timeOfLastRequestCompletion);
            assertFalse(job.inFlightRequest);
        } finally {
            job.executor.shutdownNow();
        }
    }

    @Test
    public void obsoleteFailureCannotClearCurrentRequestState() throws Exception {
        ManualClient client = new ManualClient();
        WikiDataFetcherJob job = job(client);
        try {
            job.attemptToFetchWikiData(true);
            job.onWorldSwitch(EnumSet.of(WorldType.DEADMAN));
            client.calls.get(0).fail();
            assertTrue(job.inFlightRequest);
            assertNull(job.timeOfLastRequestCompletion);
            client.calls.get(1).respond(200, "{ malformed");
            assertFalse(job.inFlightRequest);
            assertNotNull(job.timeOfLastRequestCompletion);
        } finally {
            job.executor.shutdownNow();
        }
    }

    @Test
    public void deadmanV2CanRecoverAfterAnInactiveEventResponse() throws Exception {
        ManualClient client = new ManualClient();
        WikiDataFetcherJob job = job(client);
        try {
            List<WikiRequestWrapper> received = new ArrayList<>();
            job.subscribe((prices, time) -> received.add(prices));
            job.onWorldSwitch(EnumSet.of(WorldType.DEADMAN));
            assertEquals("/api/v2/dmm/latest", client.calls.get(0).request().url().encodedPath());
            assertTrue(client.calls.get(0).respond(404, "Not found").closed);
            assertFalse(job.inFlightRequest);
            assertNotNull(job.timeOfLastRequestCompletion);
            assertTrue(received.isEmpty());

            job.attemptToFetchWikiData(true);
            assertEquals("/api/v2/dmm/latest", client.calls.get(1).request().url().encodedPath());
            assertTrue(client.calls.get(1).respond(200, prices(5_000_000_001L)).closed);
            assertEquals(1, received.size());
            assertEquals(WikiDataSource.DMM, received.get(0).getWikiDataSource());
            assertEquals(5_000_000_001L, received.get(0).getWikiRequest().getData().get(4151).getHigh());

            job.onWorldSwitch(EnumSet.noneOf(WorldType.class));
            assertEquals("/api/v2/osrs/latest", client.calls.get(2).request().url().encodedPath());
            assertTrue(client.calls.get(2).respond(200, prices(100)).closed);
            assertEquals(WikiDataSource.REGULAR, received.get(1).getWikiDataSource());
        } finally {
            job.executor.shutdownNow();
        }
    }

    private WikiDataFetcherJob job(ManualClient client) {
        FlippingPlugin plugin = new FlippingPlugin();
        plugin.gson = new Gson();
        return new WikiDataFetcherJob(plugin, client);
    }

    private String prices(long high) {
        return "{\"data\":{\"4151\":{\"high\":" + high + "}}}";
    }
}
