package com.flippingutilities.ui.widgets;

import lombok.Builder;
import lombok.Getter;

import java.awt.Color;
import java.awt.Font;

@Getter
@Builder
public final class ChartConfig {

    @Builder.Default
    private final int width = 320;

    @Builder.Default
    private final int height = 200;

    @Builder.Default
    private final int topPadding = 15;

    @Builder.Default
    private final int bottomPadding = 25;

    @Builder.Default
    private final int rightPadding = 15;

    @Builder.Default
    private final Color backgroundColor = new Color(0, 0, 0, 180);

    @Builder.Default
    private final Color gridColor = new Color(50, 50, 50, 200);

    @Builder.Default
    private final Color fillColor = new Color(80, 80, 80, 100);

    @Builder.Default
    private final Color highLineColor = new Color(0, 200, 0);

    @Builder.Default
    private final Color lowLineColor = new Color(200, 0, 0);

    @Builder.Default
    private final Color referenceLineColor = new Color(0, 200, 255);

    @Builder.Default
    private final Color labelColor = new Color(200, 200, 200);

    @Builder.Default
    private final float lineStroke = 1.3f;

    @Builder.Default
    private final float gridStroke = 0.5f;

    @Builder.Default
    private final float referenceStroke = 1.3f;

    private final Font labelFont;
}
