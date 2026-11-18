package org.telegram.messenger;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.voip.VoIPPreNotificationService;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ChildgramBreakOverlay;
import org.telegram.ui.Components.voip.VoIPWindowView;
import org.telegram.ui.LaunchActivity;
import org.webrtc.OrientationHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** API 34 project AVD. Touches only a local fixture, break controls and the call-list shortcut. */
public final class ChildgramBreakInstrumentation extends Instrumentation {
    private static final String[] BREAK_SETTINGS = {"break_reminder", "break_start_minutes", "break_fade_minutes", "break_rest_minutes"};
    private final AtomicInteger touches = new AtomicInteger();
    private final AtomicInteger cancels = new AtomicInteger();
    private final ExecutorService input = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BreakCheckInput");
        thread.setDaemon(true);
        return thread;
    });
    private SharedPreferences settings, clock;
    private final Rect lastWorkingKeyboard = new Rect(), lastKeyboardTask = new Rect();
    private String stage = "setup";
    private Dialog dialog;
    private BaseFragment timingScreen;
    private View target, viewer;
    private int assertions;
    private boolean realTime;
    private volatile boolean coverOnResume;
    private volatile long lastPause, lastResume;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        realTime = arguments != null && "true".equals(arguments.getString("real_time"));
        start();
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (coverOnResume && (Object) activity == LaunchActivity.instance) {
            lastResume = SystemClock.elapsedRealtime();
            // A Dialog registered before the activity's decor would reverse the window discovery order.
            // Cover the first frame without registering another Android window.
            if (timingScreen == null) activity.getWindow().getDecorView().setForeground(new ColorDrawable(Color.WHITE));
        }
    }

    @Override public void callActivityOnPause(Activity activity) {
        super.callActivityOnPause(activity);
        if (coverOnResume && (Object) activity == LaunchActivity.instance) lastPause = SystemClock.elapsedRealtime();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        settings = getTargetContext().getSharedPreferences("childgram_parental", Context.MODE_PRIVATE);
        clock = getTargetContext().getSharedPreferences("childgram_break_time", Context.MODE_PRIVATE);
        Map<String, ?> originalSettings = new HashMap<>(settings.getAll());
        Map<String, ?> originalClock = new HashMap<>(clock.getAll());
        String failure = null;
        try {
            setup();
            if (realTime) realTimeChecks(report);
            else checks(report);
        } catch (Throwable e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            failure = stage + " / " + (cause instanceof AssertionError ? cause.getMessage() : cause.getClass().getSimpleName());
            if (realTime) {
                try {
                    report.putString("timing_state", main(() -> "resumed=" + LaunchActivity.isResumed + ", foreground="
                            + ((Object) ChildgramUsageTracker.getBreakActivity() == LaunchActivity.instance)
                            + ", onFixture=" + (timingScreen != null && currentFragment() == timingScreen)
                            + ", screenOn=" + ApplicationLoader.isScreenOn + ", locked=" + (SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter)
                            + ", call=" + ChildgramBreakCalls.isCallActive() + ", profile=" + clock.getString("profile", "none")
                            + ", debt=" + clock.getLong("debt", 0) + ", overlay=" + (overlay() != null)));
                } catch (Exception ignored) {}
            }
        } finally {
            try {
                coverOnResume = false;
                mark("closing fixture");
                main(() -> {
                    if (viewer != null && viewer.isAttachedToWindow()) ((Activity) viewer.getContext()).getWindowManager().removeViewImmediate(viewer);
                    ChildgramParentalSettings.setBreakReminderEnabled(false);
                    if (LaunchActivity.instance != null) {
                        Activity activity = (Activity) (Object) LaunchActivity.instance;
                        if (timingScreen != null) {
                            activity.getWindow().getDecorView().setVisibility(View.INVISIBLE);
                            timingScreen.removeSelfFromStack(true);
                        }
                        activity.finish();
                    }
                    if (dialog != null) dialog.dismiss();
                    return null;
                });
                await("test activity closes", () -> LaunchActivity.instance == null || ((Activity) (Object) LaunchActivity.instance).isDestroyed());
            } catch (Throwable e) {
                failure = "Could not close test fixture";
            } finally {
                input.shutdownNow();
                SharedPreferences.Editor restore = copyValues(clock.edit().clear(), originalClock);
                SharedPreferences.Editor restoreSetting = settings.edit();
                Map<String, Object> previous = new HashMap<>();
                for (String key : BREAK_SETTINGS) {
                    restoreSetting.remove(key);
                    if (originalSettings.containsKey(key)) previous.put(key, originalSettings.get(key));
                }
                boolean clockRestored = restore.commit();
                boolean settingRestored = copyValues(restoreSetting, previous).commit();
                boolean restored = clockRestored && settingRestored && originalSettings.equals(settings.getAll())
                        && originalClock.equals(clock.getAll());
                report.putBoolean("original_settings_and_timer_restored", restored);
                if (!restored) failure = "Could not restore original settings or timer";
            }
        }
        report.putInt("assertions", assertions);
        report.putString("status", failure == null ? "passed" : "failed");
        if (failure != null) report.putString("failure", failure);
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }

    @SuppressWarnings("unchecked")
    private static SharedPreferences.Editor copyValues(SharedPreferences.Editor editor, Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        }
        return editor;
    }

    private void setup() throws Exception {
        expect("Childgram debug instrumentation required", BuildVars.CHILDGRAM);
        SharedPreferences.Editor defaults = settings.edit();
        for (String key : BREAK_SETTINGS) defaults.remove(key);
        expect("reset only break settings for the default check", defaults.commit());
        expect("break reminders default off", !ChildgramParentalSettings.isBreakReminderEnabled());
        expect("default profile is 10 + 10 with 5 minutes of rest", ChildgramParentalSettings.getBreakStartMinutes() == 10
                && ChildgramParentalSettings.getBreakFadeMinutes() == 10 && ChildgramParentalSettings.getBreakRestMinutes() == 5);
        coverOnResume = realTime;
        if (realTime) main(() -> {
            if (LaunchActivity.isResumed && LaunchActivity.instance != null) {
                Activity activity = (Activity) (Object) LaunchActivity.instance;
                activity.getWindow().getDecorView().setForeground(new ColorDrawable(Color.WHITE));
            }
            return null;
        });
        bringToFront();
        await("app opens", () -> LaunchActivity.isResumed && currentFragment() != null
                && ((Activity) (Object) LaunchActivity.instance).getWindow().getDecorView().isAttachedToWindow());
        expect("no real call active", !main(ChildgramBreakCalls::isCallActive));
        main(() -> {
            // Use the actual Telegram activity window without touching any account content.
            timingScreen = new BaseFragment() {
                @Override protected View createView(Context context) {
                    return fragmentView = realTime ? fixtureText(context) : new InputFixture(context);
                }
            };
            expect("present safe timing fixture", ((BaseFragment) currentFragment()).presentFragment(timingScreen, false, true));
            return null;
        });
    }

    private void showFixture(Activity activity) {
        dialog = new Dialog(activity);
        dialog.setOwnerActivity(activity);
        dialog.setContentView(fixtureText(activity));
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
    }

    private View fixtureText(Context context) {
        TextView sample = new TextView(context);
        sample.setText("Childgram\n\nТестовый экран для проверки перерыва");
        sample.setTextSize(20);
        sample.setTextColor(Color.BLACK);
        sample.setBackgroundColor(Color.WHITE);
        sample.setPadding(40, 40, 40, 40);
        target = sample;
        target.setOnClickListener(v -> touches.incrementAndGet());
        target.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancels.incrementAndGet();
            return false;
        });
        return target;
    }

    private void realTimeChecks(Bundle report) throws Exception {
        PowerManager power = (PowerManager) getTargetContext().getSystemService(Context.POWER_SERVICE);
        // Keep this one emulator run awake without changing its screen timeout.
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock awake = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Childgram:BreakTimingCheck");
        awake.acquire(240_000);
        try {
            Activity activity = (Activity) (Object) LaunchActivity.instance;
            View decor = activity.getWindow().getDecorView();
            await("opaque neutral fixture is visible", () -> currentFragment() == timingScreen && target != null
                    && target.isShown() && target.getHeight() > 0 && decor.hasWindowFocus());
            main(() -> { decor.setForeground(null); return null; });
            mark("checking the three parental timing editors");
            assertions += ChildgramParentalInstrumentation.checkBreakDurationDialogs(this, (Activity) (Object) LaunchActivity.instance);
            main(() -> {
                ChildgramParentalSettings.setBreakReminderEnabled(false);
                ChildgramParentalSettings.setBreakStartMinutes(1);
                ChildgramParentalSettings.setBreakFadeMinutes(1);
                ChildgramParentalSettings.setBreakRestMinutes(1);
                return null;
            });
            await("one-minute settings saved and old balance cleared", () -> ChildgramParentalSettings.getBreakStartMinutes() == 1
                    && ChildgramParentalSettings.getBreakFadeMinutes() == 1 && ChildgramParentalSettings.getBreakRestMinutes() == 1
                    && !clock.contains("debt") && overlay() == null);
            long start = main(() -> {
                ChildgramParentalSettings.setBreakReminderEnabled(true);
                return SystemClock.elapsedRealtime();
            });
            await("real 1/1/1 balance starts growing on the safe fixture", 17_000, () ->
                    "1/1/1".equals(clock.getString("profile", "")) && clock.getLong("debt", 0) > 0);
            await("waiting for the first real minute of use", 59_000, () -> button("ChildgramBreakPrompt") != null);
            long hint = SystemClock.elapsedRealtime() - start;
            report.putLong("first_hint_ms", hint);
            expect("first hint at one minute", hint >= 59_500 && hint <= 65_000);

            mark("letting the native window reach the middle of its fade");
            SystemClock.sleep(Math.max(0, start + 90_500 - SystemClock.elapsedRealtime()));
            float before = visibleDim(target);
            report.putFloat("dim_at_90_seconds", before);
            expect("quadratic fade reaches about 25 percent halfway", before >= .20f && before <= .31f);
            mark("taking a short Home break");
            expect("Home accepted", input.submit(() -> getUiAutomation().performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_HOME)).get(8, TimeUnit.SECONDS));
            await("backgrounding removes the reminder", () -> !LaunchActivity.isResumed && lastPause > start && overlay() == null);
            SystemClock.sleep(4_000);
            bringToFront();
            await("returning to the fixture restores the reminder", () -> lastResume > lastPause
                    && currentFragment() == timingScreen && button("ChildgramBreakPrompt") != null);
            SystemClock.sleep(250); // Sample the frame with the newly attached filter.
            float after = visibleDim(target);
            long away = lastResume - lastPause;
            report.putLong("short_pause_ms", away);
            report.putFloat("dim_after_short_pause", after);
            expect("a short background pause immediately recovers visibility", after < before - .025f && after > 0);

            await("waiting for the real cap", 55_000, () -> button("ChildgramBreakContinue") != null);
            long cap = SystemClock.elapsedRealtime();
            awaitDim("cap covers the white fixture with black pixels", target, .98f, 1f);
            report.putLong("full_screen_ms", cap - start);
            // On this profile, each 1 s of active fade earns 0.8 s of rest. A pause of p
            // seconds postpones the limit by the pause itself plus p / 0.8 s of resumed use.
            long expected = start + 120_000 + Math.round(2.25 * away);
            expect("full screen arrives at the configured limit adjusted for the short break", Math.abs(cap - expected) <= 4500);
            await("waiting for one full minute of real recovery", 70_000, () ->
                    button("ChildgramBreakReady") != null && button("ChildgramBreakContinue") != null);
            long recovery = SystemClock.elapsedRealtime() - cap;
            report.putLong("full_reset_ms", recovery);
            expect("full recovery takes one minute", recovery >= 58_000 && recovery <= 64_000);
            tapButton("ChildgramBreakContinue");
            await("return after full recovery shows an undimmed fixture", () -> overlay() == null
                    && currentFragment() == timingScreen && decor.hasWindowFocus());
            awaitDim("white fixture pixels return after a full break", target, 0f, .04f);
        } finally {
            if (awake.isHeld()) awake.release();
        }
    }

    private float visibleDim(View whiteSurface) throws Exception {
        Rect area = main(() -> bounds(whiteSurface));
        Bitmap screen = getUiAutomation().takeScreenshot();
        expect("compositor returned a frame of the fixture", screen != null && area.intersect(0, 0, screen.getWidth(), screen.getHeight()));
        try {
            // The moving bubble may cross one edge. Both sample points are on a solid white background.
            int y = area.bottom - Math.max(1, area.height() / 8);
            int left = screen.getPixel(area.left + area.width() / 8, y);
            int right = screen.getPixel(area.right - area.width() / 8, y);
            return 1f - Math.max(Color.green(left), Color.green(right)) / 255f;
        } finally { screen.recycle(); }
    }

    private float awaitDim(String label, View surface, float min, float max) throws Exception {
        mark(label);
        long deadline = SystemClock.uptimeMillis() + 4000;
        float dim;
        do {
            dim = visibleDim(surface);
            if (dim >= min && dim <= max) { assertions++; return dim; }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(label + " (measured " + dim + ")");
    }

    private static final class InputFixture extends LinearLayout {
        final ScrollView scroll;
        final Button button;
        final EditText editor;
        final View white;
        int clicks;

        InputFixture(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setBackgroundColor(Color.WHITE);
            setFocusableInTouchMode(true);
            scroll = new ScrollView(context);
            TextView text = new TextView(context);
            text.setText("Childgram\n\nTouch and keyboard test");
            text.setTextColor(Color.BLACK);
            text.setMinHeight(AndroidUtilities.dp(3000));
            scroll.addView(text);
            addView(scroll, new LayoutParams(-1, 0, 1));
            button = new Button(context);
            button.setText("Test button");
            button.setOnClickListener(v -> clicks++);
            addView(button, -1, AndroidUtilities.dp(56));
            editor = new EditText(context);
            editor.setSingleLine(true);
            editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editor.setHint("Test input");
            addView(editor, -1, AndroidUtilities.dp(52));
            white = new View(context);
            white.setBackgroundColor(Color.WHITE);
            addView(white, -1, AndroidUtilities.dp(20));
            requestFocus();
        }
    }

    private boolean checkPartialInput(String host, InputFixture fixture, View root, Bundle report) throws Exception {
        main(() -> { ChildgramParentalSettings.setBreakReminderEnabled(false); return null; });
        await(host + " has an undimmed input fixture", () -> overlay() == null && root.hasWindowFocus() && fixture.white.getHeight() > 0);
        awaitDim(host + " white calibration surface", fixture.white, 0f, .04f);
        seed(2_790_000); // About 83%, with over 50 seconds before reaching the cap.
        await(host + " displays its partial reminder", () -> hostIs(root) && button("ChildgramBreakPrompt") != null);
        float dim = awaitDim(host + " white pixels actually dim over 80 percent", fixture.white, .80f, .98f);
        report.putFloat(host + "_visible_dim", dim);
        boolean dimBehind = main(() -> (((WindowManager.LayoutParams) overlay().getLayoutParams()).flags
                & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0);
        report.putBoolean(host + "_childgram_dim_behind", dimBehind);

        Rect area = main(() -> bounds(fixture.button));
        tap(area.right - area.width() / 8, area.centerY());
        await(host + " receives a tap under heavy dimming", () -> fixture.clicks == 1);
        area = main(() -> bounds(fixture.scroll));
        int x = area.right - area.width() / 8;
        gesture(x, area.bottom - area.height() / 5, x, area.centerY());
        await(host + " receives a scroll gesture under heavy dimming", () -> fixture.scroll.getScrollY() > 0);

        Rect bubble = main(() -> bounds(overlay()));
        int distance = Math.min(160, area.height() / 4);
        gesture(bubble.centerX(), bubble.centerY(), bubble.centerX(), bubble.centerY() + distance);
        await(host + " reminder moves independently", () -> button("ChildgramBreakPrompt") != null
                && bounds(overlay()).centerY() > bubble.centerY() + distance / 2);

        area = main(() -> bounds(fixture.editor));
        tap(area.right - area.width() / 8, area.centerY());
        InputMethodManager ime = (InputMethodManager) getTargetContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        await(host + " editor owns the active IME", 6000, () -> fixture.editor.hasFocus() && ime.isActive(fixture.editor)
                && fixture.editor.getRootWindowInsets().isVisible(WindowInsets.Type.ime()));
        AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        if ((info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(info);
        }
        Rect keys = new Rect();
        Rect previous = new Rect();
        long[] unchangedSince = {0};
        await(host + " system keyboard is visible and its key bounds have settled", 6000, () -> {
            if (!fixture.editor.hasFocus() || !ime.isActive(fixture.editor)) return false;
            WindowMetrics metrics = ((Activity) (Object) LaunchActivity.instance).getWindowManager().getCurrentWindowMetrics();
            Rect task = metrics.getBounds();
            WindowInsets insets = fixture.editor.getRootWindowInsets();
            if (!insets.isVisible(WindowInsets.Type.ime())) return false;
            Rect visible = new Rect();
            root.getWindowVisibleDisplayFrame(visible);
            int bottom = task.bottom - insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int imeHeight = Math.max(insets.getInsets(WindowInsets.Type.ime()).bottom,
                    metrics.getWindowInsets().getInsets(WindowInsets.Type.ime()).bottom);
            int top = Math.min(task.bottom - imeHeight, visible.bottom);
            Rect usableScreen = new Rect(task.left, task.top, task.right, bottom);
            keys.set(task.left, top, task.right, bottom);
            Rect accessible = new Rect();
            for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    window.getBoundsInScreen(accessible);
                    break;
                }
            }
            // The accessibility window may already report its full size while still below the
            // display during the slide-in animation. Prefer the IME area reported to the editor.
            String source = "insets";
            if (!usableKeyboard(keys, usableScreen)) {
                source = "window";
                if (!keys.setIntersect(accessible, usableScreen) || !usableKeyboard(keys, usableScreen)) {
                    source = "previous";
                    if (lastKeyboardTask.equals(task)) keys.set(lastWorkingKeyboard);
                    else keys.setEmpty();
                }
            }
            report.putString(host + "_ime_bounds", "window=" + accessible.toShortString() + ", visible="
                    + visible.toShortString() + ", insets=" + insets.getInsets(WindowInsets.Type.ime()).bottom
                    + ", keys=" + keys.toShortString() + ", source=" + source);
            long now = SystemClock.uptimeMillis();
            if (!keys.equals(previous)) {
                previous.set(keys);
                unchangedSince[0] = now;
            }
            return usableKeyboard(keys, usableScreen) && now - unchangedSince[0] >= 500;
        });
        // A real touch on an alphabetic key in the middle row. Never inspect suggestions or saved text.
        int keyX = keys.centerX(), keyY = keys.top + keys.height() / 2;
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, keyX, keyY, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                expect(host + " IME receives injected touch", input.submit(() -> getUiAutomation().injectInputEvent(event, true)).get(8, TimeUnit.SECONDS));
            } finally { event.recycle(); }
        }
        await(host + " real IME key inserts a letter in the fixture", 5000, () -> fixture.editor.length() > 0
                && Character.isLetter(Character.codePointAt(fixture.editor.getText(), 0)));
        lastWorkingKeyboard.set(keys);
        lastKeyboardTask.set(((Activity) (Object) LaunchActivity.instance).getWindowManager().getCurrentWindowMetrics().getBounds());
        report.putBoolean(host + "_heavy_dim_input_ok", true);
        main(() -> { ime.hideSoftInputFromWindow(fixture.editor.getWindowToken(), 0); return null; });
        await(host + " closes the keyboard", () -> !fixture.editor.getRootWindowInsets().isVisible(WindowInsets.Type.ime()));
        return dimBehind;
    }

    private static boolean usableKeyboard(Rect keys, Rect screen) {
        return screen.contains(keys) && keys.width() >= screen.width() / 2
                && keys.height() >= AndroidUtilities.dp(96) && keys.top > screen.top + screen.height() / 4;
    }

    private void checks(Bundle report) throws Exception {
        Activity activity = (Activity) (Object) LaunchActivity.instance;
        await("neutral Telegram screen is ready", () -> currentFragment() == timingScreen && timingScreen.getFragmentView() instanceof InputFixture
                && timingScreen.getFragmentView().getHeight() > 0);
        InputFixture page = main(() -> (InputFixture) timingScreen.getFragmentView());
        boolean pageUsesWindowDim = checkPartialInput("activity", page, activity.getWindow().getDecorView(), report);
        InputFixture modal = main(() -> {
            dialog = new Dialog(activity);
            dialog.setOwnerActivity(activity);
            InputFixture content = new InputFixture(activity);
            dialog.setContentView(content);
            dialog.show();
            dialog.getWindow().setLayout(-1, -1);
            return content;
        });
        boolean dialogUsesWindowDim = checkPartialInput("dialog", modal, dialog.getWindow().getDecorView(), report);
        mark("checking system dim flags after both hosts passed input checks");
        expect("partial Childgram windows must not request system DIM_BEHIND", !pageUsesWindowDim && !dialogUsesWindowDim);
        main(() -> { dialog.dismiss(); showFixture(activity); return null; });
        seed(1_800_000); // 15 active minutes.
        await("soft reminder attaches to the dialog", () -> hostIs(dialog.getWindow().getDecorView())
                && button("ChildgramBreakPrompt") != null && bounds(target).height() > 200);
        expect("enabled preference stored", settings.getBoolean("break_reminder", false));
        float dim = awaitDim("15 minutes dims actual white fixture pixels by 25 percent", target, .21f, .29f);
        screenshot("soft");
        mark("checking composited video under the break filter");
        assertions += ChildgramBreakSurfaceCheck.run(this, activity, dim);
        await("soft reminder returns to the fixture after video", () -> hostIs(dialog.getWindow().getDecorView()));
        Rect sheet = main(() -> bounds(target));
        int x = sheet.centerX(), y = sheet.bottom - sheet.height() / 8;
        tap(x, y);
        await("soft overlay lets outside taps reach the dialog", () -> touches.get() == 1);
        Rect before = main(() -> bounds(overlay()));
        int distance = Math.min(160, sheet.height() / 4);
        gesture(before.centerX(), before.centerY(), before.centerX(), before.centerY() + distance);
        await("drag moves the bubble without starting rest", () -> button("ChildgramBreakPrompt") != null
                && bounds(overlay()).centerY() > before.centerY() + distance / 2);
        tapButton("ChildgramBreakPrompt");
        await("tap starts an early break", () -> button("ChildgramBreakContinue") != null);
        String initialCountdown = main(() -> ((TextView) overlay().getChildAt(1)).getText().toString());
        await("rest timer counts down while Childgram stays open", () -> button("ChildgramBreakContinue") != null
                && !initialCountdown.contentEquals(((TextView) overlay().getChildAt(1)).getText()));
        screenshot("rest");
        tap(x, y);
        expect("rest blocks blind taps on the dialog", touches.get() == 1);
        tapButton("ChildgramBreakContinue");
        await("return resumes the partial session", () -> button("ChildgramBreakPrompt") != null);

        seed(2_988_000); // Three seconds before the natural cap.
        await("soft reminder before crossing cap", () -> button("ChildgramBreakPrompt") != null);
        int cancelled = cancels.get();
        long down = SystemClock.uptimeMillis();
        pointer(down, MotionEvent.ACTION_DOWN, x, y);
        try {
            await("reaching the limit cancels the pending tap and opens rest",
                    () -> button("ChildgramBreakContinue") != null && cancels.get() > cancelled);
        } finally {
            pointer(down, MotionEvent.ACTION_UP, x, y);
        }
        expect("releasing the earlier tap cannot click hidden content", touches.get() == 1);
        main(() -> {
            viewer = new View(activity);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.LAST_APPLICATION_WINDOW, 0, PixelFormat.OPAQUE);
            activity.getWindowManager().addView(viewer, params);
            return null;
        });
        await("full screen viewer window is covered", () -> hostIs(viewer));
        main(() -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) viewer.getLayoutParams();
            params.width = 180;
            params.height = 110;
            ((Activity) viewer.getContext()).getWindowManager().updateViewLayout(viewer, params);
            return null;
        });
        await("rest over floating video covers the underlying task", () -> hostIs(viewer) && viewer.getWidth() == 180
                && !bounds(viewer).contains(x, y) && bounds(overlay()).contains(sheet));
        tap(x, y);
        expect("floating video cannot expose hidden tap targets", touches.get() == 1);
        main(() -> { ((Activity) viewer.getContext()).getWindowManager().removeViewImmediate(viewer); return null; });
        await("closing viewer restores the rest controls", () -> button("ChildgramBreakContinue") != null
                && hostIs(dialog.getWindow().getDecorView()));
        main(() -> { dialog.dismiss(); return null; });
        await("overlay moves back onto the activity", () -> hostIs(((Activity) (Object) LaunchActivity.instance).getWindow().getDecorView()));
        checkIncomingCall();
        expect("Home accepted from rest", input.submit(() -> getUiAutomation().performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_HOME)).get(8, TimeUnit.SECONDS));
        await("Home removes the overlay", () -> !LaunchActivity.isResumed && overlay() == null);
        SystemClock.sleep(1500);
        bringToFront();
        await("return after a partial break restores the soft reminder", () -> button("ChildgramBreakPrompt") != null);
        tapButton("ChildgramBreakPrompt");
        await("outgoing call control available during rest", () -> button("ChildgramBreakCall") != null);
        // Navigation can outlast Android's input animation wait. Observe its actual destination.
        mark("tapping outgoing call control");
        Rect call = main(() -> bounds(button("ChildgramBreakCall")));
        long press = SystemClock.uptimeMillis();
        pointer(press, MotionEvent.ACTION_DOWN, call.centerX(), call.centerY());
        send(press, MotionEvent.ACTION_UP, call.centerX(), call.centerY());
        await("shortcut opens the undimmed call list", () -> LaunchActivity.isResumed && currentFragment() instanceof CallLogActivity && overlay() == null);
        expect("the shortcut did not place a call", !main(ChildgramBreakCalls::isCallActive));
    }

    private void checkIncomingCall() throws Exception {
        Activity activity = (Activity) (Object) LaunchActivity.instance;
        VoIPPreNotificationService.State fixture = new VoIPPreNotificationService.State(UserConfig.selectedAccount, 0, null);
        VoIPWindowView[] window = new VoIPWindowView[1];
        boolean wasCameraLocked = OrientationHelper.cameraRotationDisabled;
        AtomicInteger callTouches = new AtomicInteger();
        long frozenDebt;
        try {
            main(() -> {
                expect("incoming fixture starts without a call", VoIPPreNotificationService.currentState == null && !ChildgramBreakCalls.isCallActive());
                Field orientation = AndroidUtilities.class.getDeclaredField("prevOrientation");
                orientation.setAccessible(true);
                expect("incoming fixture starts without an existing orientation lock", orientation.getInt(null) == -10);
                window[0] = new VoIPWindowView(activity, false);
                window[0].setLockOnScreen(true);
                View control = new View(activity);
                control.setOnClickListener(v -> callTouches.incrementAndGet());
                window[0].addView(control, -1, -1);
                VoIPPreNotificationService.currentState = fixture;
                activity.getWindowManager().addView(window[0], window[0].createWindowLayoutParams());
                ChildgramUsageTracker.refreshVisibleScreen();
                return null;
            });
            await("incoming call window stays visible above the full break", () -> overlay() == null && window[0].hasWindowFocus()
                    && ChildgramBreakCalls.isCallActive() && clock.getBoolean("calling", false));
            frozenDebt = clock.getLong("debt", 0);
            expect("call begins with an outstanding break", frozenDebt > 0);
            Rect area = main(() -> bounds(window[0]));
            tap(area.centerX(), area.centerY());
            await("incoming fixture control receives real touch", () -> callTouches.get() == 1);
            SystemClock.sleep(1500);
        } finally {
            main(() -> {
                if (VoIPPreNotificationService.currentState == fixture) VoIPPreNotificationService.currentState = null;
                if (window[0] != null) {
                    if (window[0].isAttachedToWindow()) activity.getWindowManager().removeViewImmediate(window[0]);
                    AndroidUtilities.unlockOrientation(activity);
                    OrientationHelper.cameraRotationDisabled = wasCameraLocked;
                }
                ChildgramUsageTracker.refreshVisibleScreen();
                return null;
            });
        }
        await("original break returns after incoming call", () -> !clock.getBoolean("calling", true) && button("ChildgramBreakContinue") != null);
        expect("incoming call neither counts as use nor pays down the break", clock.getLong("debt", 0) == frozenDebt);
    }

    private void seed(long debt) throws Exception {
        main(() -> { ChildgramParentalSettings.setBreakReminderEnabled(false); return null; });
        await("disabling clears the clock and overlay", () -> !clock.contains("debt") && overlay() == null);
        main(() -> {
            clock.edit().putLong("debt", debt).putLong("elapsed", SystemClock.elapsedRealtime())
                    .putLong("wall", System.currentTimeMillis()).putInt("boot", Settings.Global.getInt(
                            getTargetContext().getContentResolver(), "boot_count", -1)).apply();
            ChildgramParentalSettings.setBreakReminderEnabled(true);
            return null;
        });
    }

    private void bringToFront() {
        getTargetContext().startActivity(new Intent(getTargetContext(), LaunchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // The standalone test compiler uses the app classes jar without AndroidX FragmentActivity.
    private static Object currentFragment() throws Exception {
        return LaunchActivity.class.getDeclaredMethod("getSafeLastFragment").invoke(null);
    }

    private void screenshot(String name) throws Exception {
        // Crop to the opaque test surface. Activity contents, system bars and notifications stay out.
        Rect area = main(() -> bounds(target));
        Bitmap screen = getUiAutomation().takeScreenshot();
        expect("screenshot available", screen != null && area.intersect(0, 0, screen.getWidth(), screen.getHeight()));
        Bitmap crop = Bitmap.createBitmap(screen, area.left, area.top, area.width(), area.height());
        File file = new File(getTargetContext().getCacheDir(), "childgram-break-" + name + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            expect("fixture screenshot saved", crop.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            crop.recycle();
            if (crop != screen) screen.recycle();
        }
    }

    private static ChildgramBreakOverlay overlay() {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            if (root instanceof ChildgramBreakOverlay && root.isShown()) return (ChildgramBreakOverlay) root;
        }
        return null;
    }

    private boolean hostIs(View root) {
        ChildgramBreakOverlay layer = overlay();
        return layer != null && ((WindowManager.LayoutParams) layer.getLayoutParams()).token == root.getWindowToken();
    }

    private View button(String key) {
        ChildgramBreakOverlay layer = overlay();
        if (layer == null) return null;
        String label = LocaleController.getString(getTargetContext().getResources().getIdentifier(key, "string", getTargetContext().getPackageName()));
        for (int i = 0; i < layer.getChildCount(); i++) {
            View child = layer.getChildAt(i);
            if (child instanceof TextView && label.contentEquals(((TextView) child).getText()) && child.getWidth() > 0) return child;
        }
        return null;
    }

    private static Rect bounds(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    private void tapButton(String key) throws Exception {
        Rect area = main(() -> bounds(button(key)));
        tap(area.centerX(), area.centerY());
    }

    private void tap(int x, int y) throws Exception { gesture(x, y, x, y); }

    private void gesture(int x, int y, int endX, int endY) throws Exception {
        long down = SystemClock.uptimeMillis();
        pointer(down, MotionEvent.ACTION_DOWN, x, y);
        if (x != endX || y != endY) {
            pointer(down, MotionEvent.ACTION_MOVE, (x + endX) / 2, (y + endY) / 2);
            SystemClock.sleep(40);
            pointer(down, MotionEvent.ACTION_MOVE, endX, endY);
        }
        pointer(down, MotionEvent.ACTION_UP, endX, endY);
        main(() -> null);
    }

    private void pointer(long down, int action, int x, int y) throws Exception {
        send(down, action, x, y).get(8, TimeUnit.SECONDS);
    }

    private Future<?> send(long down, int action, int x, int y) {
        return input.submit(() -> {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { sendPointerSync(event); } finally { event.recycle(); }
        });
    }

    private <T> T main(Callable<T> query) throws Exception {
        FutureTask<T> task = new FutureTask<>(query);
        new Handler(Looper.getMainLooper()).post(task);
        try { return task.get(8, TimeUnit.SECONDS); }
        finally { task.cancel(false); }
    }

    private void mark(String label) {
        stage = label;
        Log.i("ChildgramBreakCheck", label);
        Bundle status = new Bundle();
        status.putString("break_check", label);
        sendStatus(0, status);
    }

    private void await(String label, Callable<Boolean> condition) throws Exception {
        await(label, 20_000, condition);
    }

    private void await(String label, long timeoutMillis, Callable<Boolean> condition) throws Exception {
        mark(label);
        long limit = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < limit) {
            if (main(condition)) { assertions++; return; }
            SystemClock.sleep(timeoutMillis > 20_000 ? 400 : 100);
        }
        throw new AssertionError(label);
    }

    private void expect(String label, boolean success) {
        if (!success) throw new AssertionError(label);
        assertions++;
    }
}
