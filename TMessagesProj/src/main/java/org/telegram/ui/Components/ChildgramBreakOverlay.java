package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChildgramBreakCalls;
import org.telegram.messenger.ChildgramUsageTracker;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.GroupCallActivity;
import org.telegram.ui.Components.voip.VoIPWindowView;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

/** An app subwindow. Android dims everything behind the small, otherwise non-modal bubble. */
public final class ChildgramBreakOverlay extends LinearLayout {
    private static ChildgramBreakOverlay current;
    private final Activity activity;
    private final View parentWindow;
    private final WindowManager manager;
    private final WindowManager.LayoutParams params;
    private final boolean full, resting;
    private final Map<View, Integer> hiddenFromAccessibility = new WeakHashMap<>();
    private final TextView prompt, time;
    private final Rect visibleFrame = new Rect();
    private long shownSeconds = -1, lastMove;
    private float x = dp(16), y = dp(72), vx = dp(12), vy = dp(8);
    private float downX, downY, startX, startY;
    private boolean holding, dragged;

    /** Called on the UI thread by the shared usage clock. */
    public static void update(float alpha, long remainingMillis, boolean resting) {
        Activity owner = ChildgramUsageTracker.getBreakActivity();
        if (owner == null || owner.isFinishing() || owner.isDestroyed() || GroupCallActivity.groupCallUiVisible) {
            hide();
            return;
        }
        View decor = owner.getWindow().getDecorView();
        List<View> roots = AndroidUtilities.allGlobalViews();
        if (roots == null || roots.isEmpty()) roots = Collections.singletonList(decor);
        View parent = null;
        boolean popupAbove = false, foundCurrent = false;
        for (View view : roots) {
            if (view == current) {
                foundCurrent = true;
                continue;
            }
            if (!view.isShown() || view.getWindowVisibility() != VISIBLE || view.getWindowToken() == null
                    || !(view.getLayoutParams() instanceof WindowManager.LayoutParams)) continue;
            WindowManager.LayoutParams attributes = (WindowManager.LayoutParams) view.getLayoutParams();
            int type = attributes.type;
            if (type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW && type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) {
                if (!belongsTo(view, owner)) continue;
                if (view instanceof VoIPWindowView) {
                    hide();
                    return;
                }
                parent = view;
            } else if (foundCurrent && type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
                    && current != null && view.getApplicationWindowToken() == current.parentWindow.getWindowToken()) {
                popupAbove = true;
            }
        }
        if (parent == null) {
            hide();
            return;
        }
        boolean full = resting || alpha >= 1f;
        if (current == null || current.parentWindow != parent || current.getParent() == null || popupAbove
                || current.full != full || current.resting != resting) {
            hide();
            ChildgramBreakOverlay next = new ChildgramBreakOverlay(owner, parent, full, resting);
            next.setTime(remainingMillis);
            next.params.dimAmount = alpha;
            try {
                if (full) AndroidUtilities.hideKeyboard(parent.findFocus() == null ? parent : parent.findFocus());
                next.manager.addView(next, next.params);
                current = next;
                if (full) next.prompt.requestFocus();
            } catch (WindowManager.BadTokenException | IllegalArgumentException e) {
                // The host window may already be leaving the screen. The next tick selects its successor.
                FileLog.e(e);
            }
        } else {
            current.setTime(remainingMillis);
            if (current.params.dimAmount != alpha) {
                current.params.dimAmount = alpha;
                current.applyPosition();
            }
        }
        if (current != null && full) {
            for (View root : roots) {
                if (root instanceof ChildgramBreakOverlay || !root.isAttachedToWindow() || !(belongsTo(root, owner)
                        || root.getApplicationWindowToken() == parent.getWindowToken())) continue;
                if (!current.hiddenFromAccessibility.containsKey(root)) {
                    current.hiddenFromAccessibility.put(root, root.getImportantForAccessibility());
                    root.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    // Android can deliver the UP for a finger already down to its original window.
                    long now = SystemClock.uptimeMillis();
                    MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                    cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    try {
                        root.dispatchTouchEvent(cancel);
                        root.cancelPendingInputEvents();
                    } finally {
                        cancel.recycle();
                    }
                    if (current == null || !current.full) break;
                }
            }
        }
    }

    private static boolean belongsTo(View root, Activity owner) {
        View decor = owner.getWindow().getDecorView();
        if (root == decor || AndroidUtilities.findActivity(root.getContext()) == owner) return true;
        if (!(root.getLayoutParams() instanceof WindowManager.LayoutParams)) return false;
        // DecorContext can unwrap to a display context instead of the Activity. Sibling windows
        // share the activity token; an attached panel reports its parent's window token.
        WindowManager.LayoutParams layout = (WindowManager.LayoutParams) root.getLayoutParams();
        if (layout.token == null) return false;
        return layout.token == decor.getWindowToken() || layout.token == owner.getWindow().getAttributes().token
                || decor.getLayoutParams() instanceof WindowManager.LayoutParams
                && layout.token == ((WindowManager.LayoutParams) decor.getLayoutParams()).token;
    }

