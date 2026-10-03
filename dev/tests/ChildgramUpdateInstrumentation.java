package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageInfo;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the installed, signed APK as a fixture; no public release or network needed. */
public final class ChildgramUpdateInstrumentation {
    private int assertions;
    private interface Checked { void run() throws Exception; }
    private void expect(String label, boolean value) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private void rejects(String label, Checked operation) throws Exception {
        try { operation.run(); } catch (Exception expected) { assertions++; return; }
        throw new AssertionError(label);
    }
    private static ChildgramUpdateController.Manifest parse(JSONObject json, int code) throws Exception {
        return ChildgramUpdateController.Manifest.parse(json, code, "org.childgram");
    }
    private static JSONObject changed(JSONObject original, String key, Object value) throws Exception {
        return new JSONObject(original.toString()).put(key, value);
    }

    public static int run(Context context) throws Exception {
        ChildgramUpdateInstrumentation test = new ChildgramUpdateInstrumentation();
        test.check(context);
        return test.assertions;
    }

    private static JSONObject fixture(Context context) throws Exception {
        return fixture(context, new File(context.getApplicationInfo().sourceDir));
    }

    private static JSONObject fixture(Context context, File apk) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(apk)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return new JSONObject().put("schema_version", 1).put("available", true)
                .put("package", "org.childgram").put("version", info.versionName).put("version_code", info.versionCode)
                .put("sha256", hex.toString()).put("size", apk.length()).put("changelog", "Local update fixture")
                .put("file_url", "https://github.com/Childgram/cg-android/releases/download/v" + info.versionName
                        + "/childgram-" + info.versionName + "-arm64.apk");
    }

    private void check(Context context) throws Exception {
        expect("Childgram release enables custom updates", ApplicationLoader.applicationLoaderInstance.isCustomUpdate());
        expect("permanent feed", "https://update.childgram.org/android.json".equals(ChildgramUpdateController.FEED_URL));
        File apk = new File(context.getApplicationInfo().sourceDir);
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        JSONObject json = fixture(context);
        ChildgramUpdateController.Manifest release = parse(json, 0);
        expect("new version available", release != null);
        expect("same version ignored", parse(json, info.versionCode) == null);
        expect("downgrade ignored", parse(json, info.versionCode + 1) == null);
        expect("empty feed accepted", parse(new JSONObject("{\"schema_version\":1,\"available\":false}"), 0) == null);
        expect("version code orders prereleases numerically", new BetaUpdate("0.1.0-alpha.10", 10, "")
                .higherThan(new BetaUpdate("0.1.0-alpha.9", 9, "")));
        rejects("wrong schema", () -> parse(changed(json, "schema_version", 2), 0));
        rejects("wrong package", () -> parse(changed(json, "package", "org.telegram.messenger"), 0));
        rejects("fractional version code", () -> parse(changed(json, "version_code", 1.5), 0));
        rejects("overflow version code", () -> parse(changed(json, "version_code", 2147483648L), 0));
        rejects("invalid hash", () -> parse(changed(json, "sha256", "wrong"), 0));
        rejects("oversized APK", () -> parse(changed(json, "size", 251L * 1024 * 1024), 0));
        for (String url : new String[] { json.getString("file_url").replace("https:", "http:"),
                json.getString("file_url").replace("github.com", "github.com.evil.example"),
                json.getString("file_url").replace("Childgram/", "Other/"),
                json.getString("file_url") + "?redirect=1", json.getString("file_url") + "#fragment",
                json.getString("file_url").replace("github.com/", "user@github.com/") }) {
            rejects("unexpected download URL", () -> parse(changed(json, "file_url", url), 0));
        }
        ChildgramUpdateController.verifyApk(context, apk, release);
        assertions++;
        rejects("APK hash mismatch", () -> ChildgramUpdateController.verifyApk(context, apk,
                parse(changed(json, "sha256", "0000000000000000000000000000000000000000000000000000000000000000"), 0)));
        rejects("APK size mismatch", () -> ChildgramUpdateController.verifyApk(context, apk,
                parse(changed(json, "size", apk.length() - 1), 0)));
        rejects("APK version mismatch", () -> ChildgramUpdateController.verifyApk(context, apk,
                parse(changed(json, "version_code", info.versionCode + 1), 0)));
        File wrongSigner = new File(context.getExternalFilesDir("update-checks"), "wrong-signer.apk");
        expect("wrong-signer fixture exists", wrongSigner.isFile());
        ChildgramUpdateController.Manifest wrongSignerManifest = parse(fixture(context, wrongSigner), 0);
        rejects("foreign signing key rejected", () -> ChildgramUpdateController.verifyApk(context, wrongSigner, wrongSignerManifest));
        byte[] bytes = {1, 2, 3, 4};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChildgramUpdateController.copy(new ByteArrayInputStream(bytes), out, 4, new AtomicBoolean(), null);
        expect("download bytes preserved", java.util.Arrays.equals(bytes, out.toByteArray()));
        rejects("download limit enforced", () -> ChildgramUpdateController.copy(new ByteArrayInputStream(bytes),
                new ByteArrayOutputStream(), 3, new AtomicBoolean(), null));
        rejects("cancellation enforced", () -> ChildgramUpdateController.copy(new ByteArrayInputStream(bytes),
                new ByteArrayOutputStream(), 4, new AtomicBoolean(true), null));
        File directory = new File(context.getCacheDir(), "childgram-updates");
        directory.mkdirs();
        File copy = File.createTempFile("fixture-", ".apk", directory);
        try {
            try (FileInputStream input = new FileInputStream(apk); FileOutputStream output = new FileOutputStream(copy)) {
                ChildgramUpdateController.copy(input, output, apk.length(), new AtomicBoolean(), null);
            }
            ChildgramUpdateController.verifyApk(context, copy, release);
            assertions++;
            try (android.os.ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(
                    android.net.Uri.parse("content://org.childgram.provider/childgram_updates/" + copy.getName()), "r")) {
                expect("installer can read APK URI", descriptor != null && descriptor.getStatSize() == apk.length());
            }
        } finally { copy.delete(); }
    }

    /** Explicit local UI check. The fixture is never persisted as an update feed. */
    public static void preview(android.app.Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        File directory = new File(context.getCacheDir(), "childgram-updates");
        directory.mkdirs();
        File copy = new File(directory, "fixture-preview.apk");
        try (FileInputStream input = new FileInputStream(context.getApplicationInfo().sourceDir);
             FileOutputStream output = new FileOutputStream(copy)) {
            ChildgramUpdateController.copy(input, output, 250L * 1024 * 1024, new AtomicBoolean(), null);
        }
        ChildgramUpdateController.Manifest release = parse(fixture(context).put("changelog", "Проверка обновления на локальном APK."), 0);
        ChildgramUpdateController.verifyApk(context, copy, release);
        instrumentation.runOnMainSync(ApplicationLoader::postInitApplication);
        android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor("org.telegram.ui.LaunchActivity", null, false);
        context.startActivity(new android.content.Intent().setClassName("org.childgram", "org.telegram.ui.LaunchActivity")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        android.app.Activity activity = monitor.waitForActivityWithTimeout(20000);
        instrumentation.removeMonitor(monitor);
        if (activity == null) throw new AssertionError("LaunchActivity did not resume");
        Thread.sleep(2000);
        instrumentation.runOnMainSync(() -> {
            try {
                ChildgramUpdateController controller = ChildgramUpdateController.getInstance();
                for (String name : new String[] {"available", "downloaded", "lastCheck"}) {
                    java.lang.reflect.Field field = ChildgramUpdateController.class.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(controller, name.equals("available") ? release : name.equals("downloaded") ? copy : System.currentTimeMillis());
                }
                ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(activity, release.update, UserConfig.selectedAccount);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        Thread.sleep(2000);
        screenshot(instrumentation, "update-dialog.png");
        String label = LocaleController.getString("AppUpdateNow");
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> buttons = instrumentation.getUiAutomation()
                .getRootInActiveWindow().findAccessibilityNodeInfosByText(label);
        if (buttons.isEmpty()) throw new AssertionError("Update button is not visible");
        android.view.accessibility.AccessibilityNodeInfo button = buttons.get(0);
        while (!button.isClickable() && button.getParent() != null) button = button.getParent();
        if (!button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Cannot click update button");
        boolean installerReady = false;
        long deadline = System.currentTimeMillis() + 20000;
        android.view.accessibility.AccessibilityNodeInfo root = null;
        while (System.currentTimeMillis() < deadline) {
            root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && !"org.childgram".contentEquals(root.getPackageName())
                    && !root.findAccessibilityNodeInfosByText("Childgram").isEmpty()) {
                installerReady = true;
                break;
            }
            Thread.sleep(200);
        }
        if (!installerReady) throw new AssertionError("System installer did not recognize Childgram");
        screenshot(instrumentation, "update-installer.png");
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> cancel = root.findAccessibilityNodeInfosByText(
                context.getString(android.R.string.cancel));
        if (!cancel.isEmpty()) cancel.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        // The APK remains only in the app cache while the installer can still use it.
    }

    private static void screenshot(android.app.Instrumentation instrumentation, String name) throws Exception {
        File directory = instrumentation.getTargetContext().getExternalFilesDir("update-checks");
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            instrumentation.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
    }
}
