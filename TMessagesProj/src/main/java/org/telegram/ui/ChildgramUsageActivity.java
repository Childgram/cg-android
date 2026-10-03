package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChildgramUsageStorage;
import org.telegram.messenger.ChildgramUsageTime;
import org.telegram.messenger.ChildgramUsageTracker;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;

public final class ChildgramUsageActivity extends UniversalFragment {
    private LocalDate date = LocalDate.now();
    private ZoneId zone = ZoneId.systemDefault();
    private ChildgramUsageStorage.Report report;
    private String selected;
    private LinearLayout navigation;
    private TextView previous, dateLabel, next;
    private HourChart chart;
    private int request;
    private boolean resumed;
    private boolean clearing;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            reload();
            if (resumed) AndroidUtilities.runOnUIThread(this, 5000);
        }
    };

    @Override public View createView(Context context) {
        navigation = new LinearLayout(context);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        previous = navigationButton(context, "‹", getString(R.string.ChildgramUsagePrevious));
        next = navigationButton(context, "›", getString(R.string.ChildgramUsageNext));
        dateLabel = navigationButton(context, "", "");
        dateLabel.setTextSize(16);
        navigation.addView(previous, LayoutHelper.createLinear(56, 56));
        navigation.addView(dateLabel, new LinearLayout.LayoutParams(0, AndroidUtilities.dp(56), 1));
        navigation.addView(next, LayoutHelper.createLinear(56, 56));
        previous.setOnClickListener(v -> changeDay(-1));
        next.setOnClickListener(v -> changeDay(1));
        chart = new HourChart(context);
        View result = super.createView(context);
        reload();
        return result;
    }

    private TextView navigationButton(Context context, String text, String description) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(30);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        view.setContentDescription(description);
        return view;
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        refresh.run();
    }

    @Override public void onPause() {
        resumed = false;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        super.onPause();
    }

    @Override public void onFragmentDestroy() {
        resumed = false;
        request++;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        super.onFragmentDestroy();
    }

    @Override protected CharSequence getTitle() { return getString(R.string.ChildgramUsageTitle); }

    private ChildgramUsageStorage storage() {
        return ChildgramUsageStorage.getInstance(getUserConfig().getClientUserId());
    }

    private void reload() {
        if (listView == null || clearing) return;
        zone = ZoneId.systemDefault();
        if (date.isAfter(LocalDate.now(zone))) date = LocalDate.now(zone);
        final int token = ++request;
        ChildgramUsageTracker.flush();
        storage().read(date, zone, value -> {
            if (token != request || isFinished || listView == null) return;
            report = value;
            updateNavigation();
            listView.adapter.update(false);
            chart.invalidate();
        });
    }

    private void updateNavigation() {
        dateLabel.setText(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(LocaleController.getInstance().getCurrentLocale())));
        dateLabel.setContentDescription(dateLabel.getText());
        boolean canGoBack = report != null && report.firstUtc != 0
                && date.isAfter(Instant.ofEpochMilli(report.firstUtc).atZone(zone).toLocalDate());
        previous.setEnabled(canGoBack);
        previous.setAlpha(canGoBack ? 1f : .35f);
        boolean canGoNext = date.isBefore(LocalDate.now(zone));
        next.setEnabled(canGoNext);
        next.setAlpha(canGoNext ? 1f : .35f);
    }

    private void changeDay(int delta) {
        LocalDate target = date.plusDays(delta);
        if (target.isAfter(LocalDate.now(ZoneId.systemDefault()))) return;
        date = target;
        report = null;
        chart.selectedHour = -1;
        updateNavigation();
        listView.adapter.update(false);
        reload();
    }

    private ChildgramUsageStorage.Entry selection() {
        if (report != null && selected != null) {
            for (ChildgramUsageStorage.Entry entry : report.entries) if (selected.equals(entry.key)) return entry;
        }
        return null;
    }

    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCustom(navigation, 56));
        if (report == null) {
            items.add(UItem.asShadow(getString(R.string.Loading)));
            return;
        }
        if (report.failed) {
            items.add(UItem.asShadow(getString(R.string.ChildgramUsageError)));
            return;
        }
        ChildgramUsageStorage.Entry entry = selection();
        if (entry == null) selected = null;
        items.add(UItem.asButton(1, (selected == null ? "✓ " : "") + getString(R.string.ChildgramUsageTotal), duration(report.total)));
        items.add(UItem.asHeader(entry == null ? getString(R.string.ChildgramUsageHourly) : label(entry)));
        items.add(UItem.asCustom(chart, 220));
        items.add(UItem.asShadow(getString(R.string.ChildgramUsageVideo) + ": " + duration(entry == null ? report.video : entry.video)
                + "\n" + zone.getId() + "\n" + getString(R.string.ChildgramUsageSelect)));
        if (report.entries.isEmpty()) items.add(UItem.asShadow(getString(R.string.ChildgramUsageEmpty)));
        else {
            items.add(UItem.asHeader(getString(R.string.ChildgramUsageChats)));
            for (int i = 0; i < report.entries.size(); i++) {
                ChildgramUsageStorage.Entry row = report.entries.get(i);
                items.add(UItem.asButton(100 + i, (row.key.equals(selected) ? "✓ " : "") + label(row), duration(row.total)));
            }
        }
        if (report.fileSize > ChildgramUsageStorage.CLEANUP_THRESHOLD) {
            items.add(UItem.asShadow(AndroidUtilities.formatFileSize(report.fileSize)));
            items.add(UItem.asButton(2, getString(R.string.ChildgramUsageClear)).setEnabled(!clearing));
        }
    }

    private String label(ChildgramUsageStorage.Entry entry) {
        switch (entry.key) {
            case "dialogs": return getString(R.string.ChildgramUsageDialogs);
            case "settings": return getString(R.string.ChildgramUsageSettings);
            case "contacts": return getString(R.string.ChildgramUsageContacts);
            case "calls": return getString(R.string.ChildgramUsageCalls);
            case "profile": return getString(R.string.ChildgramUsageProfile);
            case "other": return getString(R.string.ChildgramUsageOther);
            default: return entry.label;
        }
    }

    private static String duration(long millis) {
        return LocaleController.formatDuration((int) Math.min(Integer.MAX_VALUE, millis / 1000));
    }

    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == 2) {
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.ChildgramUsageClear))
                    .setMessage(getString(R.string.ChildgramUsageClearInfo))
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
                        clearing = true;
                        request++;
                        ChildgramUsageTracker.splitForCleanup();
                        listView.adapter.update(false);
                        storage().prune(ChildgramUsageTime.cleanupCutoff(LocalDate.now(), ZoneId.systemDefault()), success -> {
                            clearing = false;
                            reload();
                            if (!success && !isFinished && getParentActivity() != null) {
                                showDialog(new AlertDialog.Builder(getParentActivity())
                                        .setMessage(getString(R.string.ChildgramUsageError))
                                        .setPositiveButton(getString(R.string.OK), null).create());
                            }
                        });
                    }).create());
            return;
        }
        if (item.id == 1) selected = null;
        else if (report != null && item.id >= 100 && item.id - 100 < report.entries.size()) selected = report.entries.get(item.id - 100).key;
        else return;
        chart.selectedHour = -1;
        chart.invalidate();
        listView.adapter.update(false);
    }

    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }

    private final class HourChart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selectedHour = -1;

        HourChart(Context context) {
            super(context);
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            setMinimumHeight(AndroidUtilities.dp(220));
            setClickable(true);
            setFocusable(true);
            setContentDescription(getString(R.string.ChildgramUsageHourly));
        }

        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(MeasureSpec.getSize(width), AndroidUtilities.dp(220));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (report == null || report.hours == null || report.hours.isEmpty()) return;
            ChildgramUsageStorage.Entry entry = selection();
            long[] totals = entry == null ? report.hourlyTotal : entry.hourlyTotal;
            long[] videos = entry == null ? report.hourlyVideo : entry.hourlyVideo;
            long max = 60_000;
            for (long total : totals) max = Math.max(max, total);
            float left = AndroidUtilities.dp(16), width = getWidth() - 2 * left;
            float step = width / totals.length;
            float baseline = AndroidUtilities.dp(182), barHeight = AndroidUtilities.dp(110);
            paint.setTextSize(AndroidUtilities.dp(11));
            paint.setColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            if (selectedHour >= 0 && selectedHour < totals.length) {
                String summary = report.hours.get(selectedHour).label + " · " + duration(totals[selectedHour]);
                canvas.drawText(summary, left, AndroidUtilities.dp(23), paint);
                String video = getString(R.string.ChildgramUsageVideo) + ": " + duration(videos[selectedHour]);
                canvas.drawText(video, left, AndroidUtilities.dp(43), paint);
                setContentDescription(summary + ". " + video);
            } else canvas.drawText(duration(max), left, AndroidUtilities.dp(43), paint);
            for (int i = 0; i < totals.length; i++) {
                float x = left + step * i;
                paint.setColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
                paint.setAlpha(selectedHour < 0 || selectedHour == i ? 255 : 110);
                canvas.drawRect(x + 1, baseline - barHeight * totals[i] / max, x + step - 1, baseline, paint);
                paint.setColor(0xffdf8524);
                canvas.drawRect(x + 1, baseline - barHeight * videos[i] / max, x + step - 1, baseline, paint);
                paint.setColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                if (i % 3 == 0) canvas.drawText(report.hours.get(i).label.substring(0, 2), x, baseline + AndroidUtilities.dp(20), paint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && report != null && !report.hours.isEmpty()) {
                float left = AndroidUtilities.dp(16);
                selectedHour = Math.max(0, Math.min(report.hours.size() - 1,
                        (int) ((event.getX() - left) / (getWidth() - 2 * left) * report.hours.size())));
                performClick();
                invalidate();
            }
            return true;
        }

        private boolean selectHour(int delta) {
            if (report == null || report.hours.isEmpty()) return false;
            selectedHour = Math.max(0, Math.min(report.hours.size() - 1, selectedHour + delta));
            announceSelection();
            invalidate();
            return true;
        }

        private void announceSelection() {
            if (report == null || selectedHour < 0 || selectedHour >= report.hours.size()) return;
            ChildgramUsageStorage.Entry entry = selection();
            long total = entry == null ? report.hourlyTotal[selectedHour] : entry.hourlyTotal[selectedHour];
            long video = entry == null ? report.hourlyVideo[selectedHour] : entry.hourlyVideo[selectedHour];
            String summary = report.hours.get(selectedHour).label + ". " + getString(R.string.ChildgramUsageTotal)
                    + ": " + duration(total) + ". " + getString(R.string.ChildgramUsageVideo) + ": " + duration(video);
            setContentDescription(summary);
            announceForAccessibility(summary);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.SeekBar");
            if (report != null && !report.hours.isEmpty()) {
                info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                        0, report.hours.size() - 1, Math.max(0, selectedHour)));
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_CLICK) return selectHour(1);
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return selectHour(-1);
            return super.performAccessibilityAction(action, arguments);
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return selectHour(1);
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return selectHour(-1);
            return super.onKeyDown(keyCode, event);
        }

        @Override public boolean performClick() {
            super.performClick();
            announceSelection();
            return true;
        }
    }
}
