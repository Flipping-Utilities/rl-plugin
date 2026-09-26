package com.flippingutilities.utilities;

import com.google.gson.annotations.JsonAdapter;
import lombok.Data;

/**
 * Latest prices for one item from the wiki API.
 *
 * Prices retain the full long range. Null prices mean that the wiki has not seen that
 * side traded; hasHigh/hasLow distinguish missing prices while the price getters return
 * zero for consumers that compare market prices with offers.
 */
@Data
public class WikiItemMargins {
    @JsonAdapter(LenientLongAdapter.class)
    Long high;

    @JsonAdapter(LenientLongAdapter.class)
    Long highTime;

    @JsonAdapter(LenientLongAdapter.class)
    Long low;

    @JsonAdapter(LenientLongAdapter.class)
    Long lowTime;

    /** True when the wiki has an instant-buy (high) price for this item. */
    public boolean hasHigh() {
        return high != null && high > 0;
    }

    /** True when the wiki has an instant-sell (low) price for this item. */
    public boolean hasLow() {
        return low != null && low > 0;
    }

    /** The instant-buy price, or zero when the wiki has no data. */
    public long getHigh() {
        return high == null ? 0 : high;
    }

    /** The instant-sell price, or zero when the wiki has no data. */
    public long getLow() {
        return low == null ? 0 : low;
    }
}
