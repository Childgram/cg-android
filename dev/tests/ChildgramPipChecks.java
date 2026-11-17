package org.telegram.messenger;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.utils.pip.PipCoordinator;
import org.telegram.utils.pip.PipSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Optional real decoder/window checks. The only message and video are local synthetic fixtures. */
public final class ChildgramPipChecks {
    private final Instrumentation instrumentation;
    private int assertions;
    private String stage = "setup";
    private PhotoViewer viewer;
    private PipSession session;
    private MessageObject message;

    private ChildgramPipChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    public static int run(Instrumentation instrumentation, String mode) throws Exception {
        ChildgramPipChecks checks = new ChildgramPipChecks(instrumentation);
        try {
            return checks.checks(mode);
        } catch (Throwable error) {
            // Throwable messages may contain account or file data; only code locations are reported.
            StringBuilder details = new StringBuilder("PiP check failed at ").append(checks.stage);
            for (int cause = 0; error != null && cause < 3; cause++, error = error.getCause()) {
                details.append(" | ").append(error.getClass().getSimpleName());
                StackTraceElement[] frames = error.getStackTrace();
                for (int i = 0; i < Math.min(6, frames.length); i++) {
                    details.append(" at ").append(frames[i].getClassName()).append('.').append(frames[i].getMethodName())
                            .append(':').append(frames[i].getLineNumber());
                }
            }
            throw new AssertionError(details.toString());
        }
    }

