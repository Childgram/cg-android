package org.childgram.checks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.Base64;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Checks the installed R8 policy gate, resolved through the JNI-kept backend's owner type. */
public final class ReleaseWidgetGateInstrumentation extends Instrumentation {
    // The original alpha.7 runner used this name. Keep it so an interrupted run can recover.
    private static final String RECOVERY = "childgram_release_gate_recovery_alpha7";
    private String expectedVersion, expectedCode;
    private int assertions;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) {
            expectedVersion = arguments.getString("version_name");
            expectedCode = arguments.getString("version_code");
        }
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        SharedPreferences preferences = getTargetContext().getSharedPreferences("childgram_parental", Context.MODE_PRIVATE);
        // A separate target-private file is accessible under the instrumented process's UID.
        SharedPreferences recovery = getTargetContext().getSharedPreferences(RECOVERY, Context.MODE_PRIVATE);
        Map<String, ?> original = new HashMap<>(preferences.getAll());
        boolean mutated = false;
        String failure = null;
        try {
            expect("release package", getTargetContext().getPackageName().equals("org.childgram"));
            PackageInfo installed = getTargetContext().getPackageManager().getPackageInfo("org.childgram", 0);
            report.putString("installed_version", installed.versionName);
            report.putInt("installed_code", installed.versionCode);
            expect("release is not debuggable", (getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0);
            if (recovery.getBoolean("pending", false)) {
                expect("restore interrupted parental fixture", copyFields(preferences.edit(), recovery.getAll()).commit());
                original = new HashMap<>(preferences.getAll());
                expect("remove recovered parental backup", recovery.edit().clear().commit());
                report.putBoolean("interrupted_settings_recovered", true);
            }
            expect("installed release matches gradle.properties", installed.versionName.equals(expectedVersion)
                    && Integer.toString(installed.versionCode).equals(expectedCode));
            Class<?> backend = Class.forName("org.telegram.utils.twidget.WidgetSandboxBackend", true, getTargetContext().getClassLoader());
            Class<?> sessionType = backend.getDeclaredField("owner").getType();
            report.putString("session_class", sessionType.getName());
            Method gate = null;
            for (Method method : sessionType.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == boolean.class
                        && method.getParameterTypes().length == 0) {
                    if (gate != null) throw new UnsupportedOperationException();
                    gate = method;
                }
            }
            // The original Session has exactly one static boolean(): disabledByConfig().
            if (gate == null) throw new UnsupportedOperationException();
            gate.setAccessible(true);
            report.putString("gate_method", gate.getName());
            expect("persist original parental fields before mutation", copyFields(recovery.edit().clear(), original)
                    .putBoolean("pending", true).commit());
            mutated = true;
            expect("prepare default fixture", preferences.edit().remove("block_widgets").remove("pin_hash").remove("pin_salt").commit());
            expect("default policy blocks widgets without PIN", (Boolean) gate.invoke(null));

            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            PBEKeySpec fixture = new PBEKeySpec("4826".toCharArray(), salt, 120000, 256);
            byte[] hash;
            try { hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(fixture).getEncoded(); }
            finally { fixture.clearPassword(); }
            expect("prepare valid PIN fixture", preferences.edit()
                    .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("pin_hash", Base64.encodeToString(hash, Base64.NO_WRAP)).commit());
            expect("default policy remains blocked with PIN", (Boolean) gate.invoke(null));
            expect("prepare parental opt-out fixture", preferences.edit().putBoolean("block_widgets", false).commit());
            expect("valid PIN and opt-out actually open the policy gate", !(Boolean) gate.invoke(null));
            expect("remove PIN fixture", preferences.edit().remove("pin_hash").remove("pin_salt").commit());
            expect("raw opt-out without PIN closes the policy gate", (Boolean) gate.invoke(null));
            report.putString("policy_default_gate", "passed");
            report.putString("policy_pin_opt_out_control", "passed");
            report.putString("policy_no_pin_gate", "passed");
            report.putString("status", "passed");
        } catch (UnsupportedOperationException | ClassNotFoundException | NoSuchFieldException error) {
            report.putString("status", "unsupported_r8_shape");
            report.putString("limitation", "Required policy signature changed under R8.");
        } catch (Throwable error) {
            failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName();
        } finally {
            boolean restored = (!mutated || copyFields(preferences.edit(), original).commit())
                    && preferences.getAll().equals(original);
            report.putBoolean("original_settings_restored", restored);
            if (!restored) failure = "Could not restore parental fixture fields";
            if (mutated && restored && !getTargetContext().deleteSharedPreferences(RECOVERY))
                failure = "Could not remove parental recovery backup";
        }
        if (failure != null) {
            report.putString("status", "failed");
            report.putString("failure", failure);
        }
        report.putInt("assertions", assertions);
        report.putString("scope", "Actual R8 release policy gate using stored preference fixtures; no native start or PIN UI authentication claimed.");
        finish(failure == null && "passed".equals(report.getString("status")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }

    private static SharedPreferences.Editor copyFields(SharedPreferences.Editor editor, Map<String, ?> original) {
        for (String key : new String[]{"block_widgets", "pin_hash", "pin_salt"}) {
            Object value = original.get(key);
            if (value == null) editor.remove(key);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else throw new IllegalStateException("Unexpected parental preference type");
        }
        return editor;
    }

    private void expect(String label, boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
