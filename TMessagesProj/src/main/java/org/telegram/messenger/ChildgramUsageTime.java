package org.telegram.messenger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** UTC interval math, independent of Android and the persistence layer. */
public final class ChildgramUsageTime {
    private ChildgramUsageTime() {}

    public static long startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public static long overlap(long start, long end, long rangeStart, long rangeEnd) {
        return Math.max(0, Math.min(end, rangeEnd) - Math.max(start, rangeStart));
    }

    public static long cleanupCutoff(LocalDate today, ZoneId zone) {
        return startOfDay(today.minusDays(7), zone);
    }

    public static final class Hour {
        public final long start, end;
        public final String label;

        private Hour(long start, long end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }
    }

    public static List<Hour> hours(LocalDate date, ZoneId zone) {
        final long dayStart = startOfDay(date, zone);
        final long dayEnd = startOfDay(date.plusDays(1), zone);
        TreeSet<Long> boundaries = new TreeSet<>();
        boundaries.add(dayStart);
        boundaries.add(dayEnd);
        for (int h = 0; h < 24; h++) {
            LocalDateTime localHour = date.atTime(h, 0);
            for (ZoneOffset offset : zone.getRules().getValidOffsets(localHour)) {
                long instant = localHour.toInstant(offset).toEpochMilli();
                if (instant >= dayStart && instant < dayEnd) boundaries.add(instant);
            }
        }
        // Include partial hours in zones whose DST transition is not a whole hour.
        ZoneOffsetTransition transition = zone.getRules().nextTransition(Instant.ofEpochMilli(dayStart - 1));
        while (transition != null && transition.getInstant().toEpochMilli() < dayEnd) {
            long instant = transition.getInstant().toEpochMilli();
            if (instant > dayStart) boundaries.add(instant);
            transition = zone.getRules().nextTransition(transition.getInstant());
        }
        ArrayList<Long> points = new ArrayList<>(boundaries);
        ArrayList<Hour> result = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        Set<String> duplicate = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            String label = formatter.format(Instant.ofEpochMilli(points.get(i)).atZone(zone));
            if (!seen.add(label)) duplicate.add(label);
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            java.time.ZonedDateTime instant = Instant.ofEpochMilli(points.get(i)).atZone(zone);
            String label = formatter.format(instant);
            if (duplicate.contains(label)) label += " " + instant.getOffset().getId();
            result.add(new Hour(points.get(i), points.get(i + 1), label));
        }
        return result;
    }
}
