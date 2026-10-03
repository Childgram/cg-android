package org.telegram.messenger;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.ui.LaunchActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Childgram's published-release feed; never queries Telegram's app update service. */
public final class ChildgramUpdateController {
    public static final String FEED_URL = "https://update.childgram.org/android.json";
    private static final long CHECK_INTERVAL = 6 * 60 * 60 * 1000L;
    private static ChildgramUpdateController instance;

    public static ChildgramUpdateController getInstance() {
        if (instance == null) instance = new ChildgramUpdateController();
        return instance;
    }

    public static final class Manifest {
        public final BetaUpdate update;
        public final String fileUrl, sha256, packageName;
        public final long size;

        private Manifest(JSONObject json, String packageName) throws Exception {
            this.packageName = packageName;
            Object code = json.get("version_code");
            if (!(code instanceof Integer || code instanceof Long) || json.getLong("version_code") <= 0
                    || json.getLong("version_code") > Integer.MAX_VALUE) throw new IOException("Invalid version code");
            String version = json.getString("version");
            if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")) throw new IOException("Invalid version");
            update = new BetaUpdate(version, json.getInt("version_code"), json.optString("changelog", ""));
            fileUrl = json.getString("file_url");
            URI uri = new URI(fileUrl);
            String prefix = "/Childgram/cg-android/releases/download/v" + version + "/";
            if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getRawPath().equals(prefix + "childgram-" + version + "-arm64.apk")) {
                throw new IOException("Unexpected release URL");
            }
            sha256 = json.getString("sha256");
            if (!sha256.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256");
            size = json.getLong("size");
            if (size <= 0 || size > 250L * 1024 * 1024) throw new IOException("Invalid APK size");
        }

        public static Manifest parse(JSONObject json, int installedCode, String packageName) throws Exception {
            if (json.getInt("schema_version") != 1) throw new IOException("Unsupported update feed");
            if (!json.getBoolean("available")) return null;
            if (!packageName.equals(json.getString("package"))) throw new IOException("Unexpected package");
            Manifest manifest = new Manifest(json, packageName);
            return manifest.update.versionCode > installedCode ? manifest : null;
        }
    }

    private final DispatchQueue queue = new DispatchQueue("childgram-updates");
    private final ArrayList<Runnable> callbacks = new ArrayList<>();
    private Manifest available;
    private File downloaded;
    private boolean checking, failed, downloading;
    private float progress;
    private AtomicBoolean cancellation;
    private long lastCheck;

    private ChildgramUpdateController() {
        android.content.SharedPreferences prefs = context().getSharedPreferences("childgram_updates", Context.MODE_PRIVATE);
        lastCheck = prefs.getLong("checked_at", 0);
        try {
            available = Manifest.parse(new JSONObject(prefs.getString("manifest", "{}")), installedCode(), context().getPackageName());
        } catch (Exception ignored) { }
    }

    private static Context context() { return ApplicationLoader.applicationContext; }
    private static int installedCode() throws Exception {
        return context().getPackageManager().getPackageInfo(context().getPackageName(), 0).versionCode;
    }

    public BetaUpdate getUpdate() { return available == null ? null : available.update; }
    public boolean checkFailed() { return failed; }
    public boolean isDownloading() { return downloading; }
    public float getProgress() { return progress; }
    public File getDownloadedFile() { return downloaded != null && downloaded.isFile() ? downloaded : null; }

