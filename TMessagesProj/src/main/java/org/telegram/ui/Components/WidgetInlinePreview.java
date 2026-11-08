package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChildgramParentalSettings;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.utils.twidget.WidgetMedia;
import org.telegram.utils.twidget.WidgetSandboxMessage;
import org.telegram.utils.twidget.WidgetSandboxSession;
import org.telegram.utils.twidget.WidgetSandboxTheme;

/**
 * A widget result in a list of inline results (botInlineMessageWidget), shown as a preview (widget-sandbox
 * docs/sdk-v1.md §2.16): drawn on the outgoing bubble's colour it will be sent in, no touches routed to it (a tap
 * selects the result), no requests. The view that shows it attaches and detaches it.
 */
public final class WidgetInlinePreview implements WidgetSandboxPlayer.Host {
    /** At most this tall in the list (dp); a taller widget is clipped, as a bubble clips one at 600. */
    private static final int MAX_HEIGHT_DP = 320;

    private final View view;
    private final String url;
    private WidgetSandboxMessage message;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private WidgetSandboxPlayer player;
    private int widthPx;
    private boolean shown;
    private boolean running;
    private Text blockedText;

    public static boolean supports(TLRPC.BotInlineResult result) {
        return result != null && result.send_message instanceof TLRPC.TL_botInlineMessageWidget;
    }

    public WidgetInlinePreview(View view, int account, TLRPC.BotInlineResult result, Theme.ResourcesProvider resourcesProvider) {
        this.view = view;
        this.resourcesProvider = resourcesProvider;
        url = WidgetMedia.previewUrl(account, result);
        message = WidgetMedia.find(url);
    }

    /** The preview's height in px for a widget `widthPx` wide. */
    public int heightPx(int widthPx) {
        int height = WidgetMedia.contentHeightPx(url, message, widthPx);
        return Math.min(height, AndroidUtilities.dp(MAX_HEIGHT_DP));
    }

    /** UI: the view shows the preview at this width: the widget runs, laid out for it. */
    public void show(int widthPx) {
        if (widthPx <= 0) return;
        this.widthPx = widthPx;
        if (!shown) {
            shown = true;
            WidgetMedia.showInlinePreview(this);
        }
        if (WidgetSandboxSession.disabledByConfig()) return;
        if (message == null) message = WidgetMedia.find(url);
        if (message == null) return;
        message.setWidthPx(widthPx);
        if (running) return;
        running = true;
        message.setTheme(WidgetSandboxTheme.build(resourcesProvider, true));
        if (player == null) player = new WidgetSandboxPlayer(this, AndroidUtilities.density);
        player.bind(message);
        player.setVisible(true);
        WidgetMedia.onShown(message);
    }

    /** UI: the view no longer shows it. */
    public void hide() {
        if (!shown) return;
        shown = false;
        WidgetMedia.hideInlinePreview(this);
        stop();
    }

    private void stop() {
        if (!running) return;
        running = false;
        player.setVisible(false);
        player.unbind();
        WidgetMedia.onHidden(message);
    }

    public void onWidgetPolicyChanged() {
        stop();
        message = null;
        if (shown) show(widthPx);
        view.requestLayout();
        view.invalidate();
    }

    public void draw(Canvas canvas, float x, float y) {
        if (widthPx <= 0) return;
        int height = heightPx(widthPx);
        // The bubble the result will be sent in has the chat's radius.
        float radius = AndroidUtilities.dp(SharedConfig.bubbleRadius);
        canvas.save();
        canvas.translate(x, y);
        clip.rewind();
        AndroidUtilities.rectTmp.set(0, 0, widthPx, height);
        clip.addRoundRect(AndroidUtilities.rectTmp, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        background.setColor(Theme.getColor(Theme.key_chat_outBubble, resourcesProvider) | 0xff000000);
        canvas.drawRect(AndroidUtilities.rectTmp, background);
        if (ChildgramParentalSettings.blockWidgets()) {
            if (blockedText == null) blockedText = new Text(LocaleController.getString(R.string.ChildgramParentalWidgetsBlocked), 14).multiline(3);
            blockedText.setMaxWidth(Math.max(AndroidUtilities.dp(1), widthPx - AndroidUtilities.dp(24)));
            blockedText.draw(canvas, AndroidUtilities.dp(12), AndroidUtilities.dp(12), Theme.getColor(Theme.key_chat_messageTextOut, resourcesProvider), 1f);
        } else if (player != null && player.draw(canvas, widthPx)) view.postInvalidateOnAnimation();
        canvas.restore();
    }

    @Override
    public void onWidgetResized() {
        view.requestLayout();
        view.invalidate();
    }

    @Override
    public void invalidateWidget() {
        view.invalidate();
    }

    @Override
    public void disallowParentIntercept(boolean disallow) {
    }
}