    public static void hide() {
        ChildgramBreakOverlay previous = current;
        if (previous == null) return;
        current = null;
        for (Map.Entry<View, Integer> entry : previous.hiddenFromAccessibility.entrySet()) {
            if (entry.getKey().getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                entry.getKey().setImportantForAccessibility(entry.getValue());
            }
        }
        previous.hiddenFromAccessibility.clear();
        if (previous.getParent() != null) {
            try {
                previous.manager.removeViewImmediate(previous);
            } catch (IllegalArgumentException e) {
                FileLog.e(e);
            }
        }
    }

    private ChildgramBreakOverlay(Activity owner, View parentWindow, boolean full, boolean resting) {
        super(owner);
        activity = owner;
        this.parentWindow = parentWindow;
        this.full = full;
        this.resting = resting;
        manager = owner.getWindowManager();
        int size = full ? MATCH_PARENT : WRAP_CONTENT;
        // Use task bounds even when attached to a floating video or narrow dialog.
        int flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!full) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        params = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL, flags, PixelFormat.TRANSLUCENT);
        params.token = parentWindow.getWindowToken();
        params.setTitle("Childgram break");
        params.gravity = full ? Gravity.CENTER : Gravity.TOP | Gravity.LEFT;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(16), dp(10), dp(16), dp(10));
        if (full) {
            setBackgroundColor(Color.BLACK);
            setClickable(true);
        } else {
            params.x = (int) x;
            params.y = (int) y;
            setBackground(Theme.createRoundRectDrawable(dp(22), 0xff212121));
            setOnClickListener(v -> ChildgramUsageTracker.startBreak());
            setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        prompt = label(resting ? R.string.ChildgramBreakResting : R.string.ChildgramBreakPrompt, full ? 20 : 15);
        time = label(0, 14);
        if (full) {
            prompt.setFocusableInTouchMode(true);
            addButton(resting ? R.string.ChildgramBreakContinue : R.string.ChildgramBreakStart,
                    v -> { if (resting) ChildgramUsageTracker.endBreak(); else ChildgramUsageTracker.startBreak(); });
            addButton(R.string.ChildgramBreakCall, v -> ChildgramBreakCalls.showDialer(activity));
        } else {
            prompt.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            time.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private TextView label(int string, int sp) {
        TextView view = new TextView(activity);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        if (string != 0) view.setText(getString(string));
        addView(view);
        return view;
    }

    private void addButton(int string, OnClickListener click) {
        Button button = new Button(activity);
        button.setText(getString(string));
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12), 0xff303030, 0xff545454));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(48));
        button.setOnClickListener(click);
        LayoutParams lp = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        lp.topMargin = dp(8);
        addView(button, lp);
    }

    private void setTime(long remainingMillis) {
        long seconds = (Math.max(0L, remainingMillis) + 999) / 1000;
        if (seconds == shownSeconds) return;
        shownSeconds = seconds;
        String text = resting && seconds == 0 ? getString(R.string.ChildgramBreakReady)
                : LocaleController.formatString(resting ? R.string.ChildgramBreakTimeLeft : R.string.ChildgramBreakNeeded,
                        DateUtils.formatElapsedTime(seconds));
        time.setText(text);
        if (!full) setContentDescription(getString(R.string.ChildgramBreakPrompt) + ". " + text + ". " + getString(R.string.ChildgramBreakStart));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!full) post(move);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(move);
        super.onDetachedFromWindow();
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // A newly opened dialog can overtake our app window between two timer ticks.
        if (!hasWindowFocus && current == this && full) ChildgramUsageTracker.refreshVisibleScreen();
    }

    private final Runnable move = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.15f, (now - lastMove) / 1000f);
            lastMove = now;
            AccessibilityManager accessibility = (AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
            boolean moveEnabled = (accessibility == null || !accessibility.isTouchExplorationEnabled())
                    && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
            if (!holding) {
                if (moveEnabled) moveTo(x + vx * dt, y + vy * dt, true);
                else moveTo(x, y, false);
            }
            postDelayed(this, moveEnabled ? 80 : 1000);
        }
    };

    private void moveTo(float newX, float newY, boolean bounce) {
        if (getWidth() == 0 || getHeight() == 0) return;
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
        int right = Math.max(0, visibleFrame.width() - getWidth());
        int bottom = Math.max(0, visibleFrame.height() - getHeight());
        if (bounce && (newX <= 0 || newX >= right)) vx = newX <= 0 ? Math.abs(vx) : -Math.abs(vx);
        if (bounce && (newY <= 0 || newY >= bottom)) vy = newY <= 0 ? Math.abs(vy) : -Math.abs(vy);
        x = Math.max(0, Math.min(newX, right));
        y = Math.max(0, Math.min(newY, bottom));
        if (params.x == Math.round(x) && params.y == Math.round(y)) return;
        params.x = Math.round(x);
        params.y = Math.round(y);
        applyPosition();
    }

    private void applyPosition() {
        if (getParent() == null) return;
        try {
            manager.updateViewLayout(this, params);
        } catch (IllegalArgumentException e) {
            FileLog.e(e);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (full) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                holding = true;
                dragged = false;
                downX = event.getRawX();
                downY = event.getRawY();
                startX = x;
                startY = y;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (!dragged && Math.hypot(dx, dy) > ViewConfiguration.get(activity).getScaledTouchSlop()) dragged = true;
                if (dragged) moveTo(startX + dx, startY + dy, false);
                break;
            case MotionEvent.ACTION_UP:
                holding = false;
                if (!dragged) performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                holding = false;
                break;
        }
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (full && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) activity.moveTaskToBack(true);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
