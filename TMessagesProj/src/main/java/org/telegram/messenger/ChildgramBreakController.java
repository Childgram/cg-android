package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.provider.Settings;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import org.telegram.ui.Components.ChildgramBreakOverlay;
import org.telegram.ui.PhotoViewer;
import java.lang.ref.WeakReference;

/** UI-thread owner of the single break balance, shared by every account and content window. */
public final class ChildgramBreakController {
    private enum Mode { USE, AWAY, FROZEN }

    private static ChildgramBreakTimer timer;
    private static String profile;
    private static Mode mode = Mode.AWAY;
    private static long lastUpdate, lastSave;
    private static int boot;
    private static boolean leftApp, disabledCleaned;
    private static WeakReference<TextureView> pipTarget;
    private static int pipLight = 255;

    private ChildgramBreakController() {}

    public static void rememberPipTarget(TextureView target) {
        if (!BuildVars.CHILDGRAM || target == null) return;
        pipTarget = new WeakReference<>(target);
        pipLight = 255;
        ChildgramUsageTracker.refreshVisibleScreen();
    }

    static void setPipDim(float dim, boolean appWindowDimmed) {
        TextureView target = pipTarget == null ? null : pipTarget.get();
        if (target == null) return;
        ViewGroup.LayoutParams root = target.getRootView().getLayoutParams();
        Activity owner = AndroidUtilities.findActivity(target.getContext());
        // The in-app player is under its window's dim. For overlay/system PiP, affect only the video.
        if (appWindowDimmed && owner != null && !AndroidUtilities.isInPictureInPictureMode(owner)
                && root instanceof WindowManager.LayoutParams
                && ((WindowManager.LayoutParams) root).type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) dim = 0;
        int light = Math.round((1f - dim) * 255);
        if (light == pipLight) return;
        pipLight = light;
        Paint paint = null;
        if (light != 255) {
            paint = new Paint();
            paint.setColorFilter(new LightingColorFilter(Color.rgb(light, light, light), 0));
        }
        target.setLayerPaint(paint);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("childgram_break_time", Context.MODE_PRIVATE);
    }

    static void onBackground() {
        if (timer != null && timer.resting) leftApp = true;
    }

    static void setResting(boolean resting) {
        if (timer == null) return;
        if (resting) timer.resting = true;
        else timer.resume();
    }

    static void clear() {
        if (prefs().contains("debt")) prefs().edit().clear().apply();
        timer = null;
        leftApp = false;
        ChildgramBreakOverlay.hide();
        setPipDim(0, false);
    }

    static void update(long elapsed, long wall, boolean visibleContent, boolean playingPip, boolean exemptScreen) {
        if (!ChildgramParentalSettings.isBreakReminderEnabled()) {
            if (!disabledCleaned) {
                clear();
                disabledCleaned = true;
            }
            return;
        }
        disabledCleaned = false;
        boolean calling = ChildgramBreakCalls.isCallActive();
        Mode next = calling || exemptScreen ? Mode.FROZEN : visibleContent || playingPip ? Mode.USE : Mode.AWAY;
        if (timer == null) {
            ChildgramBreakCalls.observe();
            SharedPreferences saved = prefs();
            boot = Settings.Global.getInt(ApplicationLoader.applicationContext.getContentResolver(), "boot_count", -1);
            int start = ChildgramParentalSettings.getBreakStartMinutes();
            int fade = ChildgramParentalSettings.getBreakFadeMinutes();
            int rest = ChildgramParentalSettings.getBreakRestMinutes();
            profile = start + "/" + fade + "/" + rest;
            // Older checkpoints always used 10/10/5, with ten units per millisecond of rest.
            long debt = profile.equals(saved.getString("profile", "10/10/5")) ? saved.getLong("debt", 0) : 0;
            timer = new ChildgramBreakTimer(start, fade, rest, debt);
            if (timer.debt > 0 && !(calling && saved.getBoolean("calling", false))) {
                // When the process was killed there was no usable client after the last checkpoint.
                timer.recover(ChildgramBreakTimer.timeAway(saved.getLong("elapsed", elapsed), saved.getLong("wall", wall),
                        saved.getInt("boot", -1), elapsed, wall, boot));
                timer.resume();
            }
            lastUpdate = lastSave = elapsed;
            mode = Mode.AWAY;
        }
        boolean wasResting = timer.resting;
        if (mode == Mode.USE) timer.use(elapsed - lastUpdate);
        else if (mode == Mode.AWAY) timer.recover(elapsed - lastUpdate);
        lastUpdate = elapsed;
        if (timer.resting && !wasResting && !visibleContent) leftApp = true;
        if (leftApp && visibleContent && !exemptScreen && !calling) {
            timer.resume();
            leftApp = false;
        }
        if (timer.resting && !visibleContent && timer.debt == 0) timer.resume();
        if (timer.resting && !calling && !exemptScreen) PhotoViewer.pauseChildgramBreakVideo();
        if (next != mode || timer.resting != wasResting || elapsed - lastSave >= 10_000) {
            SharedPreferences saved = prefs();
            if (timer.debt > 0) {
                saved.edit().putString("profile", profile).putLong("debt", timer.debt).putLong("elapsed", elapsed).putLong("wall", wall)
                        .putInt("boot", boot).putBoolean("calling", calling).apply();
            } else if (saved.contains("debt")) {
                saved.edit().clear().apply();
            }
            lastSave = elapsed;
        }
        mode = next;
        float dim = timer.resting ? 1f : timer.alpha();
        boolean showOverlay = visibleContent && !exemptScreen && (timer.resting || timer.showHint());
        setPipDim(dim, showOverlay);
        if (showOverlay) {
            ChildgramBreakOverlay.update(dim, timer.remainingMillis(), timer.resting);
        } else {
            ChildgramBreakOverlay.hide();
        }
    }
}
