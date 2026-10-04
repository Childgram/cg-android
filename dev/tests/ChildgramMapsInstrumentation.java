package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LocationActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opens the real location viewer with an in-memory point; sends no messages. */
public final class ChildgramMapsInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            String pkg = getTargetContext().getPackageName();
            Bundle metadata = getTargetContext().getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData;
            String key = metadata == null ? null : metadata.getString("com.google.android.maps.v2.API_KEY");
            if (key == null || !key.matches("AIza[0-9A-Za-z_-]{35}")) {
                throw new AssertionError("Maps API key missing from installed APK");
            }
            runOnMainSync(ApplicationLoader::postInitApplication);
            ActivityMonitor monitor = addMonitor("org.telegram.ui.LaunchActivity", null, false);
            getTargetContext().startActivity(new Intent().setClassName(pkg, "org.telegram.ui.LaunchActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity activity = monitor.waitForActivityWithTimeout(20000);
            removeMonitor(monitor);
            if (activity == null) throw new AssertionError("LaunchActivity did not resume");
            SystemClock.sleep(2000);
            LocationActivity[] viewer = new LocationActivity[1];
            runOnMainSync(() -> {
                TLRPC.TL_message message = new TLRPC.TL_message();
                message.id = 1;
                message.date = (int) (System.currentTimeMillis() / 1000);
                message.peer_id = new TLRPC.TL_peerUser();
                message.peer_id.user_id = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
                message.from_id = message.peer_id;
                message.message = "";
                message.media = new TLRPC.TL_messageMediaGeo();
                message.media.geo = new TLRPC.TL_geoPoint();
                // Public landmark, never the account's real location.
                message.media.geo.lat = 48.85837;
                message.media.geo._long = 2.294481;
                viewer[0] = new LocationActivity(3);
                viewer[0].setMessageObject(new MessageObject(UserConfig.selectedAccount, message, false, false));
                try {
                    activity.getClass().getMethod("presentFragment", BaseFragment.class).invoke(activity, viewer[0]);
                } catch (ReflectiveOperationException e) { throw new AssertionError("Cannot open location viewer"); }
            });
            Field mapField = LocationActivity.class.getDeclaredField("map");
            mapField.setAccessible(true);
            IMapsProvider.IMap[] map = new IMapsProvider.IMap[1];
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (map[0] == null && SystemClock.elapsedRealtime() < deadline) {
                runOnMainSync(() -> {
                    try { map[0] = (IMapsProvider.IMap) mapField.get(viewer[0]); }
                    catch (IllegalAccessException e) { throw new AssertionError(e); }
                });
                SystemClock.sleep(200);
            }
            if (map[0] == null) throw new AssertionError("Location viewer map did not initialize");
            CountDownLatch loaded = new CountDownLatch(1);
            runOnMainSync(() -> map[0].setOnMapLoadedCallback(loaded::countDown));
            deadline = SystemClock.elapsedRealtime() + 60000;
            while (!loaded.await(1, TimeUnit.SECONDS) && SystemClock.elapsedRealtime() < deadline) {
                // The dedicated AVD uses English. Viewing a supplied point must
                // work without granting access to the device's own location.
                android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                if (root != null && root.getPackageName() != null
                        && root.getPackageName().toString().endsWith(".permissioncontroller")) {
                    for (android.view.accessibility.AccessibilityNodeInfo button : root.findAccessibilityNodeInfosByText("Don’t allow")) {
                        button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
            if (loaded.getCount() != 0) throw new AssertionError("Map tiles did not finish loading");
            File image = new File(getTargetContext().getExternalFilesDir("maps-checks"), "location-viewer.png");
            try (FileOutputStream output = new FileOutputStream(image)) {
                getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
            report.putString("status", "passed");
            report.putBoolean("map_loaded", true);
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            report.putString("status", "failed");
            report.putString("failure", error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, report);
        }
    }
}
