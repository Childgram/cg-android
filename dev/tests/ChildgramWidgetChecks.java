package org.telegram.utils.twidget;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Binder;
import android.os.SystemClock;
import android.view.View;

import org.telegram.messenger.AppGlobalConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChildgramParentalSettings;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.WidgetInlinePreview;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;

/** Synthetic, offline widgets only. Called by the parental suite, which restores its preferences. */
public final class ChildgramWidgetChecks {
    private static final String HTML = "<html><body>Childgram widget check<script>"
            + "var n=0;function mark(){document.documentElement.setAttribute('data-conformance','childgram:'+ ++n);}"
            + "mark();setInterval(mark,100);</script></body></html>";
    private int assertions;

    public static int run(Instrumentation instrumentation) throws Exception {
        ChildgramWidgetChecks checks = new ChildgramWidgetChecks();
        checks.check(instrumentation);
        return checks.assertions;
    }

    private void check(Instrumentation instrumentation) throws Exception {
        WidgetSandboxSession[] normal = new WidgetSandboxSession[1];
        WidgetSandboxSession[] previewSession = new WidgetSandboxSession[1];
        WidgetInlinePreview[] preview = new WidgetInlinePreview[1];
        Object[] serverFlags = new Object[UserConfig.MAX_ACCOUNT_COUNT];
        boolean[] originalFlags = new boolean[serverFlags.length];
        boolean originalIsolated = WidgetEngine.isolated;
        boolean originalConformance = WidgetEngine.conformance;
        boolean originalAttackScan = WidgetEngine.attackScan;
        byte[] originalLimits = WidgetEngine.limits;
        String[] previewUrl = new String[1];
        Field configHandler = field(AppGlobalConfig.ConfigBoolean.class, "handler");
        Field configValue = null;
        try {
            // Override only in-memory server flags; never send or persist account configuration.
            for (int account = 0; account < serverFlags.length; account++) {
                serverFlags[account] = configHandler.get(AppGlobalConfig.getInstance(account).widgetsDisabled);
                if (configValue == null) configValue = field(serverFlags[account].getClass(), "value");
                originalFlags[account] = configValue.getBoolean(serverFlags[account]);
                configValue.setBoolean(serverFlags[account], false);
            }
            WidgetEngine.isolated = true;
            WidgetEngine.conformance(true);
            WidgetEngine.attackScan(false);
            WidgetEngine.limits(null);
            expect("native widgets are disabled by parental default", WidgetSandboxSession.disabledByConfig());
            Object connectionBefore = connection(WidgetRemoteEngine.INSTANCE);
            boolean keptBefore = field(WidgetRemoteEngine.class, "kept").getBoolean(WidgetRemoteEngine.INSTANCE);
            WidgetSandboxSession.prewarmEngine(instrumentation.getTargetContext());
            expect("parental default does not bind the remote engine", connection(WidgetRemoteEngine.INSTANCE) == connectionBefore);
            expect("parental default does not retain a prewarmed engine",
                    field(WidgetRemoteEngine.class, "kept").getBoolean(WidgetRemoteEngine.INSTANCE) == keptBefore);
            normal[0] = new WidgetSandboxSession(instrumentation.getTargetContext(), HTML, false, 1);
            boolean rejected = false;
            try { normal[0].start(320, false); }
            catch (IllegalStateException expected) { rejected = true; }
            expect("default restriction rejects native startup", rejected);
            expect("blocked startup has no engine handle", sessionHandle(normal[0]) == 0);
            expect("blocked startup produced no frame", normal[0].dump() == null);
            instrumentation.runOnMainSync(normal[0]::close);
            checkPendingStartup(instrumentation);

            TLRPC.TL_botInlineResult result = new TLRPC.TL_botInlineResult();
            result.id = "childgram-local-widget-check";
            result.query_id = -System.nanoTime();
            TLRPC.TL_botInlineMessageWidget widget = new TLRPC.TL_botInlineMessageWidget();
            widget.width = 200;
            widget.height = 100;
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
                deflater.write(HTML.getBytes(StandardCharsets.UTF_8));
            }
            widget.payload = compressed.toByteArray();
            result.send_message = widget;
            int previewWidth = AndroidUtilities.dp(320);
            instrumentation.runOnMainSync(() -> {
                previewUrl[0] = WidgetMedia.previewUrl(UserConfig.selectedAccount, result);
                preview[0] = new WidgetInlinePreview(new View(instrumentation.getTargetContext()), UserConfig.selectedAccount, result, null);
                preview[0].show(previewWidth);
                WidgetMedia.measure(previewUrl[0], previewWidth, false);
            });
            instrumentation.waitForIdleSync();
            Bitmap[] rendered = new Bitmap[1];
            instrumentation.runOnMainSync(() -> {
                rendered[0] = Bitmap.createBitmap(previewWidth, preview[0].heightPx(previewWidth), Bitmap.Config.ARGB_8888);
                preview[0].draw(new Canvas(rendered[0]), 0, 0);
            });
            try (FileOutputStream image = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(), "childgram-widget-blocked.png"))) {
                expect("blocked preview screenshot saved", rendered[0].compress(Bitmap.CompressFormat.PNG, 100, image));
            } finally {
                rendered[0].recycle();
            }
            expect("blocked inline preview has no runtime", WidgetMedia.find(previewUrl[0]) == null);
            expect("blocked inline preview creates no session", field(WidgetInlinePreview.class, "message").get(preview[0]) == null);
            expect("blocking preserves preview metadata", WidgetMedia.isPreview(previewUrl[0]));

            expect("install native-widget PIN fixture", ChildgramParentalSettings.setPin("4826"));
            int activeAccount = -1;
            for (int account = 0; account < serverFlags.length; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) { activeAccount = account; break; }
            }
            // Keep the shown preview stopped until both the parent and server allow startup.
            if (activeAccount >= 0) configValue.setBoolean(serverFlags[activeAccount], true);
            instrumentation.runOnMainSync(() -> ChildgramParentalSettings.setBlockWidgets(false));
            expect("parent can disable the local widget restriction", !ChildgramParentalSettings.blockWidgets());
            if (activeAccount >= 0) {
                expect("parental opt-out cannot override server widget kill switch", WidgetSandboxSession.disabledByConfig());
                configValue.setBoolean(serverFlags[activeAccount], false);
                instrumentation.runOnMainSync(WidgetMedia::onParentalSettingsChanged);
            }
            expect("parent can allow native widgets", !WidgetSandboxSession.disabledByConfig());
            CountDownLatch normalRan = new CountDownLatch(1), previewRan = new CountDownLatch(1);
            AtomicInteger normalMarkers = new AtomicInteger(), previewMarkers = new AtomicInteger();
            normal[0] = new WidgetSandboxSession(instrumentation.getTargetContext(), HTML, false, 1);
            instrumentation.runOnMainSync(() -> {
                normal[0].setConformanceListener(value -> {
                    if (value.startsWith("childgram:")) { normalMarkers.incrementAndGet(); normalRan.countDown(); }
                });
                normal[0].setVisible(true);
            });
            normal[0].start(320, false);
            expect("allowed native widget executes harmless JavaScript", normalRan.await(15, TimeUnit.SECONDS));
            expect("allowed widget uses the remote engine", field(WidgetSandboxSession.class, "engine").get(normal[0]) == WidgetRemoteEngine.INSTANCE);
            long normalHandle = sessionHandle(normal[0]);
            expect("allowed widget owns a remote client handle", normalHandle != 0 && clients(WidgetRemoteEngine.INSTANCE).containsKey(normalHandle));
            Object connected = connection(WidgetRemoteEngine.INSTANCE);
            IWidgetEngine remoteService = (IWidgetEngine) field(connected.getClass(), "engine").get(connected);
            expect("widget service Binder belongs to another process", remoteService != null
                    && remoteService.asBinder().queryLocalInterface("org.telegram.utils.twidget.IWidgetEngine") == null);

            instrumentation.waitForIdleSync();
            WidgetSandboxMessage previewMessage = (WidgetSandboxMessage) field(WidgetInlinePreview.class, "message").get(preview[0]);
            expect("allowing widgets resumes an already shown preview", previewMessage != null);
            previewSession[0] = (WidgetSandboxSession) field(WidgetSandboxMessage.class, "session").get(previewMessage);
            expect("allowed inline preview has a session", previewSession[0] != null);
            instrumentation.runOnMainSync(() -> previewSession[0].setConformanceListener(value -> {
                if (value.startsWith("childgram:")) { previewMarkers.incrementAndGet(); previewRan.countDown(); }
            }));
            expect("allowed inline preview executes harmless JavaScript", previewRan.await(15, TimeUnit.SECONDS));
            long previewHandle = sessionHandle(previewSession[0]);
            expect("allowed inline preview owns a remote client handle", previewHandle != 0
                    && clients(WidgetRemoteEngine.INSTANCE).containsKey(previewHandle));

            instrumentation.runOnMainSync(() -> ChildgramParentalSettings.setBlockWidgets(true));
            instrumentation.waitForIdleSync();
            expect("enabling restriction closes an existing remote session", normal[0].closed() && sessionHandle(normal[0]) == 0);
            expect("enabling restriction closes the live inline preview", previewSession[0].closed() && sessionHandle(previewSession[0]) == 0);
            expect("closed preview returns to its placeholder", field(WidgetInlinePreview.class, "message").get(preview[0]) == null);
            // Drain queued callbacks before checking that closed sessions stop reporting conformance markers.
            SystemClock.sleep(300);
            instrumentation.waitForIdleSync();
            int normalStopped = normalMarkers.get(), previewStopped = previewMarkers.get();
            SystemClock.sleep(600);
            instrumentation.waitForIdleSync();
            expect("blocked normal widget reports no further conformance callbacks", normalMarkers.get() == normalStopped);
            expect("blocked preview reports no further conformance callbacks", previewMarkers.get() == previewStopped);
            long destroyDeadline = SystemClock.elapsedRealtime() + 2000;
            Map<?, ?> remoteClients = clients(WidgetRemoteEngine.INSTANCE);
            while ((remoteClients.containsKey(normalHandle) || remoteClients.containsKey(previewHandle))
                    && SystemClock.elapsedRealtime() < destroyDeadline) SystemClock.sleep(20);
            expect("blocking releases both remote client handles", !remoteClients.containsKey(normalHandle) && !remoteClients.containsKey(previewHandle));
            expect("blocking does not delete preview metadata", WidgetMedia.isPreview(previewUrl[0]));
        } finally {
            try {
                instrumentation.runOnMainSync(() -> {
                    if (preview[0] != null) preview[0].hide();
                    if (normal[0] != null) normal[0].close();
                    if (previewSession[0] != null) previewSession[0].close();
                    ChildgramParentalSettings.removePin();
                });
            } finally {
                WidgetEngine.isolated = originalIsolated;
                WidgetEngine.conformance(originalConformance);
                WidgetEngine.attackScan(originalAttackScan);
                WidgetEngine.limits(originalLimits);
                if (previewUrl[0] != null) ((Map<?, ?>) field(WidgetMedia.class, "entries").get(null)).remove(previewUrl[0]);
                for (int account = 0; account < serverFlags.length; account++) {
                    if (serverFlags[account] != null) configValue.setBoolean(serverFlags[account], originalFlags[account]);
                }
            }
        }
    }

    private void checkPendingStartup(Instrumentation instrumentation) throws Exception {
        // Exercise the real remote binding wait and policy gate, without starting an OS service or any JS.
        Constructor<WidgetRemoteEngine> constructor = WidgetRemoteEngine.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        WidgetRemoteEngine remote = constructor.newInstance();
        PendingConnectionContext context = new PendingConnectionContext(instrumentation.getTargetContext());
        WidgetSandboxSession pending = new WidgetSandboxSession(context, HTML, false, 1);
        field(WidgetSandboxSession.class, "engine").set(pending, remote);
        AtomicReference<Throwable> failure = new AtomicReference<>(), toggleFailure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1), blocked = new CountDownLatch(1);
        Thread starter = new Thread(() -> {
            try { pending.start(320, false); }
            catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        }, "childgram-pending-widget-check");
        starter.setDaemon(true);
        try {
            expect("install pending-widget PIN fixture", ChildgramParentalSettings.setPin("4826"));
            instrumentation.runOnMainSync(() -> ChildgramParentalSettings.setBlockWidgets(false));
            starter.start();
            expect("pending startup requests a binding", context.bound.await(2, TimeUnit.SECONDS));
            Object binding = connection(remote);
            expect("startup waits for the withheld service callback", binding != null
                    && field(binding.getClass(), "engine").get(binding) == null && finished.getCount() == 1);
            AndroidUtilities.runOnUIThread(() -> {
                try { ChildgramParentalSettings.setBlockWidgets(true); }
                catch (Throwable error) { toggleFailure.set(error); }
                finally { blocked.countDown(); }
            });
            expect("parental toggle does not wait for a pending service connection", blocked.await(2, TimeUnit.SECONDS));
            expect("parental toggle succeeds during binding", toggleFailure.get() == null);
            // The toggle queues policy refresh, which queues the kill switch; drain both before connecting.
            instrumentation.waitForIdleSync();
            expect("reblocking closes the pending session before connection", pending.closed() && sessionHandle(pending) == 0);
            instrumentation.runOnMainSync(context::connect);
            expect("pending startup exits after service connection", finished.await(2, TimeUnit.SECONDS));
            expect("reblocked startup is rejected at the final gate", failure.get() instanceof IllegalStateException);
            expect("reblocked startup never calls engine create", context.creates.get() == 0);
            expect("reblocked startup publishes no remote handle", sessionHandle(pending) == 0 && clients(remote).isEmpty());
        } finally {
            // Even a regression holding the policy monitor across bind must not deadlock the UI/test runner.
            // This fake connection has no UI or native work, so failure recovery can release it here directly.
            context.connect();
            starter.join(2000);
            if (starter.isAlive()) {
                starter.interrupt();
                starter.join(2000);
            }
            instrumentation.runOnMainSync(() -> {
                pending.close();
                ChildgramParentalSettings.removePin();
            });
            Object binding = connection(remote);
            if (binding != null) {
                Method drop = WidgetRemoteEngine.class.getDeclaredMethod("drop", binding.getClass());
                drop.setAccessible(true);
                synchronized (field(WidgetRemoteEngine.class, "lock").get(remote)) { drop.invoke(remote, binding); }
            }
        }
    }

    private static final class PendingConnectionContext extends ContextWrapper {
        final CountDownLatch bound = new CountDownLatch(1);
        final AtomicInteger creates = new AtomicInteger();
        final AtomicBoolean connected = new AtomicBoolean();
        final Binder binder = new Binder();
        volatile ServiceConnection callback;

        PendingConnectionContext(Context context) {
            super(context);
            IWidgetEngine engine = (IWidgetEngine) Proxy.newProxyInstance(IWidgetEngine.class.getClassLoader(),
                    new Class<?>[]{IWidgetEngine.class}, (proxy, method, args) -> {
                        if (method.getName().equals("asBinder")) return binder;
                        if (method.getName().equals("create")) { creates.incrementAndGet(); return 1L; }
                        return null;
                    });
            binder.attachInterface(engine, "org.telegram.utils.twidget.IWidgetEngine");
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            callback = connection;
            bound.countDown();
            return true;
        }
        @Override public void unbindService(ServiceConnection connection) { }

        void connect() {
            ServiceConnection connection = callback;
            if (connection != null && connected.compareAndSet(false, true))
                connection.onServiceConnected(new ComponentName(this, WidgetEngineService.class), binder);
        }
    }

    private static Object connection(WidgetRemoteEngine remote) throws Exception {
        synchronized (field(WidgetRemoteEngine.class, "lock").get(remote)) {
            return field(WidgetRemoteEngine.class, "connection").get(remote);
        }
    }

    private static Map<?, ?> clients(WidgetRemoteEngine remote) throws Exception {
        return (Map<?, ?>) field(WidgetRemoteEngine.class, "clients").get(remote);
    }

    private static long sessionHandle(WidgetSandboxSession session) throws Exception {
        return field(WidgetSandboxSession.class, "session").getLong(session);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void expect(String label, boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
