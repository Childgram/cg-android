package org.telegram.messenger;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real storage against an isolated negative-owner fixture database. */
public final class ChildgramUsageStorageInstrumentation {
    private static final long OWNER = Long.MIN_VALUE + 731;
    private static final long BUBBLE_OWNER = Long.MIN_VALUE + 732;
    private static final String NAME = "childgram_usage_" + OWNER + ".db";
    private int assertions;

    public static final class Result {
        public int assertions;
        public long bytesBefore, bytesAfter;
    }

    public static Result run() throws Exception {
        return new ChildgramUsageStorageInstrumentation().checks();
    }

    public static BubbleFixture openBubbleFixture(long accountOwner) throws Exception {
        if (accountOwner <= 0) throw new AssertionError("bubble fixture requires an active account");
        synchronized (ChildgramUsageStorage.class) {
            Map<Long, ChildgramUsageStorage> stores = instances();
            if (stores.containsKey(BUBBLE_OWNER)) throw new AssertionError("bubble fixture is already open");
            File file = new File(ApplicationLoader.getFilesDirFixed(), "childgram_usage_" + BUBBLE_OWNER + ".db");
            if (!SQLiteDatabase.deleteDatabase(file) && file.exists()) throw new AssertionError("old bubble fixture could not be removed");
            ChildgramUsageStorage storage = ChildgramUsageStorage.getInstance(BUBBLE_OWNER);
            return new BubbleFixture(accountOwner, file, storage, stores.put(accountOwner, storage));
        }
    }

    public static final class BubbleFixture implements AutoCloseable {
        private final long accountOwner;
        private final File file;
        private final ChildgramUsageStorage storage, previous;
        private boolean closed;

        private BubbleFixture(long accountOwner, File file, ChildgramUsageStorage storage, ChildgramUsageStorage previous) {
            this.accountOwner = accountOwner;
            this.file = file;
            this.storage = storage;
            this.previous = previous;
        }

        /** Persisted foreground milliseconds for the main chat, the bubble chat, and all screens. */
        public long[] totals(long mainPeer, long bubblePeer) throws Exception {
            return onStorageQueue(storage, () -> {
                long[] times = new long[3];
                SQLiteDatabase db = (SQLiteDatabase) storageField("database").get(storage);
                if (db == null) return times; // Nothing has been saved yet.
                String mainKey = "chat:" + mainPeer, bubbleKey = "chat:" + bubblePeer;
                try (Cursor rows = db.rawQuery("SELECT screen, SUM(end_utc - start_utc) FROM intervals WHERE kind=? GROUP BY screen",
                        new String[]{Integer.toString(ChildgramUsageStorage.SCREEN)})) {
                    while (rows.moveToNext()) {
                        long duration = rows.getLong(1);
                        String key = rows.getString(0);
                        if (mainKey.equals(key)) times[0] = duration;
                        if (bubbleKey.equals(key)) times[1] = duration;
                        times[2] += duration;
                    }
                }
                return times;
            });
        }

        @Override
        public void close() throws Exception {
            synchronized (ChildgramUsageStorage.class) {
                if (closed) return;
                Map<Long, ChildgramUsageStorage> stores = instances();
                if (previous == null) stores.remove(accountOwner);
                else stores.put(accountOwner, previous);
                closed = true;
            }
            closeFixture(BUBBLE_OWNER, storage, file);
        }
    }

