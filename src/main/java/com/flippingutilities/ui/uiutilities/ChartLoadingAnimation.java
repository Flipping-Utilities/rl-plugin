/*
 * Copyright (c) 2026, Flipping Utilities
 * All rights reserved.
 */

package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.FontManager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared utility for rendering a loading animation in chart areas.
 * Displays animated sine waves with pulsing dots and "Loading graph..." text.
 */
public class ChartLoadingAnimation {
    private static final int NUM_POINTS = 20;
    private static final double MIN_VALUE = 0;
    private static final double MAX_VALUE = 200;
    private static final double CENTER_VALUE = 100;

    public ChartLoadingAnimation() {}

    /**
     * Renders the loading animation into the chart area.
     *
     * @param graphics     The graphics context to render into
     * @param chartBounds  The bounds of the chart area
     * @param currentTime  Current time in milliseconds (for animation)
     */
    public void render(Graphics2D graphics, Rectangle chartBounds, long currentTime) {
        Shape originalClip = graphics.getClip();
        Object originalAntialiasing = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        graphics.setClip(chartBounds);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        List<Integer> yValuesInstasell = new ArrayList<>(NUM_POINTS);
        List<Integer> yValuesInstabuy = new ArrayList<>(NUM_POINTS);

        // Chart padding
        int plotX = chartBounds.x + 10;
        int plotY = chartBounds.y + 10;
        int plotWidth = chartBounds.width - 20;
        int plotHeight = chartBounds.height - 35;

        // Draw background grid
        drawGrid(graphics, plotX, plotY, plotWidth, plotHeight);

        // Generate Y values for both lines
        generateYValues(yValuesInstabuy, currentTime, true);
        generateYValues(yValuesInstasell, currentTime, false);

        // Draw both animated lines
        drawAnimatedLine(graphics, yValuesInstasell, CustomColors.CHART_INSTASELL, plotX, plotY, plotWidth, plotHeight, currentTime);
        drawAnimatedLine(graphics, yValuesInstabuy, CustomColors.CHART_INSTABUY, plotX, plotY, plotWidth, plotHeight, currentTime);

        // Draw "Loading graph..." text with animated dots
        drawLoadingText(graphics, chartBounds, currentTime);

        graphics.setClip(originalClip);
        if (originalAntialiasing != null) {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, originalAntialiasing);
        }
    }

    /**
     * Draws the background grid lines.
     */
    private void drawGrid(Graphics2D graphics, int plotX, int plotY, int plotWidth, int plotHeight) {
        graphics.setColor(CustomColors.CHART_GRID);
        graphics.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= 4; i++) {
            int y = plotY + (plotHeight * i) / 4;
            graphics.drawLine(plotX, y, plotX + plotWidth, y);
        }
    }

    /**
     * Generates Y values for an animated line.
     *
     * @param yValues     List to populate with generated values
     * @param currentTime Current time for animation
     * @param isHigher    true if you want this line to a bit higher, to limit overlap between mutliple
     */
    private void generateYValues(List<Integer> yValues, long currentTime, boolean isHigher) {
        double baseValue = CENTER_VALUE + (isHigher ? 15 : -15);

        for (int i = 0; i < NUM_POINTS; i++) {
            double seed = (currentTime / 50.0) + i * 0.5 + (isHigher ? 500 : 0);
            double noise = Math.sin(seed * 0.15) * 8 + Math.sin(seed * 0.08) * 6 + Math.sin(seed * 0.25) * 3;
            double competition = Math.sin((currentTime / 300.0) + i * 0.1 + (isHigher ? Math.PI : 0)) * 8;
            double spike = Math.sin(seed * 0.3) > 0.9 ? Math.sin(seed) * 5 : 0;
            double centerPull = (CENTER_VALUE - baseValue) * 0.15;

            baseValue += noise + competition + spike + centerPull;

            if (baseValue < CENTER_VALUE - 30) {
                baseValue = CENTER_VALUE - 30 + (baseValue - (CENTER_VALUE - 30)) * 0.3;
            }
            if (baseValue > CENTER_VALUE + 30) {
                baseValue = CENTER_VALUE + 30 + (baseValue - (CENTER_VALUE + 30)) * 0.3;
            }

            yValues.add((int) baseValue);
        }
    }

    /**
     * Draws an animated line with a pulsing dot at the end.
     */
    private void drawAnimatedLine(Graphics2D graphics, List<Integer> yValues, Color color,
                                  int plotX, int plotY, int plotWidth, int plotHeight, long currentTime) {
        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Draw the line segments
        for (int i = 0; i < yValues.size() - 1; i++) {
            int x1 = plotX + (plotWidth * i) / (NUM_POINTS - 1);
            int x2 = plotX + (plotWidth * (i + 1)) / (NUM_POINTS - 1);
            int y1 = plotY + plotHeight - (int) ((yValues.get(i) - MIN_VALUE) / (MAX_VALUE - MIN_VALUE) * plotHeight);
            int y2 = plotY + plotHeight - (int) ((yValues.get(i + 1) - MIN_VALUE) / (MAX_VALUE - MIN_VALUE) * plotHeight);
            graphics.drawLine(x1, y1, x2, y2);
        }

        // Draw pulsing dot at the end
        int lastX = plotX + plotWidth;
        int lastY = plotY + plotHeight - (int) ((yValues.get(yValues.size() - 1) - MIN_VALUE) / (MAX_VALUE - MIN_VALUE) * plotHeight);

        double pulse = Math.sin(currentTime / 150.0) * 0.3 + 0.7;
        graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (255 * pulse)));
        graphics.fillOval(lastX - 5, lastY - 5, 10, 10);
        graphics.setColor(color.brighter());
        graphics.fillOval(lastX - 3, lastY - 3, 6, 6);
    }

    /**
     * Draws the "Loading graph..." text with animated dots.
     */
    private void drawLoadingText(Graphics2D graphics, Rectangle chartBounds, long currentTime) {
        graphics.setColor(CustomColors.CHART_LABEL);
        graphics.setFont(FontManager.getRunescapeSmallFont());
        String loadingText = "Loading graph...";
        FontMetrics fm = graphics.getFontMetrics();
        int textX = chartBounds.x + chartBounds.width / 2 - fm.stringWidth(loadingText) / 2;
        int textY = chartBounds.y + chartBounds.height - 8;
        graphics.drawString(loadingText, textX, textY);

        // Animated dots
        int numDots = 3;
        int dotIndex = (int) ((currentTime / 400) % (numDots + 1));
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < dotIndex; i++) {
            dots.append(".");
        }
        graphics.drawString(dots.toString(), textX + fm.stringWidth(loadingText) + 2, textY);
   }
}
