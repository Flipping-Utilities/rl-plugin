package com.flippingutilities.ui.widgets;

import com.flippingutilities.model.Timestep;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeLabelGenerator {

    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("MMM dd");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMM");
    private static final DateTimeFormatter MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("MMM yy");

    public static String[] generate(Timestep timestep, long currentTimeSeconds) {
        int labelCount = timestep.getLabelCount();
        String[] labels = new String[labelCount];

        long rangeSeconds = timestep.getMaxTimeRangeSeconds();
        long intervalSeconds = rangeSeconds / (labelCount - 1);

        for (int i = 0; i < labelCount; i++) {
            long secondsAgo = rangeSeconds - (i * intervalSeconds);
            long timestamp = currentTimeSeconds - secondsAgo;
            labels[i] = formatTimestamp(timestamp, timestep);
        }

        return labels;
    }

    private static String formatTimestamp(long timestampSeconds, Timestep timestep) {
        Instant instant = Instant.ofEpochSecond(timestampSeconds);
        ZoneId zoneId = ZoneId.systemDefault();

        switch (timestep) {
            case FIVE_MINUTES:
                return HOUR_FORMATTER.format(instant.atZone(zoneId));
            case ONE_HOUR:
                return DAY_FORMATTER.format(instant.atZone(zoneId));
            case SIX_HOURS:
                return DAY_FORMATTER.format(instant.atZone(zoneId));
            case TWENTY_FOUR_HOURS:
                int timestampYear = instant.atZone(zoneId).getYear();
                int currentYear = Instant.now().atZone(zoneId).getYear();
                if (timestampYear == currentYear) {
                    return MONTH_FORMATTER.format(instant.atZone(zoneId));
                } else {
                    return MONTH_YEAR_FORMATTER.format(instant.atZone(zoneId));
                }
            default:
                return "";
        }
    }
}