    private int checks(String mode) throws Exception {
        expect("PiP test mode is system or overlay", "system".equals(mode) || "overlay".equals(mode));
        boolean overlay = "overlay".equals(mode);
        expect("PiP permission matches the requested test mode", Settings.canDrawOverlays(instrumentation.getTargetContext()) == overlay);
        expect("system PiP is supported and allowed", overlay || PipCoordinator.canEnterSystemPip());
        main(() -> expect("PiP test starts without another viewer or activity", LaunchActivity.instance == null
                && !PhotoViewer.hasInstance() && PhotoViewer.getPipInstance() == null));
        File video = new File(instrumentation.getTargetContext().getCacheDir(), "childgram-pip-fixture.mp4");
        try {
            try (InputStream input = instrumentation.getContext().getAssets().open("childgram-pip.mp4");
                 FileOutputStream output = new FileOutputStream(video)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            }
            // startActivitySync can wait indefinitely for an existing singleTask activity. Poll instead.
            bringToFront();
            await("test activity reaches the foreground", () -> LaunchActivity.instance != null && LaunchActivity.isResumed
                    && current("currentScreen") != null);
            Activity activity = (Activity) (Object) LaunchActivity.instance;
            main(() -> {
                int account = UserConfig.selectedAccount;
                expect("PiP fixture has an authorized test account", UserConfig.getInstance(account).getClientUserId() != 0);
                message = videoMessage(account, video);
                viewer = PhotoViewer.getInstance();
                viewer.setParentActivity(activity);
                ArrayList<MessageObject> messages = new ArrayList<>();
                messages.add(message);
                // Zero gallery dialog prevents loading the real Saved Messages history.
                expect("local video opens in the real viewer", viewer.openPhoto(messages, 0, 0, 0, 0,
                        new PhotoViewer.EmptyPhotoViewerProvider()));
            });
            await("local decoder plays a full-screen video", () -> viewer.isChildgramVideoPlaying()
                    && Boolean.TRUE.equals(get(viewer, "textureUploaded")) && !(Boolean) get(viewer, "pipAnimationInProgress"));
            main(() -> {
                expect("full-screen source is the synthetic message", viewer.getChildgramVideoMessage() == message);
                expect("full-screen playback is absent from PiP", PhotoViewer.getChildgramPipVideoMessage() == null);
                call(viewer, "switchToPip", new Class<?>[]{boolean.class}, false);
            });
            await("real floating player becomes visible inside the app", () -> {
                session = (PipSession) get(viewer, "pipSession");
                return session != null && session.getState() == PipSession.STATE_FLOATING
                        && PhotoViewer.getChildgramPipVideoMessage() == message && LaunchActivity.isResumed;
            });
            main(() -> {
                checkSource(ChildgramUsageStorage.PIP_INSIDE);
                expect("floating playback is not full-screen playback", !viewer.isChildgramVideoPlaying());
                Object screen = current("currentScreen");
                expect("foreground screen remains independent from PiP", screen != null && !(Boolean) get(screen, "video"));
                checkSuppressedStates();
            });
            expect("Home action is accepted", instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
            await("playing PiP survives Home in the requested host", () -> !LaunchActivity.isResumed
                    && (overlay ? session.getState() == PipSession.STATE_FLOATING && !activity.isInPictureInPictureMode()
                    : session.getState() == PipSession.STATE_SYSTEM && activity.isInPictureInPictureMode())
                    && PhotoViewer.getChildgramPipVideoMessage() == message);
            long[] outsidePosition = new long[1];
            main(() -> outsidePosition[0] = session.content.getCurrentPosition());
            await("outside video continues advancing", () -> session.content.getCurrentPosition() > outsidePosition[0] + 500);
            await("tracker opens an outside interval with the video source", () -> trackedPipMatches(ChildgramUsageStorage.PIP_OUTSIDE));
            Object[] outsideInterval = new Object[1];
            long[] checkpoint = new long[1];
            main(() -> {
                outsideInterval[0] = trackedPip();
                checkpoint[0] = (Long) get(outsideInterval[0], "lastSaveElapsed");
            });
            // No manual update or playback/lifecycle event: the background ticker must save a checkpoint.
            await("outside ticker advances its storage checkpoint", () -> trackedPipMatches(ChildgramUsageStorage.PIP_OUTSIDE)
                    && (Long) get(trackedPip(), "lastSaveElapsed") > checkpoint[0]);
            main(() -> {
                checkSource(ChildgramUsageStorage.PIP_OUTSIDE);
                expect("Home stops ordinary screen accounting", current("currentScreen") == null);
                if (!overlay) checkSystemStates();
                session.content.pause();
            });
            await("paused PiP no longer counts as playback", () -> PhotoViewer.getChildgramPipVideoMessage() == null
                    && current("currentPip") == null && trackedPip() == null && !session.content.isPlaying());
            main(() -> {
                expect("paused PiP retains a candidate for later polling", PhotoViewer.hasChildgramPipVideo());
                expect("pausing preserves the message source", viewer.getCurrentMessageObject() == message);
                session.content.play();
            });
            await("resuming PiP restores outside playback", () -> PhotoViewer.getChildgramPipVideoMessage() == message
                    && trackedPipMatches(ChildgramUsageStorage.PIP_OUTSIDE) && trackedPip() != outsideInterval[0]);
            main(() -> checkSource(ChildgramUsageStorage.PIP_OUTSIDE));
            bringToFront();
            await("returning to the app restores floating playback", () -> LaunchActivity.isResumed
                    && !activity.isInPictureInPictureMode() && session.getState() == PipSession.STATE_FLOATING
                    && PhotoViewer.getChildgramPipVideoMessage() == message && trackedPipMatches(ChildgramUsageStorage.PIP_INSIDE));
            main(() -> { checkSource(ChildgramUsageStorage.PIP_INSIDE); session.expand(); });
            await("expanding returns to full-screen accounting", () -> session.getState() == PipSession.STATE_PRIMARY
                    && viewer.isChildgramVideoPlaying() && PhotoViewer.getChildgramPipVideoMessage() == null);
            main(() -> expect("expanding keeps the same message", viewer.getChildgramVideoMessage() == message));
        } finally {
            try {
                main(() -> { if (viewer != null) viewer.destroyPhotoViewer(); });
            } finally {
                video.delete();
                bringToFront();
            }
        }
        expect("local video fixture is deleted", !video.exists());
        return assertions;
    }

    private void checkSource(int kind) throws Exception {
        Object pip = current("currentPip");
        expect("PiP identifies its exact message", PhotoViewer.getChildgramPipVideoMessage() == message);
        expect("PiP preserves account ownership", pip != null && (Long) get(pip, "owner")
                == UserConfig.getInstance(message.currentAccount).getClientUserId());
        expect("PiP preserves the source chat", ("chat:" + message.getDialogId()).equals(get(pip, "key")));
        expect("PiP records its foreground or outside mode", (Integer) get(pip, "kind") == kind);
    }

    private boolean trackedPipMatches(int kind) throws Exception {
        Object pip = trackedPip();
        return pip != null && (Integer) get(pip, "kind") == kind
                && (Long) get(pip, "owner") == UserConfig.getInstance(message.currentAccount).getClientUserId()
                && ("chat:" + message.getDialogId()).equals(get(pip, "key"));
    }

    private static Object trackedPip() throws Exception {
        Field field = ChildgramUsageTracker.class.getDeclaredField("pipSession");
        field.setAccessible(true);
        return field.get(null);
    }

    private void checkSuppressedStates() throws Exception {
        boolean screenOn = ApplicationLoader.isScreenOn, locked = SharedConfig.appLocked;
        int selectedAccount = UserConfig.selectedAccount;
        int state = session.getState();
        Object floating = get(session, "floating");
        View view = (View) get(floating, "contentView");
        float alpha = view.getAlpha();
        Object player = get(viewer, "videoPlayer");
        Field playerField = player.getClass().getField("player");
        Object realPlayer = playerField.get(player);
        try {
            UserConfig.selectedAccount = (message.currentAccount + 1) % UserConfig.MAX_ACCOUNT_COUNT;
            checkSource(ChildgramUsageStorage.PIP_INSIDE);
            UserConfig.selectedAccount = selectedAccount;
            ApplicationLoader.isScreenOn = false;
            expect("screen off excludes PiP from usage", current("currentPip") == null);
            expect("screen off retains a live PiP candidate", PhotoViewer.hasChildgramPipVideo());
            ApplicationLoader.isScreenOn = screenOn;
            SharedConfig.appLocked = true;
            expect("app lock excludes PiP from usage", current("currentPip") == null);
            SharedConfig.appLocked = locked;
            view.setAlpha(0);
            expect("hidden floating window does not count", PhotoViewer.getChildgramPipVideoMessage() == null);
            view.setAlpha(alpha);
            set(session, "state", PipSession.STATE_SYSTEM);
            expect("unconfirmed system transition does not count", PhotoViewer.getChildgramPipVideoMessage() == null);
            set(session, "state", PipSession.STATE_DISMISSED);
            expect("dismissed session is not a live candidate", !PhotoViewer.hasChildgramPipVideo());
            set(session, "state", state);
            // Simulate buffering/suppression: playback intent remains true, actual playback is false.
            playerField.set(player, Proxy.newProxyInstance(playerField.getType().getClassLoader(),
                    new Class<?>[]{playerField.getType()}, (proxy, method, args) -> {
                        if (method.getName().equals("isPlaying")) return false;
                        if (method.getName().equals("getPlayWhenReady")) return true;
                        throw new AssertionError("unexpected player call in PiP state fixture");
                    }));
            expect("playback intent alone is not counted", PhotoViewer.getChildgramPipVideoMessage() == null);
            expect("buffering keeps the polling candidate", PhotoViewer.hasChildgramPipVideo());
        } finally {
            playerField.set(player, realPlayer);
            view.setAlpha(alpha);
            set(session, "state", state);
            ApplicationLoader.isScreenOn = screenOn;
            SharedConfig.appLocked = locked;
            UserConfig.selectedAccount = selectedAccount;
        }
    }

    private void checkSystemStates() throws Exception {
        Object host = get(PipCoordinator.getInstance(), "systemHost");
        boolean stashed = (Boolean) get(host, "isStashed"), entering = (Boolean) get(host, "entering");
        Field primary = PhotoViewer.class.getDeclaredField("Instance"), floating = PhotoViewer.class.getDeclaredField("PipInstance");
        primary.setAccessible(true);
        floating.setAccessible(true);
        Object originalPrimary = primary.get(null), originalFloating = floating.get(null);
        try {
            primary.set(null, viewer);
            floating.set(null, null);
            expect("direct system PiP finds the source in the primary instance", PhotoViewer.getChildgramPipVideoMessage() == message);
            set(host, "isStashed", true);
            expect("stashed system PiP does not count", PhotoViewer.getChildgramPipVideoMessage() == null);
            expect("stashing retains a live polling candidate", PhotoViewer.hasChildgramPipVideo());
            set(host, "isStashed", false);
            set(host, "entering", true);
            expect("unfinished system transition does not count", PhotoViewer.getChildgramPipVideoMessage() == null);
        } finally {
            set(host, "isStashed", stashed);
            set(host, "entering", entering);
            primary.set(null, originalPrimary);
            floating.set(null, originalFloating);
        }
    }

    private static MessageObject videoMessage(int account, File file) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = -731;
        message.message = "";
        message.out = true;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = UserConfig.getInstance(account).getClientUserId();
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = message.peer_id.user_id;
        message.attachPath = file.getAbsolutePath();
        message.media = new TLRPC.TL_messageMediaDocument();
        TLRPC.TL_document document = new TLRPC.TL_document();
        message.media.document = document;
        document.id = -731;
        document.file_reference = new byte[0];
        document.mime_type = "video/mp4";
        document.size = file.length();
        TLRPC.TL_documentAttributeVideo attributes = new TLRPC.TL_documentAttributeVideo();
        attributes.w = 320; attributes.h = 180; attributes.duration = 90;
        document.attributes.add(attributes);
        MessageObject result = new MessageObject(account, message, false, false);
        result.attachPathExists = true;
        result.mediaExists = true;
        return result;
    }

    private void bringToFront() {
        instrumentation.getTargetContext().startActivity(new Intent(instrumentation.getTargetContext(), LaunchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private interface Action { void run() throws Exception; }
    private interface Condition { boolean matches() throws Exception; }
    private void main(Action action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() instanceof Exception) throw (Exception) error.get();
        if (error.get() instanceof Error) throw (Error) error.get();
    }

    private void await(String label, Condition condition) throws Exception {
        stage = label;
        long deadline = SystemClock.elapsedRealtime() + 15000;
        boolean[] ready = {false};
        do {
            main(() -> ready[0] = condition.matches());
            if (ready[0]) break;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        expect(label, ready[0]);
    }

    private static Object current(String name) throws Exception { return call(null, ChildgramUsageTracker.class, name, new Class<?>[0]); }
    private static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        return call(object, object.getClass(), name, types, args);
    }
    private static Object call(Object object, Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(object, args);
    }
    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Object get(Object object, String name) throws Exception { return field(object, name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object, name).set(object, value); }
    private void expect(String label, boolean condition) { stage = label; assertions++; if (!condition) throw new AssertionError(label); }
}
