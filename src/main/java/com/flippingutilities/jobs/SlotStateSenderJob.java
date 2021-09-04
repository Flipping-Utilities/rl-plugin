package com.flippingutilities.jobs;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.uiutilities.Paginator;
import com.flippingutilities.utilities.SlotState;
import com.flippingutilities.utilities.WikiRequest;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.sql.Date;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Slf4j
public class SlotStateSenderJob {
    String API;
    FlippingPlugin plugin;
    ScheduledExecutorService executor;
    OkHttpClient httpClient;
    Future slotStateSenderTask;


    public SlotStateSenderJob(FlippingPlugin plugin, OkHttpClient httpClient) {
        this.plugin = plugin;
        this.httpClient = httpClient;
        this.executor = Executors.newSingleThreadScheduledExecutor();
        if (System.getenv("apiurl") == null) {
            API = "something";
        }
        else {
            API = System.getenv("apiurl");
        }
    }

    public void start() {
        slotStateSenderTask = executor.scheduleAtFixedRate(this::sendSlots, 5,1, TimeUnit.SECONDS);
        log.info("started slot sender job");
    }

    public void stop() {
        if (!slotStateSenderTask.isCancelled() && !slotStateSenderTask.isCancelled()) {
            slotStateSenderTask.cancel(true);
            log.info("shut down slot sender job");
        }
    }

    private void sendSlots() {
//        if (plugin.getCurrentlyLoggedInAccount() == null) {
//            return;
//        }
//        try {
//            Map<Integer, OfferEvent> lastOfferEventForEachSlot = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();
//            Map<Integer, SlotState> slots = lastOfferEventForEachSlot.entrySet().stream().collect(Collectors.toMap((e -> e.getKey()), e -> SlotState.fromOfferEvent(e.getValue())));
//            String json = new Gson().newBuilder().setDateFormat("YYYY-MM-dd'T'HH:mm:ss.sssZ").create().toJson(slots);
//            log.info("json slots are {}", json);
//        }
//        catch (Exception e) {
//            log.info("Exception when sending slots", e);
//        }
//
//        RequestBody body = RequestBody.create(
//                MediaType.parse("application/json"), json);
//        Request request = new Request.Builder().
//                header("User-Agent", "FlippingUtilities").
//                post(body).
//                url(API).
//                build();
//        httpClient.newCall(request).enqueue(new Callback() {
//            @Override
//            public void onFailure(Call call, IOException e) {
//                log.info("failed to send slots to api");
//            }
//
//            @Override
//            public void onResponse(Call call, Response response) throws IOException {
//                try (ResponseBody responseBody = response.body()) {
//                    if (!response.isSuccessful()) {
//                        log.info("The slot endpoint response was not successful. Response: {}", response.toString());
//                    }
//                    try {
//                        //parse response
//                    }
//                    catch (JsonSyntaxException e) {
//                        log.info("Unable to parse response from the slot endpoint. Body: {}", responseBody.toString());
//                    }
//                }
//            }
//        });
    }

//    private List<>

}