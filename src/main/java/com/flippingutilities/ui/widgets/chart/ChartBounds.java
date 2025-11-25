package com.flippingutilities.ui.widgets.chart;

public final class ChartBounds {
    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public ChartBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int getRightEdge() {
        return x + width;
    }

    public int getBottomEdge() {
        return y + height;
    }
}
