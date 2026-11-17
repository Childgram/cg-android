package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private, local usage history, separated by Telegram user ID rather than account slot. */
public final class ChildgramUsageStorage {
    public static final long CLEANUP_THRESHOLD = 100_000_000L;
    public static final int SCREEN = 0, PIP_INSIDE = 1, PIP_OUTSIDE = 2;
    private static final Map<Long, ChildgramUsageStorage> instances = new HashMap<>();

    public static synchronized ChildgramUsageStorage getInstance(long owner) {
        ChildgramUsageStorage storage = instances.get(owner);
        if (storage == null) instances.put(owner, storage = new ChildgramUsageStorage(owner));
        return storage;
    }

    public interface Callback { void run(Report report); }
    public interface PruneCallback { void run(boolean success); }

    public static final class Entry {
        public String key, label;
        public long total, video, fullscreen, pipInside, pipOutside;
        public long[] hourlyTotal, hourlyVideo;
    }

    public static final class Report {
        public final List<Entry> entries = new ArrayList<>();
        public final List<Entry> mediaEntries = new ArrayList<>();
        public List<ChildgramUsageTime.Hour> hours;
        public long[] hourlyTotal, hourlyVideo;
        public long total, video, foreground, fullscreen, pipInside, pipOutside, firstUtc, fileSize;
        public boolean failed;
    }

    private final File file;
    private final DispatchQueue queue = new DispatchQueue("childgramUsage");
    private SQLiteDatabase database;

    private ChildgramUsageStorage(long owner) {
        file = new File(ApplicationLoader.getFilesDirFixed(), "childgram_usage_" + owner + ".db");
    }

    private SQLiteDatabase db() {
        if (database == null) {
            SQLiteDatabase opened = SQLiteDatabase.openOrCreateDatabase(file, null);
            try {
                opened.beginTransaction();
                try {
                    opened.execSQL("CREATE TABLE IF NOT EXISTS intervals (id TEXT PRIMARY KEY, screen TEXT NOT NULL, label TEXT NOT NULL, start_utc INTEGER NOT NULL, end_utc INTEGER NOT NULL, video INTEGER NOT NULL, kind INTEGER NOT NULL DEFAULT 0)");
                    boolean hasKind = false;
                    try (Cursor columns = opened.rawQuery("PRAGMA table_info(intervals)", null)) {
                        while (columns.moveToNext()) hasKind |= "kind".equals(columns.getString(columns.getColumnIndexOrThrow("name")));
                    }
                    if (!hasKind) opened.execSQL("ALTER TABLE intervals ADD COLUMN kind INTEGER NOT NULL DEFAULT 0");
                    opened.execSQL("CREATE INDEX IF NOT EXISTS intervals_end ON intervals(end_utc)");
                    opened.setTransactionSuccessful();
                } finally {
                    opened.endTransaction();
                }
                database = opened;
            } catch (RuntimeException error) {
                opened.close();
                throw error;
            }
        }
        return database;
    }

    public void save(String id, String screen, String label, long start, long end, boolean video) {
        save(id, screen, label, start, end, video, SCREEN);
    }

