package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Device-wide parental settings, independent of Telegram's app lock and accounts. */
public final class ChildgramParentalSettings {
    private static final long MAX_RETRY_MS = 60 * 60 * 1000;

    private ChildgramParentalSettings() {
    }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("childgram_parental", Context.MODE_PRIVATE);
    }

    public static boolean blockChannels() {
        return BuildVars.CHILDGRAM && preferences().getBoolean("block_channels", true);
    }

    public static boolean blockGroups() {
        return BuildVars.CHILDGRAM && preferences().getBoolean("block_groups", true);
    }

    public static boolean blockBots() {
        return BuildVars.CHILDGRAM && preferences().getBoolean("block_bots", true);
    }

    public static boolean blockInvites() {
        return BuildVars.CHILDGRAM && preferences().getBoolean("block_invites", true);
    }

    public static void setBlockChannels(boolean block) {
        preferences().edit().putBoolean("block_channels", block).apply();
    }

    public static void setBlockGroups(boolean block) {
        preferences().edit().putBoolean("block_groups", block).apply();
    }

    public static void setBlockBots(boolean block) {
        preferences().edit().putBoolean("block_bots", block).apply();
    }

    public static void setBlockInvites(boolean block) {
        if (blockInvites() == block) return;
        preferences().edit().putBoolean("block_invites", block).apply();
        ChildgramPrivacyController.onSettingsChanged();
    }

    public static boolean isSetupAcknowledged() {
        return preferences().getBoolean("setup_acknowledged", false);
    }

    public static void setSetupAcknowledged(boolean acknowledged) {
        preferences().edit().putBoolean("setup_acknowledged", acknowledged).apply();
    }

    public static boolean hasPin() {
        return !preferences().getString("pin_hash", "").isEmpty();
    }

    public static synchronized boolean setPin(String pin) {
        if (!validPin(pin)) {
            return false;
        }
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = hash(pin, salt);
            return clearRetry(preferences().edit())
                    .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
                    .putBoolean("setup_acknowledged", true)
                    .commit();
        } catch (Exception e) {
            return false;
        }
    }

    /** Caller must already have authenticated access to parental settings. */
    public static synchronized boolean removePin() {
        return clearRetry(preferences().edit()).remove("pin_hash").remove("pin_salt").commit();
    }

    public static synchronized boolean verifyPin(String pin) {
        if (!hasPin() || getPinRetryMs() > 0) {
            return false;
        }
        SharedPreferences preferences = preferences();
        boolean matches = false;
        if (validPin(pin)) {
            try {
                byte[] salt = Base64.decode(preferences.getString("pin_salt", ""), Base64.NO_WRAP);
                byte[] expected = Base64.decode(preferences.getString("pin_hash", ""), Base64.NO_WRAP);
                matches = salt.length == 16 && expected.length == 32 && MessageDigest.isEqual(expected, hash(pin, salt));
            } catch (Exception e) {
                // Invalid or unreadable PIN data must never grant access.
            }
        }
        if (matches) {
            return clearRetry(preferences.edit()).commit();
        }
        int failures = Math.min(23, preferences.getInt("pin_bad_tries", 0) + 1);
        long delay = failures < 3 ? 0 : Math.min(MAX_RETRY_MS, 5000L << (failures - 3));
        saveRetry(preferences.edit().putInt("pin_bad_tries", failures), delay).commit();
        return false;
    }

    /** Uses monotonic time within a boot; a reboot retains the outstanding wait. */
    public static synchronized long getPinRetryMs() {
        SharedPreferences preferences = preferences();
        long remaining = Math.min(MAX_RETRY_MS, preferences.getLong("pin_retry_remaining", 0));
        if (remaining <= 0) {
            return 0;
        }
        long elapsed = SystemClock.elapsedRealtime();
        long lastElapsed = preferences.getLong("pin_retry_elapsed", elapsed);
        int boot = bootCount();
        int previousBoot = preferences.getInt("pin_retry_boot", -1);
        boolean sameBoot = boot >= 0 && boot == previousBoot;
        if (boot < 0 && previousBoot < 0) {
            // Android 6 has no boot counter. Clock changes restart the wait conservatively.
            long wallDelta = System.currentTimeMillis() - preferences.getLong("pin_retry_wall", 0);
            sameBoot = Math.abs(wallDelta - (elapsed - lastElapsed)) < 3000;
        }
        if (sameBoot && elapsed >= lastElapsed) {
            remaining = Math.max(0, remaining - (elapsed - lastElapsed));
        }
        saveRetry(preferences.edit(), remaining).commit();
        return remaining;
    }

    private static int bootCount() {
        return Settings.Global.getInt(ApplicationLoader.applicationContext.getContentResolver(), "boot_count", -1);
    }

    private static SharedPreferences.Editor saveRetry(SharedPreferences.Editor editor, long remaining) {
        return editor.putLong("pin_retry_remaining", remaining)
                .putLong("pin_retry_elapsed", SystemClock.elapsedRealtime())
                .putLong("pin_retry_wall", System.currentTimeMillis())
                .putInt("pin_retry_boot", bootCount());
    }

    private static SharedPreferences.Editor clearRetry(SharedPreferences.Editor editor) {
        return editor.remove("pin_bad_tries").remove("pin_retry_remaining").remove("pin_retry_elapsed")
                .remove("pin_retry_wall").remove("pin_retry_boot");
    }

    private static boolean validPin(String pin) {
        if (pin == null || pin.length() != 4) {
            return false;
        }
        for (int i = 0; i < pin.length(); i++) {
            if (pin.charAt(i) < '0' || pin.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static byte[] hash(String pin, byte[] salt) throws Exception {
        PBEKeySpec key = new PBEKeySpec(pin.toCharArray(), salt, 120000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(key).getEncoded();
        } finally {
            key.clearPassword();
        }
    }
}