    public void check(boolean force, Runnable done) {
        if (done != null) callbacks.add(done);
        if (checking) return;
        if (downloading || !force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL) {
            completeCheck();
            return;
        }
        checking = true;
        failed = false;
        lastCheck = System.currentTimeMillis();
        queue.postRunnable(() -> {
            try {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                transfer(new URL(FEED_URL), body, 65536, new AtomicBoolean(), null);
                JSONObject json = new JSONObject(body.toString("UTF-8"));
                Manifest next = Manifest.parse(json, installedCode(), context().getPackageName());
                AndroidUtilities.runOnUIThread(() -> {
                    if (available == null || next == null || !available.sha256.equals(next.sha256)
                            || available.update.versionCode != next.update.versionCode
                            || !available.update.version.equals(next.update.version) || available.size != next.size) {
                        if (downloading) cancelDownload();
                        if (downloaded != null) downloaded.delete();
                        downloaded = null;
                        available = next;
                    }
                    context().getSharedPreferences("childgram_updates", Context.MODE_PRIVATE).edit()
                            .putString("manifest", json.toString()).putLong("checked_at", lastCheck).apply();
                    checking = false;
                    changed();
                    completeCheck();
                });
            } catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> {
                    checking = false;
                    failed = true;
                    // Retry sooner after a transient failure; do not erase a previously valid update.
                    lastCheck = System.currentTimeMillis() - CHECK_INTERVAL + 15 * 60 * 1000L;
                    completeCheck();
                });
            }
        });
    }

    private void completeCheck() {
        ArrayList<Runnable> pending = new ArrayList<>(callbacks);
        callbacks.clear();
        for (Runnable callback : pending) callback.run();
    }

    public void download() {
        if (available == null || downloading || getDownloadedFile() != null) return;
        Manifest release = available;
        AtomicBoolean cancel = new AtomicBoolean();
        cancellation = cancel;
        downloading = true;
        progress = 0;
        changed();
        queue.postRunnable(() -> {
            File temporary = null;
            try {
                File directory = new File(context().getCacheDir(), "childgram-updates");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update cache");
                File[] stale = directory.listFiles((dir, name) -> name.startsWith("update-") && name.endsWith(".apk"));
                if (stale != null) for (File file : stale) file.delete();
                temporary = File.createTempFile("update-", ".apk", directory);
                try (OutputStream out = new FileOutputStream(temporary)) {
                    transfer(new URL(release.fileUrl), out, release.size, cancel, bytes -> AndroidUtilities.runOnUIThread(() -> {
                        if (cancellation != cancel || cancel.get()) return;
                        progress = Math.min(0.99f, (float) bytes / release.size);
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
                    }));
                }
                verifyApk(context(), temporary, release);
                if (cancel.get()) throw new IOException("Cancelled");
                File verified = temporary;
                AndroidUtilities.runOnUIThread(() -> {
                    if (cancel.get() || cancellation != cancel || available != release) {
                        verified.delete();
                        return;
                    }
                    downloaded = verified;
                    downloading = false;
                    progress = 1;
                    changed();
                    if (!ApplicationLoader.mainInterfacePaused && LaunchActivity.instance != null) {
                        ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(LaunchActivity.instance, release.update, UserConfig.selectedAccount);
                    }
                });
            } catch (Exception error) {
                if (temporary != null) temporary.delete();
                AndroidUtilities.runOnUIThread(() -> {
                    if (cancellation != cancel) return;
                    downloading = false;
                    changed();
                    if (!cancel.get()) Toast.makeText(context(), LocaleController.getString(R.string.ChildgramUpdateDownloadFailed), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void cancelDownload() {
        if (cancellation != null) cancellation.set(true);
        downloading = false;
        progress = 0;
        changed();
    }

    private static void changed() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    interface Progress { void accept(long bytes); }

    private static void transfer(URL url, OutputStream output, long limit, AtomicBoolean cancel, Progress progress) throws Exception {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) throw new IOException("HTTPS required");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-cache");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect location");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200 || connection.getContentLength() > limit) throw new IOException("Invalid download response");
                try (InputStream input = connection.getInputStream()) {
                    copy(input, output, limit, cancel, progress);
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    static void copy(InputStream input, OutputStream output, long limit, AtomicBoolean cancel, Progress progress) throws Exception {
        byte[] buffer = new byte[65536];
        long total = 0, lastProgress = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (cancel.get()) throw new IOException("Cancelled");
            total += read;
            if (total > limit) throw new IOException("Download exceeds expected size");
            output.write(buffer, 0, read);
            if (progress != null && System.currentTimeMillis() - lastProgress > 200) {
                lastProgress = System.currentTimeMillis();
                progress.accept(total);
            }
        }
    }

    public static void verifyApk(Context context, File file, Manifest release) throws Exception {
        if (file.length() != release.size) throw new IOException("APK size mismatch");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65536];
            int read;
            while ((read = input.read(bytes)) != -1) hash.update(bytes, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!release.sha256.equals(hex.toString())) throw new IOException("APK SHA-256 mismatch");
        PackageManager pm = context.getPackageManager();
        PackageInfo apk = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        if (apk == null || !release.packageName.equals(apk.packageName)
                || !context.getPackageName().equals(apk.packageName) || apk.versionCode != release.update.versionCode
                || !release.update.version.equals(apk.versionName) || apk.signatures == null || installed.signatures == null
                || apk.signatures.length != 1 || installed.signatures.length != 1
                || !Arrays.equals(apk.signatures[0].toByteArray(), installed.signatures[0].toByteArray())
                || Build.VERSION.SDK_INT >= 24 && apk.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) {
            throw new IOException("APK identity, version or signing certificate mismatch");
        }
    }
}
