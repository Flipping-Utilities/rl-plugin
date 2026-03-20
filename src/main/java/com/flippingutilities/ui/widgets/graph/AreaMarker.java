package com.flippingutilities.ui.widgets.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.awt.Color;

/**
 * An area marker between two price levels (filled region with border).
 */
@Getter
@Builder
@AllArgsConstructor
public class AreaMarker implements ChartMarker {
    
    /**
     * Position of the label relative to the area marker.
     */
    public enum LabelPosition {
        TOP,    // Label above the top line (for buy offers - tax region above price)
        BOTTOM  // Label below the bottom line (for sell offers - tax region below price)
    }
    
    private final int minPrice;
    private final int maxPrice;
    private final String label;
    private final Color lineColor;
    private final Color fillColor;
    @Builder.Default private final boolean dashed = true;
    @Builder.Default private final boolean showLabel = true;
    @Builder.Default private final LabelPosition labelPosition = LabelPosition.TOP;

    /**
     * Creates a tax region area marker (filled area with dashed border).
     * Label appears at top by default (for buy offers).
     */
    public static AreaMarker taxRegion(int minPrice, int maxPrice, String label, Color lineColor, Color fillColor) {
        return taxRegion(minPrice, maxPrice, label, lineColor, fillColor, LabelPosition.TOP);
    }
    
    /**
     * Creates a tax region area marker with specified label position.
     * Use LabelPosition.BOTTOM for sell offers (tax region below the price).
     */
    public static AreaMarker taxRegion(int minPrice, int maxPrice, String label, Color lineColor, Color fillColor, LabelPosition labelPosition) {
        return AreaMarker.builder()
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .label(label)
                .lineColor(lineColor)
                .fillColor(fillColor)
                .dashed(true)
                .showLabel(true)
                .labelPosition(labelPosition)
                .build();
    }

    /**
     * Creates a generic area marker between two price levels.
     */
    public static AreaMarker between(int minPrice, int maxPrice, String label, Color lineColor, Color fillColor) {
        return AreaMarker.builder()
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .label(label)
                .lineColor(lineColor)
                .fillColor(fillColor)
                .dashed(false)
                .showLabel(true)
                .build();
    }
}
