package com.flippingutilities.ui.widgets.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.awt.Color;

/**
 * A vertical line marker at a specific timestamp.
 */
@Getter
@Builder
@AllArgsConstructor
public class VerticalMarker implements ChartMarker {
    private final long timestamp;
    private final String label;
    private final Color lineColor;
    private final Color fillColor;
    @Builder.Default private final boolean dashed = true;
    @Builder.Default private final boolean showLabel = true;

    /**
     * Creates a vertical marker at a specific timestamp.
     */
    public static VerticalMarker at(long timestamp, String label, Color lineColor) {
        return VerticalMarker.builder()
                .timestamp(timestamp)
                .label(label)
                .lineColor(lineColor)
                .fillColor(null)
                .dashed(true)
                .showLabel(true)
                .build();
    }

    /**
     * Creates a vertical marker at a timestamp with a solid line.
     */
    public static VerticalMarker solidAt(long timestamp, String label, Color lineColor) {
        return VerticalMarker.builder()
                .timestamp(timestamp)
                .label(label)
                .lineColor(lineColor)
                .fillColor(null)
                .dashed(false)
                .showLabel(true)
                .build();
    }
}
