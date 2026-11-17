import org.telegram.messenger.ChildgramUsageTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public final class ChildgramUsageTimeTest {
    private static final long HOUR = 3_600_000;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static long length(List<ChildgramUsageTime.Hour> hours) {
        long result = 0, previous = -1;
        for (ChildgramUsageTime.Hour hour : hours) {
            check(hour.end > hour.start, "Positive bucket length");
            if (previous != -1) check(previous == hour.start, "No gaps or overlapping hour buckets");
            result += hour.end - hour.start;
            previous = hour.end;
        }
        return result;
    }

    public static void main(String[] args) {
        ChildgramUsageTime.Union union = new ChildgramUsageTime.Union();
        long minute = 60_000;
        long total = union.add(0, 20 * minute);
        total += union.add(10 * minute, 20 * minute);
        total += union.add(12 * minute, 14 * minute);
        total += union.add(20 * minute, 30 * minute);
        check(total == 30 * minute, "Screen plus overlapping inside PiP plus outside PiP counts 30, not 40 minutes");
        check(union.add(30 * minute, 30 * minute) == 0, "Empty intervals add no time");
        check(union.add(40 * minute, 45 * minute) == 5 * minute, "Union does not count gaps");
        ChildgramUsageTime.Union sameStart = new ChildgramUsageTime.Union();
        check(sameStart.add(-10, 10) + sameStart.add(-10, 20) + sameStart.add(0, 5) == 30,
                "Equal starts, negative timestamps and nested intervals preserve the union");
        ChildgramUsageTime.Union shorterEnd = new ChildgramUsageTime.Union();
        check(shorterEnd.add(0, 20) + shorterEnd.add(0, 5) + shorterEnd.add(10, 15) + shorterEnd.add(15, 30) == 30,
                "Equal starts and decreasing ends never lose the covered tail");

        ZoneId zone = ZoneId.of("Europe/Podgorica");
        List<ChildgramUsageTime.Hour> spring = ChildgramUsageTime.hours(LocalDate.of(2026, 3, 29), zone);
        check(spring.size() == 23 && length(spring) == 23 * HOUR, "Spring day has 23 hours");
        List<ChildgramUsageTime.Hour> autumn = ChildgramUsageTime.hours(LocalDate.of(2026, 10, 25), zone);
        check(autumn.size() == 25 && length(autumn) == 25 * HOUR, "Autumn day has 25 hours");
        check(!autumn.get(2).label.equals(autumn.get(3).label), "Repeated hours have distinct labels");
        check(length(ChildgramUsageTime.hours(LocalDate.of(2026, 10, 4), ZoneId.of("Australia/Lord_Howe"))) == 23 * HOUR + HOUR / 2,
                "Half-hour DST does not invent or lose time");

        long midnight = ChildgramUsageTime.startOfDay(LocalDate.of(2026, 10, 2), zone);
        check(ChildgramUsageTime.overlap(midnight - 10_000, midnight + 20_000, midnight - HOUR, midnight) == 10_000,
                "Midnight split preserves previous-day part");
        check(ChildgramUsageTime.overlap(midnight - 10_000, midnight + 20_000, midnight, midnight + HOUR) == 20_000,
                "Midnight split preserves next-day part");
        check(ChildgramUsageTime.overlap(midnight, midnight + HOUR, midnight + HOUR, midnight + 2 * HOUR) == 0,
                "Half-open ranges do not count boundaries twice");

        long utc = Instant.parse("2026-10-02T00:30:00Z").toEpochMilli();
        ZoneId ny = ZoneId.of("America/New_York");
        long nyStart = ChildgramUsageTime.startOfDay(LocalDate.of(2026, 10, 1), ny);
        long nyEnd = ChildgramUsageTime.startOfDay(LocalDate.of(2026, 10, 2), ny);
        check(ChildgramUsageTime.overlap(utc, utc + 60_000, nyStart, nyEnd) == 60_000,
                "UTC interval appears on previous local day after timezone change");

        LocalDate today = LocalDate.of(2026, 10, 31);
        long cutoff = ChildgramUsageTime.cleanupCutoff(today, zone);
        check(Instant.ofEpochMilli(cutoff).atZone(zone).toLocalDate().equals(today.minusDays(7)), "Cleanup keeps seven full local days");
        check(ChildgramUsageTime.startOfDay(today, zone) - cutoff == 169 * HOUR, "Cleanup is calendar-based across DST");
        check(ChildgramUsageTime.overlap(cutoff - HOUR, cutoff + HOUR, cutoff, cutoff + 24 * HOUR) == HOUR,
                "Cleanup clips an interval crossing the retention boundary");
        check(ChildgramUsageTime.overlap(cutoff - HOUR, cutoff, cutoff, cutoff + HOUR) == 0,
                "Cleanup deletes intervals ending exactly at cutoff");
        System.out.println("Childgram usage time checks passed");
    }
}
