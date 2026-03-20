package com.flippingutilities.ui.widgets.graph;

import java.awt.Color;

/**
 * Base interface for chart markers (horizontal bars, vertical bars, areas).
 */
public interface ChartMarker {
    
    /**
     * Gets the label for this marker.
     */
    String getLabel();
    
    /**
     * Gets the line color for this marker.
     */
    Color getLineColor();
    
    /**
     * Gets the fill color for this marker (may be null for no fill).
     */
    Color getFillColor();
    
    /**
     * Returns true if the marker line should be dashed.
     */
    boolean isDashed();
    
    /**
     * Returns true if the label should be shown.
     */
    boolean isShowLabel();
}
