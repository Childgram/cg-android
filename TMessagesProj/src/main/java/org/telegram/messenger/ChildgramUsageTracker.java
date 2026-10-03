package org.telegram.messenger;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.BubbleActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.TopicsFragment;
import org.telegram.ui.ViewPagerActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/** One foreground interval at a time; full-screen video is a subset of that interval. */
public final class ChildgramUsageTracker {
    private static final Set<Activity> resumedActivities = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean foreground;
    private static boolean refreshPending;
    private static Session session;
    private static long lastSaveElapsed;
    private static final Runnable refresh = () -> {
        refreshPending = false;
        update();
    };
    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            if (foreground) AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    private ChildgramUsageTracker() {}

    private static final class Session {
        String id, key, label;
        long owner, utc, elapsed;
        boolean video;
    }

    public static void onForeground(Activity source, boolean active) {
        if (!BuildVars.CHILDGRAM) return;
        if (active && !source.isFinishing() && !source.isDestroyed()) resumedActivities.add(source);
        else resumedActivities.remove(source);
        foreground = !resumedActivities.isEmpty();
        AndroidUtilities.cancelRunOnUIThread(tick);
        update();
        if (foreground) AndroidUtilities.runOnUIThread(tick, 1000);
    }

    /** Coalesce nested fragment lifecycle events until the navigation stack has settled. */
    public static void refreshVisibleScreen() {
        if (!BuildVars.CHILDGRAM || refreshPending) return;
        refreshPending = true;
        AndroidUtilities.runOnUIThread(refresh);
    }

    public static void flush() {
        if (!BuildVars.CHILDGRAM) return;
        update();
        save(SystemClock.elapsedRealtime());
    }

    /** Start a fresh row before pruning so a later checkpoint cannot restore an old interval. */
    public static void splitForCleanup() {
        if (!BuildVars.CHILDGRAM) return;
        save(SystemClock.elapsedRealtime());
        session = null;
        update();
    }

    private static void update() {
        if (!BuildVars.CHILDGRAM) return;
        long elapsed = SystemClock.elapsedRealtime();
        long utc = System.currentTimeMillis();
        Session target = currentScreen();
        boolean same = session != null && target != null && session.owner == target.owner
                && session.key.equals(target.key) && session.video == target.video;
        // A wall-clock correction changes placement on the timeline, never the measured duration.
        boolean clockChanged = session != null && Math.abs(utc - (session.utc + elapsed - session.elapsed)) > 2000;
        if (!same || clockChanged) {
            save(elapsed);
            session = target;
            if (session != null) {
                session.id = UUID.randomUUID().toString();
                session.utc = utc;
                session.elapsed = elapsed;
                lastSaveElapsed = elapsed;
            }
        } else if (elapsed - lastSaveElapsed >= 10000) {
            save(elapsed);
        }
    }

    private static void save(long elapsed) {
        if (session == null) return;
        ChildgramUsageStorage.getInstance(session.owner).save(session.id, session.key, session.label,
                session.utc, session.utc + Math.max(0, elapsed - session.elapsed), session.video);
        lastSaveElapsed = elapsed;
    }

    private static Session currentScreen() {
        if (!foreground || !ApplicationLoader.isScreenOn || SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter) return null;
        BaseFragment fragment = visibleFragment();
        if (fragment == null || fragment.isPaused()) return null;
        Activity activity = fragment.getParentActivity();
        if (activity == null || AndroidUtilities.isInPictureInPictureMode(activity)) return null;
        int account = fragment.getCurrentAccount();
        long owner = UserConfig.getInstance(account).getClientUserId();
        if (owner == 0) return null;
        Session target = new Session();
        target.owner = owner;
        long dialogId = fragment instanceof ChatActivity ? ((ChatActivity) fragment).getDialogId()
                : fragment instanceof TopicsFragment ? ((TopicsFragment) fragment).getDialogId() : 0;
        if (PhotoViewer.hasInstance()) {
            PhotoViewer viewer = PhotoViewer.getInstance();
            MessageObject video = viewer.getChildgramVideoMessage();
            if (video != null && video.currentAccount == account) {
                // Forwarded messages belong to the chat in which they are being watched.
                dialogId = video.getDialogId();
                target.video = viewer.isChildgramVideoPlaying();
            }
        }
        if (dialogId != 0) {
            target.key = "chat:" + dialogId;
            target.label = dialogName(account, dialogId);
        } else {
            String name = fragment.getClass().getSimpleName();
            if (name.equals("DialogsActivity")) target.key = "dialogs";
            else if (name.contains("Settings") || name.contains("Privacy") || name.equals("ThemeActivity")
                    || name.equals("ChildgramUsageActivity") || name.equals("UserInfoActivity")
                    || name.equals("SessionsActivity") || name.equals("DataUsageActivity")) target.key = "settings";
            else if (name.contains("Contacts")) target.key = "contacts";
            else if (name.contains("Call")) target.key = "calls";
            else if (name.equals("ProfileActivity")) target.key = "profile";
            else target.key = "other";
            target.label = target.key;
        }
        return target;
    }

    private static BaseFragment visibleFragment() {
        if (BubbleActivity.instance != null && resumedActivities.contains(BubbleActivity.instance)
                && BubbleActivity.instance.actionBarLayout != null) {
            BaseFragment bubble = top(BubbleActivity.instance.actionBarLayout);
            if (bubble != null && !bubble.isPaused()) return unwrap(bubble);
        }
        LaunchActivity launch = LaunchActivity.instance;
        if (launch == null || !resumedActivities.contains(launch)) return null;
        INavigationLayout main = launch.getActionBarLayout();
        BaseFragment fragment;
        if (main != launch.actionBarLayout && (fragment = top(main)) != null) return unwrap(fragment);
        if ((fragment = top(launch.getLayersActionBarLayout())) != null) return unwrap(fragment);
        if ((fragment = top(launch.getRightActionBarLayout())) != null) return unwrap(fragment);
        return unwrap(top(main));
    }

    private static BaseFragment top(INavigationLayout layout) {
        return layout != null && layout.getView().getVisibility() == View.VISIBLE ? layout.getLastFragment() : null;
    }

    private static BaseFragment unwrap(BaseFragment fragment) {
        while (fragment instanceof ViewPagerActivity) fragment = ((ViewPagerActivity) fragment).getCurrentVisibleFragment();
        return fragment;
    }

    private static String dialogName(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        if (dialogId == UserConfig.getInstance(account).getClientUserId()) return LocaleController.getString(R.string.SavedMessages);
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat chat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (chat != null) return UserObject.getUserName(controller.getUser(chat.user_id));
        } else if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = controller.getUser(dialogId);
            if (user != null) return UserObject.getUserName(user);
        } else {
            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat != null && chat.title != null) return chat.title;
        }
        return Long.toString(dialogId);
    }
}
