package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.FrameLayout;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChildgramParentalPinActivity;
import org.telegram.ui.CodeFieldContainer;
import org.telegram.ui.CodeNumberField;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Local configuration only; invitation sync is exercised only with the restriction off. */
public final class ChildgramParentalInstrumentation extends Instrumentation {
    private int assertions;
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        String failure = null;
        preferences = getTargetContext().getSharedPreferences("childgram_parental", Context.MODE_PRIVATE);
        Map<String, ?> original = new HashMap<>(preferences.getAll());
        try {
            expect("Childgram build required", BuildVars.CHILDGRAM);
            expect("clear local test fixture", preferences.edit().clear().commit());
            checkSettings();
            checkInvitationOptOut();
            checkAccess();
            checkPin();
            checkThrottle();
            checkCorruption();
            checkPinTransitions();
        } catch (Throwable error) {
            // Never include stored values or exception messages that may contain user data.
            failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName();
        } finally {
            if (!restore(original)) failure = "Could not restore original parental settings";
        }
        report.putString("status", failure == null ? "passed" : "failed");
        report.putInt("assertions", assertions);
        report.putBoolean("original_settings_restored", preferences.getAll().equals(original));
        if (failure != null) report.putString("failure", failure);
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }

    private void checkInvitationOptOut() throws Exception {
        Field instancesField = privacyField("instances");
        ChildgramPrivacyController[] instances = (ChildgramPrivacyController[]) instancesField.get(null);
        int account = UserConfig.selectedAccount;
        ChildgramPrivacyController previous = instances[account];
        boolean wasForeground = privacyField("foreground").getBoolean(null);
        Constructor<ChildgramPrivacyController> constructor = ChildgramPrivacyController.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        ChildgramPrivacyController[] created = new ChildgramPrivacyController[1];
        Exception[] creationError = new Exception[1];
        runOnMainSync(() -> {
            try { created[0] = constructor.newInstance(account); }
            catch (Exception error) { creationError[0] = error; }
        });
        if (creationError[0] != null) throw creationError[0];
        ChildgramPrivacyController controller = created[0];
        try {
            instances[account] = controller;
            privacyField("foreground").setBoolean(null, false);
            privacyField("pending").setBoolean(controller, true);
            privacyField("busy").setBoolean(controller, true);
            privacyField("retryAt").setLong(controller, SystemClock.elapsedRealtime() + 30_000);
            Runnable retry = (Runnable) privacyField("retry").get(controller);
            AndroidUtilities.runOnUIThread(retry, 30_000);
            int generation = privacyField("generation").getInt(controller);
            runOnMainSync(() -> ChildgramParentalSettings.setBlockInvites(false));
            waitForIdleSync();
            expect("opt-out cancels pending enforcement", !privacyField("pending").getBoolean(controller)
                    && !privacyField("busy").getBoolean(controller));
            expect("opt-out invalidates old responses", privacyField("generation").getInt(controller) > generation);
            expect("opt-out clears retry time", privacyField("retryAt").getLong(controller) == 0);
            expect("opt-out removes scheduled retry", !ApplicationLoader.applicationHandler.hasCallbacks(retry));
            for (int entry = 0; entry < 2; entry++) {
                runOnMainSync(() -> {
                    ChildgramPrivacyController.onForeground();
                    ChildgramPrivacyController.onAccountActivated(account);
                    controller.didReceivedNotification(NotificationCenter.didUpdateConnectionState, account);
                });
                waitForIdleSync();
                expect("disabled policy stays idle on foreground and reconnect", !privacyField("pending").getBoolean(controller)
                        && !privacyField("busy").getBoolean(controller) && privacyField("requestId").getInt(controller) == 0);
                runOnMainSync(ChildgramPrivacyController::onBackground);
            }
        } finally {
            AndroidUtilities.cancelRunOnUIThread((Runnable) privacyField("retry").get(controller));
            runOnMainSync(() -> {
                NotificationCenter.getInstance(account).removeObserver(controller, NotificationCenter.didUpdateConnectionState);
                NotificationCenter.getInstance(account).removeObserver(controller, NotificationCenter.appDidLogout);
            });
            instances[account] = previous;
            privacyField("foreground").setBoolean(null, wasForeground);
            // Restore the fixture without enabling real server reconciliation.
            preferences.edit().putBoolean("block_invites", true).commit();
        }
    }

    private static Field privacyField(String name) throws Exception {
        Field field = ChildgramPrivacyController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void checkPinTransitions() throws Exception {
        for (boolean setup : new boolean[]{false, true}) {
            if (setup) ChildgramParentalSettings.removePin();
            else ChildgramParentalSettings.setPin("4826");
            CountDownLatch success = new CountDownLatch(1);
            ChildgramParentalPinActivity[] screen = new ChildgramParentalPinActivity[1];
            String[] codeAtSuccess = new String[1];
            try {
                onMainChecked(() -> {
                    screen[0] = pinScreen(setup, () -> {
                        try { codeAtSuccess[0] = pinFields(screen[0]).getCode(); }
                        catch (Exception ignored) { }
                        success.countDown();
                    });
                    if (setup) {
                        enterPin(screen[0], "4826");
                        expect("first PIN entry clears for confirmation", pinFields(screen[0]).getCode().isEmpty());
                    }
                    enterPin(screen[0], "4826");
                    expect("submitted PIN stays visible during verification", pinFields(screen[0]).getCode().equals("4826"));
                    for (CodeNumberField field : pinFields(screen[0]).codeField) {
                        expect("verification blocks further input", !field.isEnabled() && !field.isFocusable());
                    }
                });
                expect("success animation completes", success.await(5, TimeUnit.SECONDS));
                onMainChecked(() -> {
                    expect("navigation receives the filled PIN form", "4826".equals(codeAtSuccess[0]));
                    expect("successful form does not refocus first digit", !pinFields(screen[0]).codeField[0].hasFocus());
                    screen[0].onPause();
                    expect("PIN survives closing transition", pinFields(screen[0]).getCode().equals("4826"));
                });
            } finally {
                if (screen[0] != null) onMainChecked(() -> screen[0].onFragmentDestroy());
                awaitPinVerification();
            }
            onMainChecked(() -> expect("destroyed form clears PIN", pinFields(screen[0]).getCode().isEmpty()));
        }

        ChildgramParentalSettings.setPin("4826");
        CountDownLatch unexpectedSuccess = new CountDownLatch(1);
        ChildgramParentalPinActivity[] screen = new ChildgramParentalPinActivity[1];
        try {
            onMainChecked(() -> {
                screen[0] = pinScreen(false, unexpectedSuccess::countDown);
                enterPin(screen[0], "4827");
            });
            awaitPinVerification();
            onMainChecked(() -> {
                expect("wrong PIN clears for retry", pinFields(screen[0]).getCode().isEmpty());
                expect("wrong PIN restores input", pinFields(screen[0]).codeField[0].isEnabled());
                enterPin(screen[0], "4826");
            });
            awaitPinVerification();
            onMainChecked(() -> {
                expect("success animation retains PIN", pinFields(screen[0]).getCode().equals("4826"));
                screen[0].onPause();
                expect("backgrounding clears unfinished authentication", pinFields(screen[0]).getCode().isEmpty());
            });
            expect("backgrounding cancels delayed success", !unexpectedSuccess.await(600, TimeUnit.MILLISECONDS));
            onMainChecked(() -> {
                screen[0].onResume();
                expect("return requires fresh input", pinFields(screen[0]).getCode().isEmpty()
                        && pinFields(screen[0]).codeField[0].isEnabled());
            });
        } finally {
            if (screen[0] != null) onMainChecked(() -> screen[0].onFragmentDestroy());
            awaitPinVerification();
        }
    }

    private ChildgramParentalPinActivity pinScreen(boolean setup, Runnable success) {
        ChildgramParentalPinActivity screen = new ChildgramParentalPinActivity(setup, ignored -> success.run());
        FrameLayout host = new FrameLayout(getTargetContext());
        // Exercise real fields and lifecycle without opening an activity or a Telegram account.
        screen.setParentLayout((INavigationLayout) Proxy.newProxyInstance(INavigationLayout.class.getClassLoader(),
                new Class<?>[]{INavigationLayout.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getView")) return host;
                    if (method.getName().equals("getLastFragment")) return screen;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                }));
        host.addView(screen.createView(getTargetContext()));
        screen.onResume();
        return screen;
    }

    private CodeFieldContainer pinFields(ChildgramParentalPinActivity screen) throws Exception {
        Field field = ChildgramParentalPinActivity.class.getDeclaredField("fields");
        field.setAccessible(true);
        return (CodeFieldContainer) field.get(screen);
    }

    private void enterPin(ChildgramParentalPinActivity screen, String pin) throws Exception {
        CodeFieldContainer fields = pinFields(screen);
        for (int i = 0; i < 4; i++) fields.codeField[i].setText(pin.substring(i, i + 1));
    }

    private void awaitPinVerification() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Utilities.globalQueue.postRunnable(() -> AndroidUtilities.runOnUIThread(done::countDown));
        expect("PIN verification completes", done.await(5, TimeUnit.SECONDS));
    }

    private interface CheckedRunnable { void run() throws Exception; }

    private void onMainChecked(CheckedRunnable action) throws Exception {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) {
            if (failure[0] instanceof Exception) throw (Exception) failure[0];
            throw new AssertionError(failure[0].getMessage());
        }
    }

    private void checkSettings() {
        expect("all restrictions default on", ChildgramParentalSettings.blockChannels()
                && ChildgramParentalSettings.blockGroups() && ChildgramParentalSettings.blockBots()
                && ChildgramParentalSettings.blockInvites());
        expect("fresh install has no PIN", !ChildgramParentalSettings.hasPin());
        expect("fresh install requires setup", !ChildgramParentalSettings.isSetupAcknowledged());
        String[] keys = {"block_channels", "block_groups", "block_bots", "block_invites"};
        for (int disabled = 0; disabled < keys.length; disabled++) {
            // Raw local preferences avoid the real invite setter's server reconciliation.
            expect("persist toggle fixture", preferences.edit().putBoolean(keys[disabled], false).commit());
            boolean[] values = {ChildgramParentalSettings.blockChannels(), ChildgramParentalSettings.blockGroups(),
                    ChildgramParentalSettings.blockBots(), ChildgramParentalSettings.blockInvites()};
            for (int i = 0; i < values.length; i++) expect("toggle affects only its restriction", values[i] == (i != disabled));
            expect("persist restored toggle", preferences.edit().remove(keys[disabled]).commit());
        }
        ChildgramParentalSettings.setSetupAcknowledged(true);
        expect("setup acknowledgement persisted", ChildgramParentalSettings.isSetupAcknowledged()
                && preferences.getBoolean("setup_acknowledged", false));
        ChildgramParentalSettings.setSetupAcknowledged(false);
        expect("setup acknowledgement can be reset", !ChildgramParentalSettings.isSetupAcknowledged());
    }

    private void checkPin() {
        expect("missing PIN cannot authenticate", !ChildgramParentalSettings.verifyPin("4826"));
        for (String invalid : new String[]{"", "123", "12345", "12a4", "１２３４", "12 4"}) {
            expect("invalid PIN rejected", !ChildgramParentalSettings.setPin(invalid));
            expect("invalid PIN does not install credentials", !ChildgramParentalSettings.hasPin());
        }
        expect("four-digit PIN installed", ChildgramParentalSettings.setPin("4826"));
        expect("PIN is enabled", ChildgramParentalSettings.hasPin());
        expect("installing PIN acknowledges setup", ChildgramParentalSettings.isSetupAcknowledged());
        String firstHash = preferences.getString("pin_hash", "");
        String firstSalt = preferences.getString("pin_salt", "");
        expect("PIN is stored only as a salted hash", !firstHash.isEmpty() && !firstHash.equals("4826")
                && !firstSalt.isEmpty() && !preferences.getAll().containsValue("4826"));
        expect("correct PIN accepted", ChildgramParentalSettings.verifyPin("4826"));
        expect("wrong PIN rejected", !ChildgramParentalSettings.verifyPin("4827"));
        expect("correct PIN clears failed attempts", ChildgramParentalSettings.verifyPin("4826")
                && preferences.getInt("pin_bad_tries", 0) == 0);
        expect("reinstalling same PIN succeeds", ChildgramParentalSettings.setPin("4826"));
        expect("same PIN receives independent salt", !firstSalt.equals(preferences.getString("pin_salt", "")));
        expect("same PIN receives independent hash", !firstHash.equals(preferences.getString("pin_hash", "")));
        expect("PIN changes", ChildgramParentalSettings.setPin("7093"));
        expect("old PIN no longer works", !ChildgramParentalSettings.verifyPin("4826"));
        expect("new PIN works", ChildgramParentalSettings.verifyPin("7093"));
        expect("PIN removed", ChildgramParentalSettings.removePin());
        expect("removed PIN no longer protects section", !ChildgramParentalSettings.hasPin());
        expect("removed PIN cannot authenticate", !ChildgramParentalSettings.verifyPin("7093"));
        expect("removal clears both credentials", !preferences.contains("pin_hash") && !preferences.contains("pin_salt"));
        expect("removal preserves setup acknowledgement", ChildgramParentalSettings.isSetupAcknowledged());
        expect("PIN operations preserve restrictions", ChildgramParentalSettings.blockChannels()
                && ChildgramParentalSettings.blockGroups() && ChildgramParentalSettings.blockBots()
                && ChildgramParentalSettings.blockInvites());
    }

    private void checkAccess() {
        Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> {
            try {
                ApplicationLoader.postInitApplication();
                ChildgramPrivacyController.onBackground();
                int account = UserConfig.selectedAccount;
                MessagesController controller = MessagesController.getInstance(account);
                ChildgramAccess access = ChildgramAccess.getInstance(account);
                long id = 8_000_000_000_000L + (System.nanoTime() & 0xfffffff);
                TLRPC.TL_channel channel = new TLRPC.TL_channel();
                channel.id = id;
                channel.broadcast = true;
                channel.left = true;
                controller.putChat(channel, true);
                TLRPC.TL_channel group = new TLRPC.TL_channel();
                group.id = id + 1;
                group.megagroup = true;
                group.left = true;
                controller.putChat(group, true);
                TLRPC.TL_chat basicGroup = new TLRPC.TL_chat();
                basicGroup.id = id + 2;
                basicGroup.left = true;
                controller.putChat(basicGroup, true);
                TLRPC.TL_user bot = new TLRPC.TL_user();
                bot.id = id + 3;
                bot.bot = true;
                controller.putUser(bot, true);
                TLRPC.TL_chatInvite channelInvite = new TLRPC.TL_chatInvite();
                channelInvite.channel = true;
                TLRPC.TL_chatInvite groupInvite = new TLRPC.TL_chatInvite();
                groupInvite.channel = true;
                groupInvite.megagroup = true;
                TLRPC.TL_chatInvite basicGroupInvite = new TLRPC.TL_chatInvite();
                TLRPC.TL_channelForbidden forbiddenChannel = new TLRPC.TL_channelForbidden();
                forbiddenChannel.id = id + 5;
                forbiddenChannel.broadcast = true;
                controller.putChat(forbiddenChannel, true);
                TLRPC.TL_channelForbidden forbiddenGroup = new TLRPC.TL_channelForbidden();
                forbiddenGroup.id = id + 6;
                forbiddenGroup.megagroup = true;
                controller.putChat(forbiddenGroup, true);
                TLRPC.TL_chatEmpty empty = new TLRPC.TL_chatEmpty();
                empty.id = id + 7;
                controller.putChat(empty, true);
                for (int mask = 0; mask < 8; mask++) {
                    boolean channels = (mask & 1) != 0;
                    boolean groups = (mask & 2) != 0;
                    boolean bots = (mask & 4) != 0;
                    expect("persist independent access toggles", preferences.edit()
                            .putBoolean("block_channels", channels).putBoolean("block_groups", groups)
                            .putBoolean("block_bots", bots).commit());
                    expect("channel follows its own toggle", access.isAllowed(-channel.id) == !channels);
                    expect("supergroup follows group toggle", access.isAllowed(-group.id) == !groups);
                    expect("basic group follows group toggle", access.isAllowed(-basicGroup.id) == !groups);
                    expect("unconfirmed bot follows bot toggle", access.isAllowed(bot.id) == !bots);
                    expect("channel invitation follows channel toggle", access.isInviteAllowed(channelInvite) == !channels);
                    expect("supergroup invitation follows group toggle", access.isInviteAllowed(groupInvite) == !groups);
                    expect("basic group invitation follows group toggle", access.isInviteAllowed(basicGroupInvite) == !groups);
                    expect("unresolved chat fails closed while either type restricted",
                            access.isAllowed(-(id + 4)) == !(channels || groups));
                    expect("unresolved invitation fails closed while either type restricted",
                            access.isInviteAllowed(null) == !(channels || groups));
                    expect("empty chat type is unresolved", access.isAllowed(-empty.id) == !(channels || groups));
                    expect("forbidden channel follows channel toggle", access.isAllowed(-forbiddenChannel.id) == !channels);
                    expect("forbidden supergroup follows group toggle", access.isAllowed(-forbiddenGroup.id) == !groups);
                    channel.min = group.min = true;
                    channel.left = group.left = false;
                    expect("minimal channel cannot prove membership", access.isAllowed(-channel.id) == !channels);
                    expect("minimal supergroup cannot prove membership", access.isAllowed(-group.id) == !groups);
                    channel.min = group.min = false;
                    channel.left = group.left = true;
                }
                expect("restore strict access defaults", preferences.edit().remove("block_channels")
                        .remove("block_groups").remove("block_bots").commit());
                channel.left = false;
                group.left = false;
                basicGroup.left = false;
                expect("known channel remains allowed", access.isAllowed(-channel.id));
                expect("known supergroup remains allowed", access.isAllowed(-group.id));
                expect("known basic group remains allowed", access.isAllowed(-basicGroup.id));
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError(failure[0] instanceof AssertionError
                ? failure[0].getMessage() : failure[0].getClass().getSimpleName());
    }

    private void checkThrottle() {
        expect("install throttle fixture", ChildgramParentalSettings.setPin("4826"));
        expect("first incorrect attempt", !ChildgramParentalSettings.verifyPin("4827"));
        expect("second incorrect attempt", !ChildgramParentalSettings.verifyPin("4827"));
        expect("two failures do not lock PIN", ChildgramParentalSettings.getPinRetryMs() == 0);
        expect("third incorrect attempt", !ChildgramParentalSettings.verifyPin("4827"));
        long delay = ChildgramParentalSettings.getPinRetryMs();
        expect("third failure starts delay", delay > 0 && delay <= 5000);
        expect("retry delay stored", preferences.getLong("pin_retry_remaining", 0) > 0);
        expect("correct PIN cannot skip cooldown", !ChildgramParentalSettings.verifyPin("4826"));
        expect("blocked attempts do not restart delay", ChildgramParentalSettings.getPinRetryMs() <= delay);
        // Change only the saved sample, never the device clock. An elapsed timer must
        // remain authoritative during the same boot even after a wall-clock change.
        expect("write clock-change fixture", preferences.edit().putLong("pin_retry_wall", 0).commit());
        expect("wall-clock jump cannot bypass current-boot cooldown", ChildgramParentalSettings.getPinRetryMs() > 0);
        expect("write reboot fixture", preferences.edit().putInt("pin_retry_boot", -123)
                .putLong("pin_retry_elapsed", 0).putLong("pin_retry_wall", 0).commit());
        expect("reboot cannot bypass remaining cooldown", ChildgramParentalSettings.getPinRetryMs() > 0);
        expect("write expired elapsed fixture", preferences.edit()
                .putLong("pin_retry_elapsed", SystemClock.elapsedRealtime() - 10000).commit());
        expect("elapsed delay expires", ChildgramParentalSettings.getPinRetryMs() == 0);
        expect("fourth incorrect attempt", !ChildgramParentalSettings.verifyPin("4827"));
        long longerDelay = ChildgramParentalSettings.getPinRetryMs();
        expect("repeated guessing increases wait", longerDelay > 5000 && longerDelay <= 10000);
        expect("expire increased wait", preferences.edit()
                .putLong("pin_retry_elapsed", SystemClock.elapsedRealtime() - 20000).commit());
        expect("PIN works after cooldown", ChildgramParentalSettings.verifyPin("4826"));
        expect("successful verification resets cooldown", ChildgramParentalSettings.getPinRetryMs() == 0);
    }

    private void checkCorruption() {
        expect("install corruption fixture", ChildgramParentalSettings.setPin("4826"));
        String salt = preferences.getString("pin_salt", "");
        expect("write missing-salt fixture", preferences.edit().remove("pin_salt").commit());
        expect("missing salt fails closed", ChildgramParentalSettings.hasPin() && !ChildgramParentalSettings.verifyPin("4826"));
        expect("write corrupt-hash fixture", preferences.edit().putString("pin_salt", salt).putString("pin_hash", "invalid").commit());
        expect("malformed hash fails closed", ChildgramParentalSettings.hasPin() && !ChildgramParentalSettings.verifyPin("4826"));
    }

    @SuppressWarnings("unchecked")
    private boolean restore(Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
            else return false;
        }
        return editor.commit() && preferences.getAll().equals(values);
    }

    private void expect(String label, boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