    public void save(String id, String screen, String label, long start, long end, boolean video, int kind) {
        if (kind < SCREEN || kind > PIP_OUTSIDE) throw new IllegalArgumentException("Unknown usage interval kind");
        if (end <= start) return;
        queue.postRunnable(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("screen", screen);
                values.put("label", label);
                values.put("start_utc", start);
                values.put("end_utc", end);
                values.put("video", video ? 1 : 0);
                values.put("kind", kind);
                if (db().insertWithOnConflict("intervals", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                    throw new IllegalStateException("Could not persist Childgram usage interval");
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public void read(LocalDate date, ZoneId zone, Callback callback) {
        queue.postRunnable(() -> {
            Report result = new Report();
            result.hours = ChildgramUsageTime.hours(date, zone);
            Totals all = new Totals(null, result.hours);
            result.hourlyTotal = all.entry.hourlyTotal;
            result.hourlyVideo = all.entry.hourlyVideo;
            ChildgramUsageTime.Union foreground = new ChildgramUsageTime.Union();
            long start = ChildgramUsageTime.startOfDay(date, zone);
            long end = ChildgramUsageTime.startOfDay(date.plusDays(1), zone);
            try {
                LinkedHashMap<String, Totals> entries = new LinkedHashMap<>(), mediaEntries = new LinkedHashMap<>();
                try (Cursor cursor = db().rawQuery("SELECT screen,label,start_utc,end_utc,video,kind FROM intervals WHERE end_utc>? AND start_utc<? ORDER BY start_utc", new String[]{Long.toString(start), Long.toString(end)})) {
                    while (cursor.moveToNext()) {
                        String key = cursor.getString(0);
                        String label = cursor.getString(1);
                        long intervalStart = Math.max(start, cursor.getLong(2)), intervalEnd = Math.min(end, cursor.getLong(3));
                        boolean video = cursor.getInt(4) != 0;
                        int kind = cursor.getInt(5);
                        all.add(label, intervalStart, intervalEnd, video, kind);
                        if (kind == SCREEN) {
                            result.foreground += foreground.add(intervalStart, intervalEnd);
                            entries.computeIfAbsent(key, k -> new Totals(k, result.hours)).add(label, intervalStart, intervalEnd, video, kind);
                        }
                        if (video || kind != SCREEN)
                            mediaEntries.computeIfAbsent(key, k -> new Totals(k, result.hours)).add(label, intervalStart, intervalEnd, true, kind);
                    }
                }
                result.total = all.entry.total;
                result.video = all.entry.video;
                result.fullscreen = all.entry.fullscreen;
                result.pipInside = all.entry.pipInside;
                result.pipOutside = all.entry.pipOutside;
                for (Totals totals : entries.values()) result.entries.add(totals.entry);
                for (Totals totals : mediaEntries.values()) result.mediaEntries.add(totals.entry);
                Collections.sort(result.entries, (a, b) -> Long.compare(b.total, a.total));
                Collections.sort(result.mediaEntries, (a, b) -> Long.compare(b.total, a.total));
                try (Cursor cursor = db().rawQuery("SELECT MIN(start_utc) FROM intervals", null)) {
                    if (cursor.moveToFirst() && !cursor.isNull(0)) result.firstUtc = cursor.getLong(0);
                }
                result.fileSize = fileSize();
            } catch (Exception e) {
                FileLog.e(e);
                result.failed = true;
            }
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    /** SQL supplies start-ordered rows, so only the uncovered tail contributes to each union. */
    private static final class Totals {
        final Entry entry = new Entry();
        final List<ChildgramUsageTime.Hour> hours;
        final ChildgramUsageTime.Union total = new ChildgramUsageTime.Union(), video = new ChildgramUsageTime.Union();
        final ChildgramUsageTime.Union[] modes = {new ChildgramUsageTime.Union(), new ChildgramUsageTime.Union(), new ChildgramUsageTime.Union()};

        Totals(String key, List<ChildgramUsageTime.Hour> hours) {
            entry.key = key;
            this.hours = hours;
            entry.hourlyTotal = new long[hours.size()];
            entry.hourlyVideo = new long[hours.size()];
        }

        void add(String label, long start, long end, boolean hasVideo, int kind) {
            entry.label = label;
            long added = total.add(start, end);
            entry.total += added;
            addHours(entry.hourlyTotal, end - added, end);
            if (hasVideo || kind != SCREEN) {
                added = video.add(start, end);
                entry.video += added;
                addHours(entry.hourlyVideo, end - added, end);
                added = modes[kind].add(start, end);
                if (kind == SCREEN) entry.fullscreen += added;
                else if (kind == PIP_INSIDE) entry.pipInside += added;
                else entry.pipOutside += added;
            }
        }

        private void addHours(long[] values, long start, long end) {
            if (end <= start) return;
            for (int h = 0; h < hours.size(); h++) {
                ChildgramUsageTime.Hour hour = hours.get(h);
                values[h] += ChildgramUsageTime.overlap(start, end, hour.start, hour.end);
            }
        }
    }

    public void prune(long cutoff, PruneCallback callback) {
        queue.postRunnable(() -> {
            boolean success = false;
            try {
                SQLiteDatabase db = db();
                db.beginTransaction();
                try {
                    db.delete("intervals", "end_utc<=?", new String[]{Long.toString(cutoff)});
                    db.execSQL("UPDATE intervals SET start_utc=? WHERE start_utc<? AND end_utc>?", new Object[]{cutoff, cutoff, cutoff});
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                // DELETE frees pages internally; VACUUM returns them to the filesystem.
                try (Cursor cursor = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) { cursor.moveToFirst(); }
                db.execSQL("VACUUM");
                try (Cursor cursor = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) { cursor.moveToFirst(); }
                success = true;
            } catch (Exception e) {
                FileLog.e(e);
            }
            final boolean completed = success;
            AndroidUtilities.runOnUIThread(() -> callback.run(completed));
        });
    }

    private long fileSize() {
        long size = file.length();
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) size += new File(file.getPath() + suffix).length();
        return size;
    }
}
