package org.telegram.utils.twidget;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;

/** Synthetic, offline native widgets only. Called by the parental suite, which restores its preferences. */
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
            WidgetSandboxNative.conformance(true);
            expect("native widgets are disabled by parental default", WidgetSandboxSession.disabledByConfig());
            normal[0] = new WidgetSandboxSession(instrumentation.getTargetContext(), HTML, false, 1);
            boolean rejected = false;
            try { normal[0].start(320, false); }
            catch (IllegalStateException expected) { rejected = true; }
            expect("default restriction rejects native startup", rejected);
            expect("blocked startup has no native session", nativeSession(normal[0]) == 0);
            expect("blocked startup produced no frame", normal[0].dump() == null);
            instrumentation.runOnMainSync(normal[0]::close);

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
            expect("allowed widget owns a native session", nativeSession(normal[0]) != 0);

            instrumentation.waitForIdleSync();
            WidgetSandboxMessage previewMessage = (WidgetSandboxMessage) field(WidgetInlinePreview.class, "message").get(preview[0]);
            expect("allowing widgets resumes an already shown preview", previewMessage != null);
            previewSession[0] = (WidgetSandboxSession) field(WidgetSandboxMessage.class, "session").get(previewMessage);
            expect("allowed inline preview has a session", previewSession[0] != null);
            instrumentation.runOnMainSync(() -> previewSession[0].setConformanceListener(value -> {
                if (value.startsWith("childgram:")) { previewMarkers.incrementAndGet(); previewRan.countDown(); }
            }));
            expect("allowed inline preview executes harmless JavaScript", previewRan.await(15, TimeUnit.SECONDS));

            instrumentation.runOnMainSync(() -> ChildgramParentalSettings.setBlockWidgets(true));
            instrumentation.waitForIdleSync();
            expect("enabling restriction closes an existing native session", normal[0].closed() && nativeSession(normal[0]) == 0);
            expect("enabling restriction closes the live inline preview", previewSession[0].closed() && nativeSession(previewSession[0]) == 0);
            expect("closed preview returns to its placeholder", field(WidgetInlinePreview.class, "message").get(preview[0]) == null);
            // Let already queued callbacks drain before proving that periodic JS work has stopped.
            SystemClock.sleep(300);
            instrumentation.waitForIdleSync();
            int normalStopped = normalMarkers.get(), previewStopped = previewMarkers.get();
            SystemClock.sleep(600);
            instrumentation.waitForIdleSync();
            expect("blocked normal widget no longer runs periodic JavaScript", normalMarkers.get() == normalStopped);
            expect("blocked preview no longer runs periodic JavaScript", previewMarkers.get() == previewStopped);
            expect("blocking does not delete preview metadata", WidgetMedia.isPreview(previewUrl[0]));
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (preview[0] != null) preview[0].hide();
                if (normal[0] != null) normal[0].close();
                if (previewSession[0] != null) previewSession[0].close();
                ChildgramParentalSettings.removePin();
            });
            WidgetSandboxNative.conformance(false);
            if (previewUrl[0] != null) ((Map<?, ?>) field(WidgetMedia.class, "entries").get(null)).remove(previewUrl[0]);
            for (int account = 0; account < serverFlags.length; account++) {
                if (serverFlags[account] != null) configValue.setBoolean(serverFlags[account], originalFlags[account]);
            }
        }
    }

    private static long nativeSession(WidgetSandboxSession session) throws Exception {
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
