package com.flippingutilities.jobs;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.utilities.RemoteSlot;
import com.flippingutilities.utilities.RemoteAccountSlots;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Responsible for fetching slots from the api and handing them off to subscribers
 */
@Slf4j
public class SlotFetcherJob {
    FlippingPlugin plugin;
    ScheduledExecutorService executor;
    OkHttpClient httpClient;
    Future slotFetcherTask;
    List<RemoteAccountSlots> previousRemoteAccountSlots;
    List<Consumer<List<RemoteAccountSlots>>> subscribers = new ArrayList<>();
    public static int PERIOD = 5; //seconds

    public SlotFetcherJob(FlippingPlugin plugin, OkHttpClient httpClient, ScheduledExecutorService executor) {
        this.plugin = plugin;
        this.httpClient = httpClient;
        this.executor = executor;
    }

    public void subscribe(Consumer<List<RemoteAccountSlots>> subscriber) {
        subscribers.add(subscriber);
    }

    public void start() {
        slotFetcherTask = executor.scheduleAtFixedRate(this::fetchSlots, 10, PERIOD, TimeUnit.SECONDS);
        log.info("started slot fetcher job");
    }

    public void stop() {
        if (!slotFetcherTask.isCancelled()) {
            slotFetcherTask.cancel(true);
            log.info("shut down slot fetcher job");
        }
    }

    private void fetchSlots() {
        if (!plugin.getApiAuthHandler().canCommunicateWithApi(plugin.getCurrentlyLoggedInAccount()) ||
            !plugin.getApiAuthHandler().isPremium()) {
            return;
        }

        plugin.getApiRequestHandler().fetchGeSlots()
            .whenComplete((response, exception) -> {
                if (exception != null) {
                    log.debug("could not fetch slots successfully", exception);
                } else {
                    if (this.areSlotResponsesEqual(previousRemoteAccountSlots, response)) {
                        previousRemoteAccountSlots = response;
                        return;
                    }
                    previousRemoteAccountSlots = response;
                    subscribers.forEach(subscriber -> subscriber.accept(response));
                }
            });
    }

    private boolean areSlotResponsesEqual(List<RemoteAccountSlots> previous, List<RemoteAccountSlots> current) {
        if (previous == null) {
            return false;
        }
        if (previous.size() != current.size()) {
            return false;
        }
        //not the most efficient, could use a hashmap, but the lists are really small and don't feel like
        //writing more code for this...
        for (RemoteAccountSlots remoteAccountSlots : current) {
            Optional<RemoteAccountSlots> maybeCorrespondingRemoteAccountSlots  = previous.stream().
                filter(r -> r.getRsn().equals(remoteAccountSlots.getRsn())).findFirst();

            if (!maybeCorrespondingRemoteAccountSlots.isPresent()) {
                return false;
            }
            RemoteAccountSlots correspondingSlotResponse = maybeCorrespondingRemoteAccountSlots.get();
            if (correspondingSlotResponse.getSlots().size() != remoteAccountSlots.getSlots().size()) {
                return false;
            }
            remoteAccountSlots.getSlots().sort(Comparator.comparingInt(RemoteSlot::getIndex));
            correspondingSlotResponse.getSlots().sort(Comparator.comparingInt(RemoteSlot::getIndex));

            if (!remoteAccountSlots.getSlots().equals(correspondingSlotResponse.getSlots())) {
                return false;
            }
        }
        return true;

    }
}