    private Result checks() throws Exception {
        expect("storage fixture owner is never a Telegram account", OWNER < 0);
        File file = new File(ApplicationLoader.getFilesDirFixed(), NAME);
        expect("storage fixture has the exact isolated filename", file.getName().equals(NAME));
        // Remove only a leftover fixture from an interrupted earlier test run.
        SQLiteDatabase.deleteDatabase(file);
        long legacyStart = Instant.parse("2026-10-29T09:00:00Z").toEpochMilli();
        try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            legacy.execSQL("CREATE TABLE intervals (id TEXT PRIMARY KEY, screen TEXT NOT NULL, label TEXT NOT NULL, start_utc INTEGER NOT NULL, end_utc INTEGER NOT NULL, video INTEGER NOT NULL)");
            legacy.execSQL("INSERT INTO intervals VALUES (?, ?, ?, ?, ?, ?)",
                    new Object[]{"legacy", "chat:legacy", "Legacy video", legacyStart, legacyStart + 90_000, 1});
        }
        ChildgramUsageStorage storage = ChildgramUsageStorage.getInstance(OWNER);
        Result result = new Result();
        try {
            long cutoff = Instant.parse("2026-10-23T22:00:00Z").toEpochMilli(); // Oct 24 00:00, Podgorica
            long today = Instant.parse("2026-10-30T23:00:00Z").toEpochMilli(); // Oct 31 00:00, Podgorica
            long hour = 3_600_000;
            ChildgramUsageStorage.Report legacy = read(storage, "2026-10-29");
            expect("legacy migration preserves screen and video history", !legacy.failed && legacy.total == 90_000
                    && legacy.foreground == 90_000 && legacy.video == 90_000 && legacy.fullscreen == 90_000
                    && legacy.entries.size() == 1 && legacy.mediaEntries.size() == 1);
            expect("legacy video gains its original source chat", legacy.mediaEntries.get(0).key.equals("chat:legacy")
                    && legacy.mediaEntries.get(0).fullscreen == 90_000 && legacy.pipInside == 0 && legacy.pipOutside == 0);
            try (SQLiteDatabase migrated = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor row = migrated.rawQuery("SELECT kind,start_utc,end_utc,label FROM intervals WHERE id='legacy'", null)) {
                expect("migration defaults old rows to SCREEN without changing their data", row.moveToFirst()
                        && row.getInt(0) == ChildgramUsageStorage.SCREEN && row.getLong(1) == legacyStart
                        && row.getLong(2) == legacyStart + 90_000 && row.getString(3).equals("Legacy video"));
            }
            checkPip(storage);
            storage.save("crossing", "chat:boundary", "Boundary fixture", cutoff - 20_000, cutoff + 40_000, true);
            storage.save("pip-crossing", "chat:pip-boundary", "PiP boundary fixture", cutoff - 10_000, cutoff + 60_000,
                    true, ChildgramUsageStorage.PIP_OUTSIDE);
            storage.save("ending", "old", "Expired fixture", cutoff - 60_000, cutoff, false);
            storage.save("today", "chat:today", "Today fixture", today + 10 * hour, today + 10 * hour + 120_000, false);
            // A checkpoint updates the existing interval rather than counting it twice.
            storage.save("today", "chat:today", "Today fixture", today + 10 * hour, today + 10 * hour + 180_000, false);
            storage.save("video", "chat:today", "Today fixture", today + 10 * hour + 180_000, today + 10 * hour + 240_000, true);
            long yesterday = today - 24 * hour;
            storage.save("gift", "gifts", "gifts", yesterday, yesterday + 60_000, false);
            storage.save("legacy-other", "other", "other", yesterday + 60_000, yesterday + 120_000, false);
            ChildgramUsageStorage.Report gifts = read(storage, "2026-10-30");
            expect("gift and legacy other records remain separate", !gifts.failed && gifts.entries.size() == 2
                    && gifts.entries.stream().anyMatch(entry -> entry.key.equals("gifts") && entry.total == 60_000)
                    && gifts.entries.stream().anyMatch(entry -> entry.key.equals("other") && entry.total == 60_000));
            expect("gift browsing is counted once and is not full-screen video", gifts.total == 120_000 && gifts.video == 0);

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
            expect("legacy save still means foreground screen", before.foreground == before.total
                    && before.fullscreen == before.video && before.pipInside == 0 && before.pipOutside == 0);
            expect("full-screen video appears in separate source statistics", before.mediaEntries.size() == 1
                    && before.mediaEntries.get(0).key.equals("chat:today") && before.mediaEntries.get(0).total == 60_000);
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
            expect("all interval kinds are clipped and unioned at retention boundary", boundary.total == 60_000
                    && boundary.video == 60_000 && boundary.foreground == 40_000 && boundary.fullscreen == 40_000
                    && boundary.pipOutside == 60_000 && boundary.mediaEntries.size() == 2);
            expect("oldest retained timestamp equals local cutoff", boundary.firstUtc == cutoff);
            checkHours(boundary);
            ChildgramUsageStorage.Report expired = read(storage, "2026-10-23");
            expect("older local day is empty after cleanup", !expired.failed && expired.total == 0 && expired.entries.isEmpty());
            ChildgramUsageStorage.Report after = read(storage, "2026-10-31");
            expect("today survives retention cleanup", !after.failed && after.total == 240_000 && after.video == 60_000);
            checkHours(after);
            result.bytesAfter = after.fileSize;
            expect("VACUUM shrank real database and sidecar files", after.fileSize < before.fileSize / 4 && after.fileSize < 1_000_000);
            expect("migrated legacy history survives pruning", read(storage, "2026-10-29").total == 90_000);
            expect("inside and outside PiP history survive pruning", read(storage, "2026-10-28").total == 30 * 60_000);
        } finally {
            closeFixture(OWNER, storage, file);
        }
        expect("fixture database was removed", !file.exists());
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            expect("fixture sidecar was removed", !new File(file.getPath() + suffix).exists());
        }
        result.assertions = assertions;
        return result;
    }

    private void checkPip(ChildgramUsageStorage storage) throws Exception {
        long minute = 60_000;
        long start = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli(); // 10:00 local
        storage.save("pip-screen", "settings", "settings", start, start + 20 * minute, false);
        storage.save("same-start-screen", "settings", "settings", start, start + minute, false);
        storage.save("nested-screen", "settings", "settings", start + 5 * minute, start + 7 * minute, false);
        storage.save("pip-in", "chat:source", "Source", start + 10 * minute, start + 15 * minute, true, ChildgramUsageStorage.PIP_INSIDE);
        storage.save("pip-in", "chat:source", "Source", start + 10 * minute, start + 20 * minute, true, ChildgramUsageStorage.PIP_INSIDE);
        storage.save("nested-pip", "chat:source", "Source", start + 12 * minute, start + 14 * minute, true, ChildgramUsageStorage.PIP_INSIDE);
        storage.save("other-source", "chat:another", "Another source", start + 15 * minute, start + 18 * minute, true, ChildgramUsageStorage.PIP_INSIDE);
        storage.save("pip-out", "chat:source", "Source", start + 20 * minute, start + 30 * minute, true, ChildgramUsageStorage.PIP_OUTSIDE);
        ChildgramUsageStorage.Report report = read(storage, "2026-10-28");
        expect("20 foreground minutes plus 10 outside minutes equals 30 total", !report.failed
                && report.total == 30 * minute && report.foreground == 20 * minute);
        expect("overlapping video sources do not inflate video totals", report.video == 20 * minute
                && report.fullscreen == 0 && report.pipInside == 10 * minute && report.pipOutside == 10 * minute);
        expect("parallel PiP does not change screen entries", report.entries.size() == 1
                && report.entries.get(0).key.equals("settings") && report.entries.get(0).total == 20 * minute
                && report.entries.get(0).video == 0);
        expect("media sources remain separate and sorted", report.mediaEntries.size() == 2
                && report.mediaEntries.get(0).key.equals("chat:source") && report.mediaEntries.get(1).total == 3 * minute);
        ChildgramUsageStorage.Entry media = report.mediaEntries.get(0);
        expect("source time unions checkpoints and nested PiP intervals", media.total == 20 * minute
                && media.video == media.total && media.pipInside == 10 * minute && media.pipOutside == 10 * minute && media.fullscreen == 0);
        expect("source and overall hourly unions agree", report.hourlyTotal[10] == 30 * minute
                && report.hourlyVideo[10] == 20 * minute && media.hourlyTotal[10] == 20 * minute
                && media.hourlyVideo[10] == media.hourlyTotal[10]);
        checkHours(report);

        long midnightStart = Instant.parse("2026-10-26T22:50:00Z").toEpochMilli(); // 23:50 local
        storage.save("midnight-screen", "settings", "settings", midnightStart, midnightStart + 20 * minute, false);
        storage.save("midnight-pip", "chat:source", "Source", midnightStart + 5 * minute, midnightStart + 25 * minute,
                true, ChildgramUsageStorage.PIP_OUTSIDE);
        ChildgramUsageStorage.Report beforeMidnight = read(storage, "2026-10-26"), afterMidnight = read(storage, "2026-10-27");
        expect("union clips both overlapping streams before midnight", beforeMidnight.total == 10 * minute
                && beforeMidnight.video == 5 * minute && beforeMidnight.hourlyTotal[23] == 10 * minute);
        expect("union clips both overlapping streams after midnight", afterMidnight.total == 15 * minute
                && afterMidnight.foreground == 10 * minute && afterMidnight.video == 15 * minute
                && afterMidnight.hourlyTotal[0] == 15 * minute);

        long firstTwo = Instant.parse("2026-10-25T00:10:00Z").toEpochMilli();
        long secondTwo = Instant.parse("2026-10-25T01:10:00Z").toEpochMilli();
        storage.save("dst-first", "chat:source", "Source", firstTwo, firstTwo + 10 * minute, true, ChildgramUsageStorage.PIP_OUTSIDE);
        storage.save("dst-second", "chat:source", "Source", secondTwo, secondTwo + 10 * minute, true, ChildgramUsageStorage.PIP_OUTSIDE);
        ChildgramUsageStorage.Report dst = read(storage, "2026-10-25");
        expect("repeated DST hours retain distinct video intervals", dst.hourlyTotal.length == 25 && dst.total == 20 * minute
                && dst.video == 20 * minute && dst.foreground == 0 && dst.pipOutside == 20 * minute
                && dst.hourlyTotal[2] == 10 * minute && dst.hourlyTotal[3] == 10 * minute);
        checkHours(dst);
    }

    private void checkHours(ChildgramUsageStorage.Report report) {
        long total = 0, video = 0;
        for (int i = 0; i < report.hourlyTotal.length; i++) {
            total += report.hourlyTotal[i];
            video += report.hourlyVideo[i];
            expect("hourly video never exceeds total active time", report.hourlyVideo[i] <= report.hourlyTotal[i]);
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

    private static Field storageField(String name) throws Exception {
        Field field = ChildgramUsageStorage.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, ChildgramUsageStorage> instances() throws Exception {
        return (Map<Long, ChildgramUsageStorage>) storageField("instances").get(null);
    }

    private static <T> T onStorageQueue(ChildgramUsageStorage storage, Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        ((DispatchQueue) storageField("queue").get(storage)).postRunnable(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static void closeFixture(long fixtureOwner, ChildgramUsageStorage storage, File file) throws Exception {
        onStorageQueue(storage, () -> {
            Field field = storageField("database");
            SQLiteDatabase db = (SQLiteDatabase) field.get(storage);
            if (db != null) db.close();
            field.set(storage, null);
            return null;
        });
        synchronized (ChildgramUsageStorage.class) {
            instances().remove(fixtureOwner);
            if (!SQLiteDatabase.deleteDatabase(file) && file.exists()) throw new AssertionError("fixture database deletion succeeded");
        }
    }

    private void expect(String label, boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
