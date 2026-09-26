package com.flippingutilities.ui.widgets.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.awt.Color;

/**
 * A horizontal line marker at a specific price level.
 */
@Getter
@Builder
@AllArgsConstructor
public class HorizontalMarker implements ChartMarker {
    private final long price;
    private final String label;
    private final Color lineColor;
    private final Color fillColor;
    @Builder.Default private final boolean dashed = false;
    @Builder.Default private final boolean showLabel = true;
    @Builder.Default private final boolean showFill = false;

    /**
     * Creates a horizontal marker for the offer price (solid line with label).
     */
    public static HorizontalMarker offerPrice(long price, String label, Color lineColor) {
        return HorizontalMarker.builder()
                .price(price)
                .label(label)
                .lineColor(lineColor)
                .fillColor(null)
                .dashed(false)
                .showLabel(true)
                .showFill(false)
                .build();
    }

    /**
     * Creates a horizontal marker for tax threshold (dashed line).
     */
    public static HorizontalMarker taxThreshold(long price, String label, Color lineColor) {
        return HorizontalMarker.builder()
                .price(price)
                .label(label)
                .lineColor(lineColor)
                .fillColor(null)
                .dashed(true)
                .showLabel(true)
                .showFill(false)
                .build();
    }

    /**
     * Creates a horizontal marker for desired price.
     */
    public static HorizontalMarker desiredPrice(long price, String label, Color lineColor) {
        return HorizontalMarker.builder()
                .price(price)
                .label(label)
                .lineColor(lineColor)
                .fillColor(null)
                .dashed(true)
                .showLabel(true)
                .showFill(false)
                .build();
    }
}
