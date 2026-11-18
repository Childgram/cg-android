package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import org.telegram.messenger.voip.VoIPService;
import org.telegram.messenger.voip.VoIPServiceState;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.LaunchActivity;

public final class ChildgramBreakCalls {
    private static boolean observing;

    private ChildgramBreakCalls() {}

    static void observe() {
        if (observing) return;
        observing = true;
        NotificationCenter.NotificationCenterDelegate listener = (id, account, args) -> ChildgramUsageTracker.refreshVisibleScreen();
        NotificationCenter center = NotificationCenter.getGlobalInstance();
        center.addObserver(listener, NotificationCenter.didStartedCall);
        center.addObserver(listener, NotificationCenter.didEndCall);
    }

    public static boolean isCallActive() {
        VoIPServiceState state = VoIPService.getSharedState();
        return state != null && state.getCallState() != VoIPService.STATE_ENDED
                && state.getCallState() != VoIPService.STATE_FAILED;
    }

    static boolean isCallScreen(BaseFragment fragment) {
        return fragment instanceof CallLogActivity || fragment instanceof GroupCreateActivity
                && fragment.getArguments() != null && fragment.getArguments().getBoolean("isCall");
    }

    public static void showDialer(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Intent intent = new Intent(activity, LaunchActivity.class);
        if (isCallActive()) {
            VoIPService service = VoIPService.getSharedInstance();
            if (service != null) intent.putExtra("currentAccount", service.getAccount());
            intent.setAction(service != null && service.groupCall != null ? "voip_chat" : "voip");
        } else {
            intent.setAction(Intent.ACTION_VIEW).setData(Uri.parse("tg://calllog"));
        }
        activity.startActivity(intent);
    }
}
