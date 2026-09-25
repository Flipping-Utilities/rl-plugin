package com.flippingutilities.jobs;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.utilities.WikiDataSource;
import com.flippingutilities.utilities.WikiRequest;
import com.flippingutilities.utilities.WikiRequestWrapper;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.WorldType;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Responsible for handling all of the requests for wiki realtime data and ensuring too many requests aren't being made.
 */
@Slf4j
public class WikiDataFetcherJob {
    public static int requestInterval = 60; //seconds
    static final String API = "https://prices.runescape.wiki/api/v1/osrs/latest";
    static final String DEADMAN_API = "https://prices.runescape.wiki/api/v1/dmm/latest";
    FlippingPlugin plugin;
    ScheduledExecutorService executor;
    OkHttpClient httpClient;
    List<BiConsumer<WikiRequestWrapper, Instant>> subscribers = new ArrayList<>();
    Future wikiDataFetchTask;
    Instant timeOfLastRequestCompletion;
    boolean inFlightRequest = false;
    String apiUrl = API;
    private long requestGeneration;


    public WikiDataFetcherJob(FlippingPlugin plugin, OkHttpClient httpClient) {
        this.plugin = plugin;
        this.httpClient = httpClient;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    public synchronized void subscribe(BiConsumer<WikiRequestWrapper, Instant> subscriber) {
        subscribers.add(subscriber);
    }

    public void start() {
        wikiDataFetchTask = executor.scheduleAtFixedRate(() -> this.attemptToFetchWikiData(false), 5,1, TimeUnit.SECONDS);
        log.debug("started wiki fetching job");
    }

    public void stop() {
        if (!wikiDataFetchTask.isCancelled() && !wikiDataFetchTask.isCancelled()) {
            wikiDataFetchTask.cancel(true);
            log.debug("shut down wiki fetching job");
        }
    }

    public synchronized void onWorldSwitch(EnumSet<WorldType> worldType) {
        if (worldType.contains(WorldType.DEADMAN)) {
            log.debug("Switching to requesting deadman api");
            apiUrl = DEADMAN_API;
        }
        else {
            apiUrl = API;
        }

        attemptToFetchWikiData(true);
    }

    private WikiDataSource getWikiDataSourceType() {
        if (apiUrl.equals(DEADMAN_API)) {
            return WikiDataSource.DMM;
        }
        return WikiDataSource.REGULAR;
    }


    //only problem with this is that then master panel will be visible even if they have opened and then closed flipping utils
    //as long as they haven't opened another plugin. But if they have another plugin open or they haven't opened flipping utils
    //then masterpanel.isVisible() will correctly return false.
    private boolean shouldFetch() {
        boolean lastRequestOldEnough = timeOfLastRequestCompletion == null || Instant.now().minus(requestInterval, ChronoUnit.SECONDS).isAfter(timeOfLastRequestCompletion);
        //for the purpose of SlotStateDrawer, we need wiki data even if the master panel is not visible, but only
        //if the user is premium.
        return (plugin.getMasterPanel().isVisible() || plugin.getApiAuthHandler().isPremium()) && !inFlightRequest && lastRequestOldEnough;
    }

    public synchronized void attemptToFetchWikiData(boolean force) {
        if (!force && !shouldFetch()) {
            return;
        }
        inFlightRequest = true;
        long generation = ++requestGeneration;
        WikiDataSource source = getWikiDataSourceType();
        Request request = new Request.Builder().header("User-Agent", "FlippingUtilities").url(apiUrl).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                completeRequest(generation, source, null);
            }

            @Override
            public void onResponse(Call call, Response response) {
                WikiRequest result = null;
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null) {
                        result = plugin.gson.fromJson(response.body().string(), WikiRequest.class);
                    }
                } catch (IOException | JsonSyntaxException e) {
                    log.debug("Could not read wiki prices", e);
                } finally {
                    completeRequest(generation, source, result);
                }
            }
        });
    }

    private void completeRequest(long generation, WikiDataSource source, WikiRequest result) {
        // Notify subscribers OUTSIDE the job monitor: subscribers (e.g. the plugin's
        // onWikiFetch) run arbitrary work on this OkHttp callback thread, and holding the
        // monitor meanwhile blocks the scheduler thread and world-switch fetch attempts
        // that also need it (up to deadlock if a subscriber takes a lock held while
        // attempting a fetch).
        List<BiConsumer<WikiRequestWrapper, Instant>> toNotify;
        Instant completedAt;
        synchronized (this) {
            // A world switch or forced refresh may have started a newer request.
            if (generation != requestGeneration) {
                return;
            }
            completedAt = Instant.now();
            timeOfLastRequestCompletion = completedAt;
            inFlightRequest = false;
            if (result == null) {
                return;
            }
            toNotify = new ArrayList<>(subscribers);
        }
        WikiRequestWrapper wrapper = new WikiRequestWrapper(result, source);
        toNotify.forEach(subscriber -> subscriber.accept(wrapper, completedAt));
    }
}
