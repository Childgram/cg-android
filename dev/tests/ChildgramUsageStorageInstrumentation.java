package org.telegram.messenger;

import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real storage against an isolated negative-owner fixture database. */
public final class ChildgramUsageStorageInstrumentation {
    private static final long OWNER = Long.MIN_VALUE + 731;
    private static final String NAME = "childgram_usage_" + OWNER + ".db";
    private int assertions;

    public static final class Result {
        public int assertions;
        public long bytesBefore, bytesAfter;
    }

    public static Result run() throws Exception {
        return new ChildgramUsageStorageInstrumentation().checks();
    }

    private Result checks() throws Exception {
        expect("storage fixture owner is never a Telegram account", OWNER < 0);
        File file = new File(ApplicationLoader.getFilesDirFixed(), NAME);
        expect("storage fixture has the exact isolated filename", file.getName().equals(NAME));
        // Remove only a leftover fixture from an interrupted earlier test run.
        SQLiteDatabase.deleteDatabase(file);
        ChildgramUsageStorage storage = ChildgramUsageStorage.getInstance(OWNER);
        Result result = new Result();
        try {
            long cutoff = Instant.parse("2026-10-23T22:00:00Z").toEpochMilli(); // Oct 24 00:00, Podgorica
            long today = Instant.parse("2026-10-30T23:00:00Z").toEpochMilli(); // Oct 31 00:00, Podgorica
            long hour = 3_600_000;
            storage.save("crossing", "chat:boundary", "Boundary fixture", cutoff - 20_000, cutoff + 40_000, true);
            storage.save("ending", "old", "Expired fixture", cutoff - 60_000, cutoff, false);
            storage.save("today", "chat:today", "Today fixture", today + 10 * hour, today + 10 * hour + 120_000, false);
            // A checkpoint updates the existing interval rather than counting it twice.
            storage.save("today", "chat:today", "Today fixture", today + 10 * hour, today + 10 * hour + 180_000, false);
            storage.save("video", "chat:today", "Today fixture", today + 10 * hour + 180_000, today + 10 * hour + 240_000, true);

            char[] padding = new char[65_536];
            Arrays.fill(padding, 'x');
            String oldLabel = new String(padding);
            for (int i = 0; i < 64; i++) {
                storage.save("bulk" + i, "old", oldLabel, cutoff - 4 * hour + i * 1000L,
                        cutoff - 4 * hour + (i + 1) * 1000L, false);
            }

            ChildgramUsageStorage.Report before = read(storage, "2026-10-31");
            expect("storage report read succeeded", !before.failed);
            expect("save checkpoints replace rather than duplicate", before.total == 240_000);
            expect("full-screen video is a subset of total", before.video == 60_000 && before.video <= before.total);
            expect("today has one aggregated chat", before.entries.size() == 1);
            expect("per-chat totals match overall totals", before.entries.get(0).total == before.total
                    && before.entries.get(0).video == before.video);
            checkHours(before);
            result.bytesBefore = before.fileSize;
            expect("fixture grew to several megabytes on disk", before.fileSize > 3_000_000);

            CountDownLatch pruned = new CountDownLatch(1);
            AtomicReference<Boolean> success = new AtomicReference<>();
            storage.prune(cutoff, value -> { success.set(value); pruned.countDown(); });
            expect("storage cleanup callback completed", pruned.await(30, TimeUnit.SECONDS));
            expect("storage cleanup succeeded", Boolean.TRUE.equals(success.get()));

            ChildgramUsageStorage.Report boundary = read(storage, "2026-10-24");
            expect("retention-boundary day read succeeded", !boundary.failed);
            expect("crossing interval is clipped to retained part", boundary.total == 40_000 && boundary.video == 40_000);
            expect("oldest retained timestamp equals local cutoff", boundary.firstUtc == cutoff);
            checkHours(boundary);
            ChildgramUsageStorage.Report expired = read(storage, "2026-10-23");
            expect("older local day is empty after cleanup", !expired.failed && expired.total == 0 && expired.entries.isEmpty());
            ChildgramUsageStorage.Report after = read(storage, "2026-10-31");
            expect("today survives retention cleanup", !after.failed && after.total == 240_000 && after.video == 60_000);
            checkHours(after);
            result.bytesAfter = after.fileSize;
            expect("VACUUM shrank real database and sidecar files", after.fileSize < before.fileSize / 4 && after.fileSize < 1_000_000);
        } finally {
            closeFixture(storage, file);
        }
        expect("fixture database was removed", !file.exists());
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            expect("fixture sidecar was removed", !new File(file.getPath() + suffix).exists());
        }
        result.assertions = assertions;
        return result;
    }

    private void checkHours(ChildgramUsageStorage.Report report) {
        long total = 0, video = 0;
        for (int i = 0; i < report.hourlyTotal.length; i++) {
            total += report.hourlyTotal[i];
            video += report.hourlyVideo[i];
            expect("hourly video never exceeds its foreground total", report.hourlyVideo[i] <= report.hourlyTotal[i]);
        }
        expect("hour buckets preserve total duration", total == report.total);
        expect("hour buckets preserve video duration", video == report.video);
    }

    private ChildgramUsageStorage.Report read(ChildgramUsageStorage storage, String localDate) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<ChildgramUsageStorage.Report> result = new AtomicReference<>();
        Method read = null;
        for (Method method : ChildgramUsageStorage.class.getMethods()) {
            if (method.getName().equals("read") && method.getParameterTypes().length == 3) read = method;
        }
        if (read == null) throw new AssertionError("storage read API exists");
        // The app core-desugars java.time to j$.time. Resolve its runtime date/zone classes
        // without shipping another copy of the desugaring runtime in this test APK.
        Class<?>[] parameters = read.getParameterTypes();
        Object zone = parameters[1].getMethod("of", String.class).invoke(null, "Europe/Podgorica");
        // Release keeps LocalDate.of, but removes the unused LocalDate.parse method.
        LocalDate parsed = LocalDate.parse(localDate);
        Object date = parameters[0].getMethod("of", int.class, int.class, int.class)
                .invoke(null, parsed.getYear(), parsed.getMonthValue(), parsed.getDayOfMonth());
        ChildgramUsageStorage.Callback callback = value -> { result.set(value); ready.countDown(); };
        read.invoke(storage, date, zone, callback);
        expect("storage read callback completed", ready.await(30, TimeUnit.SECONDS));
        return result.get();
    }

    @SuppressWarnings("unchecked")
    private void closeFixture(ChildgramUsageStorage storage, File file) throws Exception {
        Field queueField = ChildgramUsageStorage.class.getDeclaredField("queue");
        Field databaseField = ChildgramUsageStorage.class.getDeclaredField("database");
        Field instancesField = ChildgramUsageStorage.class.getDeclaredField("instances");
        queueField.setAccessible(true);
        databaseField.setAccessible(true);
        instancesField.setAccessible(true);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ((DispatchQueue) queueField.get(storage)).postRunnable(() -> {
            try {
                SQLiteDatabase database = (SQLiteDatabase) databaseField.get(storage);
                if (database != null) database.close();
                databaseField.set(storage, null);
            } catch (Throwable error) { failure.set(error); }
            finally { closed.countDown(); }
        });
        if (!closed.await(30, TimeUnit.SECONDS) || failure.get() != null) throw new AssertionError("fixture database closed safely");
        synchronized (ChildgramUsageStorage.class) {
            ((Map<Long, ChildgramUsageStorage>) instancesField.get(null)).remove(OWNER);
        }
        if (!SQLiteDatabase.deleteDatabase(file) && file.exists()) throw new AssertionError("fixture database deletion succeeded");
    }

    private void expect(String label, boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
