package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import org.telegram.tgnet.TLRPC;

/** Checks the installed, optimized release without an account or server mutations. */
public final class ChildgramReleaseInstrumentation extends Instrumentation {
    private int assertions;
    private String failure;
    private boolean preview;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        preview = arguments != null && arguments.getBoolean("preview", false);
        if (arguments != null && "true".equals(arguments.getString("preview"))) preview = true;
        start();
    }

    @Override public void onStart() {
        if (preview) {
            Bundle report = new Bundle();
            try {
                ChildgramUpdateInstrumentation.preview(this);
                report.putString("status", "passed");
                finish(Activity.RESULT_OK, report);
            } catch (Throwable error) {
                report.putString("failure", error.toString());
                finish(Activity.RESULT_CANCELED, report);
            }
            return;
        }
        runOnMainSync(() -> {
            try {
                ApplicationLoader.postInitApplication();
                expect("release package", "org.childgram".equals(getTargetContext().getPackageName()));
                expect("release is not debuggable", (getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0);
                // Reflection reads the APK field; javac would inline a compile-time constant.
                expect("release keeps Childgram enabled", BuildVars.class.getField("CHILDGRAM").getBoolean(null));
                expect("release disables debug mode", !BuildVars.DEBUG_VERSION);
                expect("release isolates account type", "org.childgram".equals(BuildVars.class.getField("ACCOUNT_TYPE").get(null)));
                expect("release never uses the official updater", !BuildVars.CHECK_UPDATES);
                int account = UserConfig.selectedAccount;
                MessagesController controller = MessagesController.getInstance(account);
                ChildgramAccess policy = ChildgramAccess.getInstance(account);
                long fixture = 8_100_000_000_000L + (System.nanoTime() & 0xfffffff);
                TLRPC.TL_user human = new TLRPC.TL_user();
                human.id = fixture;
                human.first_name = "Release check";
                controller.putUser(human, true);
                expect("ordinary person allowed", policy.isAllowed(human.id));
                TLRPC.TL_user bot = new TLRPC.TL_user();
                bot.id = fixture + 1;
                bot.bot = true;
                controller.putUser(bot, true);
                expect("unknown bot denied", !policy.isAllowed(bot.id));
                TLRPC.TL_channel channel = new TLRPC.TL_channel();
                channel.id = fixture + 2;
                channel.broadcast = true;
                controller.putChat(channel, true);
                expect("member channel allowed", policy.isAllowed(-channel.id));
                channel.left = true;
                expect("unjoined channel denied", !policy.isAllowed(-channel.id));
                expect("missing peer denied", !policy.isAllowed(fixture + 3));
                expect("browser preference cannot enable embedded browser", !controller.isWebBrowserInAppEnabled());
            } catch (Throwable error) {
                failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName();
            }
        });
        if (failure == null) {
            try {
                assertions += ChildgramUsageStorageInstrumentation.run().assertions;
                assertions += ChildgramUpdateInstrumentation.run(getTargetContext());
            }
            catch (Throwable error) { failure = error.toString(); }
        }
        Bundle report = new Bundle();
        report.putString("status", failure == null ? "passed" : "failed");
        report.putInt("assertions", assertions);
        if (failure != null) report.putString("failure", failure);
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }

    private void expect(String label, boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
