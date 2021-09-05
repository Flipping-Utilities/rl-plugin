package com.flippingutilities.jobs;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.utilities.ApiUrl;
import com.flippingutilities.utilities.SlotState;
import com.flippingutilities.utilities.SlotsRequest;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.events.GrandExchangeOfferChanged;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SlotStateSenderJob {
    String api = ApiUrl.getBaseUrl();
    FlippingPlugin plugin;
    ScheduledExecutorService executor;
    OkHttpClient httpClient;
    Future slotStateSenderTask;
    List<SlotState> previouslySentSlotState;

    public SlotStateSenderJob(FlippingPlugin plugin, OkHttpClient httpClient) {
        this.plugin = plugin;
        this.httpClient = httpClient;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        slotStateSenderTask = executor.scheduleAtFixedRate(this::sendSlots, 5,1, TimeUnit.MINUTES);
        log.info("started slot sender job");
    }

    public void stop() {
        if (!slotStateSenderTask.isCancelled() && !slotStateSenderTask.isCancelled()) {
            slotStateSenderTask.cancel(true);
            log.info("shut down slot sender job");
        }
    }

    private void sendSlots() {
        //TODO if user hasn't authenticated yet also should return
        if (plugin.getCurrentlyLoggedInAccount() == null) {
            return;
        }
        try {
            List<SlotState> currentSlotStates = this.getCurrentSlots();
            if (currentSlotStates.equals(this.previouslySentSlotState)) {
                log.info("no updates to slots since the last time I sent them, not sending any requests.");
                return;
            }
            SlotsRequest slotsRequest = new SlotsRequest(plugin.getCurrentlyLoggedInAccount(), currentSlotStates);
            String json = new Gson().newBuilder().setDateFormat(SlotState.DATE_FORMAT).create().toJson(slotsRequest);
            Runnable setPreviouslySentSlots = () -> this.previouslySentSlotState = currentSlotStates;
            String jwt = plugin.getDataHandler().getAccountWideData().getJwt();
            log.info("json slots are {}", json);
            RequestBody body = RequestBody.create(
                    MediaType.parse("application/json"), json);
            Request request = new Request.Builder().
                    header("User-Agent", "FlippingUtilities").
                    header("Authorization", "bearer" + jwt).
                    post(body).
                    url(api).
                    build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log.info("failed to send slots to api", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        log.info("The slot endpoint response was not successful. Response: {}", response.toString());
                        return;
                    }
                    //can't reference "this" from inside the inner class...pain
                    setPreviouslySentSlots.run();
                }
            });

        }
        catch (Exception e) {
            log.info("Exception when sending slots", e);
        }
    }

    /**
     * Gets the current slots by looking at both the history tracked by the plugin (lastOffers in the DataHandler) and
     * the current slots returned by client.getGrandExchangeOffers(). client.getGrandExchangeOffers is always correct.
     * The reason we don't solely rely on it is because it simply tells us the current offers in the ge and is missing
     * additional info such as when the trade for that offer was created. And the reason we can't solely rely on the
     * offers in lastOffers in DataHandler is because it may not always reflect the current state of the slot. For
     * example, if someone collected an offer on mobile and then logged into runelite, the slot is empty, but lastOffers
     * won't reflect that.
     *
     * Whenever the offer in lastOffers reflects the offer actually in the slot, we prefer to use it over the
     * offer in client.getGrandExchangeOffers(). This is because the offer in lastOffers is decorated with additional
     * info such as the time the trade for the offer was created.
     *
     * Whenever the offer in lastOffers does not reflect the offer actually in the slot, we use the offer from
     * client.getGrandExchangeOffers(). However, in this case, the slotState object will have its createdAt field be null
     * as the offer from client.getGrandExchangeOffers() is missing that information.
     */
    private List<SlotState> getCurrentSlots() {
        Map<Integer, OfferEvent> lastOfferEventForEachSlot = plugin.getDataHandler().getAccountData(plugin.getCurrentlyLoggedInAccount()).getLastOffers();
        List<SlotState> slotStates = new ArrayList<>();
        for (int i=0; i<8;i++) {
            //the offer event constructed from the true offer retrieved from client.getGrandExchangeOffers()
            OfferEvent trueOfferInSlot = this.getOfferEventConstructedFromClient(i);

            if (lastOfferEventForEachSlot.containsKey(i)) {
                OfferEvent lastOfferEventForSlotTrackedByPlugin = lastOfferEventForEachSlot.get(i);
                //when tracked offer is the same, prefer to use it as it has more info.
                if (lastOfferEventForSlotTrackedByPlugin.isDuplicate(trueOfferInSlot)) {
                    slotStates.add(SlotState.fromOfferEvent(lastOfferEventForSlotTrackedByPlugin));
                }
                //sometimes tracked offer can be incongruent with slot (collected an offer on mobile). In that case, use
                //the true offer from the client object.
                else {
                    slotStates.add(SlotState.fromOfferEvent(trueOfferInSlot));
                }
            }
            //in the case when there is no tracked offer for the slot
            else {
                //check if the slot is actually empty
                if (trueOfferInSlot.isCausedByEmptySlot()) {
                    slotStates.add(SlotState.createEmptySlot(i));
                }
                //if it is not actually empty, add the true offer we got from client.getGrandExchangeOffers()
                else {
                    slotStates.add(SlotState.fromOfferEvent(trueOfferInSlot));
                }
            }
        }
        return slotStates;
    }

    /**
     * Converts a GrandExchangeOffer from client.getGrandExchangeOffers into an OfferEvent object which is our
     * representation of offer events with extra methods that make it easier to deal with.
     */
    private OfferEvent getOfferEventConstructedFromClient(int slot) {
        GrandExchangeOffer clientOffer = this.plugin.getClient().getGrandExchangeOffers()[slot];
        GrandExchangeOfferChanged grandExchangeOfferChanged = new GrandExchangeOfferChanged();
        grandExchangeOfferChanged.setSlot(slot);
        grandExchangeOfferChanged.setOffer(clientOffer);
        return OfferEvent.fromGrandExchangeEvent(grandExchangeOfferChanged);
    }

}