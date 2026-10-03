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
        public long total, video;
        public long[] hourlyTotal, hourlyVideo;
    }

    public static final class Report {
        public final List<Entry> entries = new ArrayList<>();
        public List<ChildgramUsageTime.Hour> hours;
        public long[] hourlyTotal, hourlyVideo;
        public long total, video, firstUtc, fileSize;
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
            database = SQLiteDatabase.openOrCreateDatabase(file, null);
            database.execSQL("CREATE TABLE IF NOT EXISTS intervals (id TEXT PRIMARY KEY, screen TEXT NOT NULL, label TEXT NOT NULL, start_utc INTEGER NOT NULL, end_utc INTEGER NOT NULL, video INTEGER NOT NULL)");
            database.execSQL("CREATE INDEX IF NOT EXISTS intervals_end ON intervals(end_utc)");
        }
        return database;
    }

    public void save(String id, String screen, String label, long start, long end, boolean video) {
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
            result.hourlyTotal = new long[result.hours.size()];
            result.hourlyVideo = new long[result.hours.size()];
            long start = ChildgramUsageTime.startOfDay(date, zone);
            long end = ChildgramUsageTime.startOfDay(date.plusDays(1), zone);
            try {
                LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
                try (Cursor cursor = db().rawQuery("SELECT screen,label,start_utc,end_utc,video FROM intervals WHERE end_utc>? AND start_utc<? ORDER BY start_utc", new String[]{Long.toString(start), Long.toString(end)})) {
                    while (cursor.moveToNext()) {
                        String key = cursor.getString(0);
                        Entry entry = entries.get(key);
                        if (entry == null) {
                            entry = new Entry();
                            entry.key = key;
                            entry.hourlyTotal = new long[result.hours.size()];
                            entry.hourlyVideo = new long[result.hours.size()];
                            entries.put(key, entry);
                        }
                        entry.label = cursor.getString(1);
                        long intervalStart = cursor.getLong(2), intervalEnd = cursor.getLong(3);
                        boolean video = cursor.getInt(4) != 0;
                        long duration = ChildgramUsageTime.overlap(intervalStart, intervalEnd, start, end);
                        entry.total += duration;
                        result.total += duration;
                        if (video) { entry.video += duration; result.video += duration; }
                        for (int h = 0; h < result.hours.size(); h++) {
                            ChildgramUsageTime.Hour hour = result.hours.get(h);
                            long part = ChildgramUsageTime.overlap(intervalStart, intervalEnd, hour.start, hour.end);
                            entry.hourlyTotal[h] += part;
                            result.hourlyTotal[h] += part;
                            if (video) { entry.hourlyVideo[h] += part; result.hourlyVideo[h] += part; }
                        }
                    }
                }
                result.entries.addAll(entries.values());
                Collections.sort(result.entries, (a, b) -> Long.compare(b.total, a.total));
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
