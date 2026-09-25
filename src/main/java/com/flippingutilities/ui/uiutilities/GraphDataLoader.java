package com.flippingutilities.ui.uiutilities;

import com.flippingutilities.jobs.TimeseriesFetcher;
import com.flippingutilities.model.TimeseriesResponse;
import com.flippingutilities.model.Timestep;
import net.runelite.client.callback.ClientThread;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns the pending price-history selection for one chart or tooltip. HTTP work can
 * be shared by the fetcher, while each view independently ignores old responses.
 */
public final class GraphDataLoader {
    private final TimeseriesFetcher fetcher;
    private final ClientThread clientThread;
    private final AtomicLong generation = new AtomicLong();

    public GraphDataLoader(TimeseriesFetcher fetcher, ClientThread clientThread) {
        this.fetcher = fetcher;
        this.clientThread = clientThread;
    }

    public void load(int itemId, Timestep timestep, Consumer<TimeseriesResponse> onData) {
        long requestGeneration = generation.incrementAndGet();
        fetcher.fetch(itemId, timestep, response -> clientThread.invokeLater(() -> {
            if (generation.get() == requestGeneration) {
                onData.accept(response);
            }
        }));
    }

    /** Discard a pending response when the view is hidden, even for the same item. */
    public void clear() {
        generation.incrementAndGet();
    }
}
