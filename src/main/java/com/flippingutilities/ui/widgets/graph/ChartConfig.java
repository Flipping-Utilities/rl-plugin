package com.flippingutilities.ui.widgets.graph;

import lombok.Builder;
import lombok.Getter;
import net.runelite.client.ui.FontManager;

import java.awt.Color;
import java.awt.Font;

import com.flippingutilities.ui.uiutilities.CustomColors;

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
    private final Color backgroundColor = CustomColors.CHART_BACKGROUND;

    @Builder.Default
    private final Color gridColor = CustomColors.CHART_GRID;
    @Builder.Default
    private final Color fillColor = CustomColors.CHART_FILL;

    @Builder.Default
    private final Color highLineColor = CustomColors.CHART_INSTABUY;

    @Builder.Default
    private final Color lowLineColor = CustomColors.CHART_INSTASELL;

    @Builder.Default
    private final Color referenceLineColor = CustomColors.CHART_ACCENT;

    @Builder.Default
    private final Color labelColor = CustomColors.CHART_LABEL;

    @Builder.Default
    private final float lineStroke = 1.3f;

    @Builder.Default
    private final float gridStroke = 0.5f;

    @Builder.Default
    private final float referenceStroke = 1.3f;

    @Builder.Default
    private final Font labelFont = FontManager.getRunescapeSmallFont();
}
