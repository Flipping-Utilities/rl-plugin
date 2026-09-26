package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.controller.ApiRequestHandlerTest.ManualClient;
import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import com.google.gson.Gson;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.Assert.*;

public class GraphDataLoaderTest {
    @Test
    public void changingItemIgnoresEarlierResponseEvenWhenItArrivesLast() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add);
        fixture.loader.load(2, Timestep.ONE_HOUR, fixture.displayed::add);
        fixture.client.calls.get(1).respond(200, history(200));
        fixture.thread.drain();
        fixture.client.calls.get(0).respond(200, history(100));
        fixture.thread.drain();
        assertEquals(1, fixture.displayed.size());
        assertEquals(Integer.valueOf(200), fixture.displayed.get(0).getData().get(0).getAvgHighPrice());
    }

    @Test
    public void changingDurationIgnoresEarlierResponse() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add);
        fixture.loader.load(4151, Timestep.FIVE_MINUTES, fixture.displayed::add);
        fixture.client.calls.get(0).respond(200, history(100));
        fixture.thread.drain();
        assertTrue(fixture.displayed.isEmpty());
        fixture.client.calls.get(1).respond(200, history(200));
        fixture.thread.drain();
        assertEquals(1, fixture.displayed.size());
        assertEquals(Integer.valueOf(200), fixture.displayed.get(0).getData().get(0).getAvgHighPrice());
    }

    @Test
    public void cachedAndNetworkResponsesBothWaitForClientThread() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add);
        fixture.client.calls.get(0).respond(200, history(100));
        assertTrue(fixture.displayed.isEmpty());
        fixture.thread.drain();
        assertEquals(1, fixture.displayed.size());
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add);
        assertEquals(1, fixture.displayed.size());
        assertEquals(1, fixture.client.calls.size());
        fixture.thread.drain();
        assertEquals(2, fixture.displayed.size());
    }

    @Test
    public void hidingDiscardsResponsesAlreadyQueuedForTheClientThread() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add);
        fixture.client.calls.get(0).respond(200, history(100));
        fixture.loader.clear();
        fixture.thread.drain();
        assertTrue(fixture.displayed.isEmpty());
    }

    @Test
    public void returningToSameItemKeepsOnlyLatestViewAndOtherViewsRemainIndependent() throws Exception {
        Fixture fixture = new Fixture();
        GraphDataLoader otherView = new GraphDataLoader(fixture.fetcher, fixture.thread);
        List<TimeseriesResponse> otherDisplayed = new ArrayList<>();
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add);
        otherView.load(4151, Timestep.ONE_HOUR, otherDisplayed::add);
        fixture.loader.clear();
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add);
        assertEquals(1, fixture.client.calls.size());
        fixture.client.calls.get(0).respond(200, history(100));
        fixture.thread.drain();
        assertEquals(1, fixture.displayed.size());
        assertEquals(1, otherDisplayed.size());
    }

    @Test
    public void currentFailuresAreDeliveredOnceOnClientThreadAndCanBeRetried() throws Exception {
        Fixture fixture = new Fixture();
        List<Throwable> failures = new ArrayList<>();
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add, failures::add);
        fixture.client.calls.get(0).fail();
        assertTrue(failures.isEmpty());
        fixture.thread.drain();
        assertEquals(1, failures.size());
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add, failures::add);
        fixture.client.calls.get(1).respond(200, history(200));
        fixture.thread.drain();
        assertEquals(1, failures.size());
        assertEquals(1, fixture.displayed.size());
    }

    @Test
    public void staleFailuresCannotOverwriteANewerSelectionOrHiddenView() throws Exception {
        Fixture fixture = new Fixture();
        List<Throwable> failures = new ArrayList<>();
        fixture.loader.load(4151, Timestep.ONE_HOUR, fixture.displayed::add, failures::add);
        fixture.loader.load(2, Timestep.ONE_HOUR, fixture.displayed::add, failures::add);
        fixture.client.calls.get(1).respond(200, history(200));
        fixture.client.calls.get(0).fail();
        fixture.thread.drain();
        assertEquals(1, fixture.displayed.size());
        assertTrue(failures.isEmpty());
        fixture.loader.load(3, Timestep.ONE_HOUR, fixture.displayed::add, failures::add);
        fixture.client.calls.get(2).fail();
        fixture.loader.clear();
        fixture.thread.drain();
        assertTrue(failures.isEmpty());
    }

    private static String history(int price) {
        return "{\"data\":[{\"timestamp\":100,\"avgHighPrice\":" + price + ",\"avgLowPrice\":40}]}";
    }

    private static class Fixture {
        final ManualClient client = new ManualClient();
        final QueuedClientThread thread = new QueuedClientThread();
        final List<TimeseriesResponse> displayed = new ArrayList<>();
        final TimeseriesFetcher fetcher;
        final GraphDataLoader loader;
        Fixture() {
            FlippingPlugin plugin = new FlippingPlugin();
            plugin.gson = new Gson();
            fetcher = new TimeseriesFetcher(client, plugin);
            loader = new GraphDataLoader(fetcher, thread);
        }
    }

    private static class QueuedClientThread extends ClientThread {
        final Queue<Runnable> callbacks = new ArrayDeque<>();
        @Override public void invokeLater(Runnable callback) { callbacks.add(callback); }
        void drain() {
            while (!callbacks.isEmpty()) {
                callbacks.remove().run();
            }
        }
    }
}
