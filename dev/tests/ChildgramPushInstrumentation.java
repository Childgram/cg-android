package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;

/** Checks the real token/Telegram registration without exposing tokens or account IDs. */
public final class ChildgramPushInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            String pkg = getTargetContext().getPackageName();
            report.putString("package", pkg);
            int projectResource = getTargetContext().getResources().getIdentifier("project_id", "string", pkg);
            if (projectResource == 0 || !"child-gram".equals(getTargetContext().getString(projectResource))) {
                throw new AssertionError("Firebase project is not child-gram");
            }
            report.putString("firebase_project", "child-gram");
            Bundle metadata = getTargetContext().getPackageManager().getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData;
            if (metadata == null || !metadata.getBoolean("firebase_messaging_auto_init_enabled")) {
                throw new AssertionError("FCM auto-init is disabled");
            }
            runOnMainSync(ApplicationLoader::postInitApplication);
            if (!ApplicationLoader.getPushProvider().hasServices()) {
                throw new AssertionError("Google Play Services unavailable or Childgram FCM disabled");
            }
            long requestStarted = SystemClock.elapsedRealtime();
            runOnMainSync(() -> {
                // Force the normal registration path; persisted success is not evidence
                // that this build can obtain a token and register it with Telegram.
                for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                    UserConfig.getInstance(i).registeredForPush = false;
                }
                ApplicationLoader.getPushProvider().onRequestPushToken();
            });
            long deadline = SystemClock.elapsedRealtime() + 60000;
            do {
                SystemClock.sleep(1000);
                runOnMainSync(() -> {
                    int active = 0, registered = 0;
                    for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                        UserConfig config = UserConfig.getInstance(i);
                        if (config.isClientActivated()) {
                            active++;
                            if (config.registeredForPush) registered++;
                        }
                    }
                    report.putBoolean("fcm_token_present", !TextUtils.isEmpty(SharedConfig.pushString));
                    report.putBoolean("fresh_token_request_completed", SharedConfig.pushStringGetTimeStart >= requestStarted
                            && SharedConfig.pushStringGetTimeEnd >= SharedConfig.pushStringGetTimeStart
                            && !"__FIREBASE_FAILED__".equals(SharedConfig.pushStringStatus));
                    report.putInt("active_accounts", active);
                    report.putInt("registered_accounts", registered);
                });
                if (report.getBoolean("fresh_token_request_completed") && report.getBoolean("fcm_token_present")
                        && report.getInt("active_accounts") == report.getInt("registered_accounts")) {
                    report.putString("status", report.getInt("active_accounts") == 0 ? "client_ready_no_account" : "registered");
                    finish(Activity.RESULT_OK, report);
                    return;
                }
            } while (SystemClock.elapsedRealtime() < deadline);
            throw new AssertionError("Token or Telegram registration not ready; inspect redacted Push registration failed log");
        } catch (Throwable error) {
            report.putString("status", "failed");
            report.putString("failure", error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, report);
        }
    }
}
