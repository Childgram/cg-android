package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Compositor pixels in a transparent window over the existing white fixture, all in memory. */
final class ChildgramBreakSurfaceCheck {
    static int run(Instrumentation test, Activity owner, float dim) throws Exception {
        CountDownLatch framePosted = new CountDownLatch(1);
        FrameLayout window = main(test, () -> {
            FrameLayout root = new FrameLayout(owner);
            SurfaceView video = new SurfaceView(owner);
            video.getHolder().addCallback(new SurfaceHolder.Callback() {
                public void surfaceCreated(SurfaceHolder holder) {}
                public void surfaceDestroyed(SurfaceHolder holder) {}
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    Canvas frame = holder.lockCanvas();
                    if (frame == null) return;
                    try {
                        frame.drawColor(Color.RED);
                    } finally {
                        holder.unlockCanvasAndPost(frame);
                    }
                    framePosted.countDown();
                }
            });
            root.addView(video, new FrameLayout.LayoutParams(84, 84, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
            View stripe = new View(owner);
            stripe.setBackgroundColor(Color.GREEN);
            root.addView(stripe, new FrameLayout.LayoutParams(-1, 12, Gravity.CENTER_VERTICAL));
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(180, 96,
                    WindowManager.LayoutParams.LAST_APPLICATION_WINDOW,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.RIGHT | Gravity.BOTTOM;
            p.x = 32;
            p.y = 120;
            owner.getWindowManager().addView(root, p);
            ChildgramUsageTracker.refreshVisibleScreen();
            return root;
        });
        try {
            if (!framePosted.await(8, TimeUnit.SECONDS)) throw new AssertionError("Surface fixture did not present a frame");
            int[] origin = main(test, () -> {
                int[] xy = new int[2];
                window.getLocationOnScreen(xy);
                return xy;
            });
            int light = Math.round(255 * (1 - dim));
            int gap = 0, surface = 0, label = 0;
            long deadline = SystemClock.elapsedRealtime() + 6_000;
            do {
                Bitmap frame = test.getUiAutomation().takeScreenshot();
                if (frame != null) {
                    try {
                        gap = frame.getPixel(origin[0] + 20, origin[1] + 68);
                        surface = frame.getPixel(origin[0] + 146, origin[1] + 68);
                        label = frame.getPixel(origin[0] + 146, origin[1] + 48);
                    } finally {
                        frame.recycle();
                    }
                    if (near(gap, light, light, light) && near(surface, light, 0, 0) && near(label, 0, light, 0)) return 3;
                }
                SystemClock.sleep(120);
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("Each fixture pixel should dim once; expected channel " + light
                    + ", gap/video/stripe were " + Integer.toHexString(gap) + "/" + Integer.toHexString(surface)
                    + "/" + Integer.toHexString(label));
        } finally {
            main(test, () -> {
                owner.getWindowManager().removeViewImmediate(window);
                ChildgramUsageTracker.refreshVisibleScreen();
                return null;
            });
        }
    }

    private static boolean near(int actual, int red, int green, int blue) {
        return Math.abs(Color.red(actual) - red) <= 12 && Math.abs(Color.green(actual) - green) <= 12
                && Math.abs(Color.blue(actual) - blue) <= 12;
    }

    private static <T> T main(Instrumentation test, Callable<T> task) throws Exception {
        FutureTask<T> result = new FutureTask<>(task);
        test.runOnMainSync(result);
        return result.get();
    }
}
