package org.telegram.messenger;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ArchiveSettingsActivity;
import org.telegram.ui.AudioRecorderSettingsActivity;
import org.telegram.ui.BubbleActivity;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChildgramParentalActivity;
import org.telegram.ui.ChildgramParentalPinActivity;
import org.telegram.ui.ChildgramUsageActivity;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DataSettingsActivity;
import org.telegram.ui.DataUsage2Activity;
import org.telegram.ui.DataUsageActivity;
import org.telegram.ui.DebugSettingsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.HeaderSettingsActivity;
import org.telegram.ui.InviteContactsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LiteModeSettingsActivity;
import org.telegram.ui.NotificationsCustomSettingsActivity;
import org.telegram.ui.NotificationsSettingsActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.PrivacyControlActivity;
import org.telegram.ui.PrivacySettingsActivity;
import org.telegram.ui.PrivacyUsersActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.ProfileActivity2;
import org.telegram.ui.ProxySettingsActivity;
import org.telegram.ui.RoundVideoSettingsActivity;
import org.telegram.ui.SaveToGallerySettingsActivity;
import org.telegram.ui.SessionsActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.ThemeActivity;
import org.telegram.ui.TopicsFragment;
import org.telegram.ui.TopicsNotifySettingsFragments;
import org.telegram.ui.UserInfoActivity;
import org.telegram.ui.ViewPagerActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.Premium.GiftPremiumBottomSheet;
import org.telegram.ui.Components.SharedMediaLayout;
import org.telegram.ui.Gifts.AcquiredGiftsSheet;
import org.telegram.ui.Gifts.ActiveAuctionsSheet;
import org.telegram.ui.Gifts.AuctionBidSheet;
import org.telegram.ui.Gifts.AuctionJoinSheet;
import org.telegram.ui.Gifts.AuctionWearingSheet;
import org.telegram.ui.Gifts.GiftMessageBottomSheet;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.Gifts.ProfileGiftsContainer;
import org.telegram.ui.Gifts.ResaleGiftsFragment;
import org.telegram.ui.Gifts.SelectGiftsBottomSheet;
import org.telegram.ui.Gifts.SendGiftSheet;
import org.telegram.ui.Stars.CraftGiftSheet;
import org.telegram.ui.Stars.GiftOfferSheet;
import org.telegram.ui.Stars.SellGiftEnterPriceSheet;
import org.telegram.ui.Stars.StarGiftPreviewSheet;
import org.telegram.ui.Stars.StarGiftSheet;
import org.telegram.ui.Stars.StarsIntroActivity;
import org.telegram.ui.bots.BotBiometrySettings;
import org.telegram.ui.web.WebBrowserSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/** One screen interval plus independently attributed PiP playback, without adding overlap to screen time. */
public final class ChildgramUsageTracker {
    private static final Set<Activity> resumedActivities = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ArrayList<BottomSheet> visibleSheets = new ArrayList<>();
    private static final Rect visibleRect = new Rect();
    private static boolean foreground;
    private static boolean refreshPending;
    private static Session session;
    private static Session pipSession;
    private static final Runnable refresh = () -> {
        refreshPending = false;
        update();
    };
    private static final Runnable tick = ChildgramUsageTracker::update;

    private ChildgramUsageTracker() {}

    private static final class Session {
        String id, key, label;
        long owner, utc, elapsed, lastSaveElapsed;
        boolean video;
        int kind;
    }

    static void onActivityVisibilityChanged(Activity activity, boolean resumed) {
        if (!BuildVars.CHILDGRAM || !(activity instanceof LaunchActivity || activity instanceof BubbleActivity)) return;
        if (resumed && !activity.isFinishing() && !activity.isDestroyed()) {
            resumedActivities.add(activity);
            // The detector runs inside super.onResume(), before Telegram restores its chat.
            refreshVisibleScreen();
        } else {
            resumedActivities.remove(activity);
            update();
        }
    }

    /** Coalesce nested fragment lifecycle events until the navigation stack has settled. */
    public static void refreshVisibleScreen() {
        if (!BuildVars.CHILDGRAM || refreshPending) return;
        refreshPending = true;
        AndroidUtilities.runOnUIThread(refresh);
    }

    /** Covers both dialog windows and sheets attached directly to a fragment. */
    public static void onSheetVisibility(BottomSheet sheet, boolean attached) {
        if (!BuildVars.CHILDGRAM) return;
        visibleSheets.remove(sheet);
        if (attached) visibleSheets.add(sheet);
        refreshVisibleScreen();
    }

    public static void flush() {
        if (!BuildVars.CHILDGRAM) return;
        update();
        long elapsed = SystemClock.elapsedRealtime();
        save(session, elapsed);
        save(pipSession, elapsed);
    }

    /** Start a fresh row before pruning so a later checkpoint cannot restore an old interval. */
    public static void splitForCleanup() {
        if (!BuildVars.CHILDGRAM) return;
        long elapsed = SystemClock.elapsedRealtime();
        save(session, elapsed);
        save(pipSession, elapsed);
        session = null;
        pipSession = null;
        update();
    }

    private static void update() {
        if (!BuildVars.CHILDGRAM) return;
        foreground = false;
        for (Activity activity : resumedActivities) {
            if (!activity.isFinishing() && !activity.isDestroyed()
                    && !AndroidUtilities.isInPictureInPictureMode(activity)) {
                foreground = true;
                break;
            }
        }
        long elapsed = SystemClock.elapsedRealtime();
        long utc = System.currentTimeMillis();
        session = updateSession(session, currentScreen(), utc, elapsed);
        pipSession = updateSession(pipSession, currentPip(), utc, elapsed);
        AndroidUtilities.cancelRunOnUIThread(tick);
        // Paused/hidden PiP still needs to notice screen unlock and the next visible playback.
        if (foreground || PhotoViewer.hasChildgramPipVideo()) AndroidUtilities.runOnUIThread(tick, 1000);
    }

    private static Session updateSession(Session session, Session target, long utc, long elapsed) {
        boolean same = session != null && target != null && session.owner == target.owner
                && session.key.equals(target.key) && session.video == target.video && session.kind == target.kind;
        // A wall-clock correction changes placement on the timeline, never the measured duration.
        boolean clockChanged = session != null && Math.abs(utc - (session.utc + elapsed - session.elapsed)) > 2000;
        if (!same || clockChanged) {
            save(session, elapsed);
            session = target;
            if (session != null) {
                session.id = UUID.randomUUID().toString();
                session.utc = utc;
                session.elapsed = elapsed;
                session.lastSaveElapsed = elapsed;
            }
        } else if (elapsed - session.lastSaveElapsed >= 10000) {
            save(session, elapsed);
        }
        return session;
    }

    private static void save(Session session, long elapsed) {
        if (session == null) return;
        ChildgramUsageStorage.getInstance(session.owner).save(session.id, session.key, session.label,
                session.utc, session.utc + Math.max(0, elapsed - session.elapsed), session.video, session.kind);
        session.lastSaveElapsed = elapsed;
    }

    private static boolean isScreenAvailable() {
        if (!ApplicationLoader.isScreenOn || SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter) return false;
        KeyguardManager keyguard = (KeyguardManager) ApplicationLoader.applicationContext.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard == null || !keyguard.isKeyguardLocked();
    }

    private static Session currentPip() {
        if (!isScreenAvailable()) return null;
        MessageObject video = PhotoViewer.getChildgramPipVideoMessage();
        if (video == null) return null;
        long owner = UserConfig.getInstance(video.currentAccount).getClientUserId();
        long dialogId = video.getDialogId();
        if (owner == 0 || dialogId == 0) return null;
        Session target = new Session();
        target.owner = owner;
        target.key = "chat:" + dialogId;
        target.label = dialogName(video.currentAccount, dialogId);
        target.video = true;
        target.kind = foreground ? ChildgramUsageStorage.PIP_INSIDE : ChildgramUsageStorage.PIP_OUTSIDE;
        return target;
    }

    private static Session currentScreen() {
        if (!foreground || !isScreenAvailable()) return null;
        BaseFragment fragment = visibleFragment();
        if (fragment == null || fragment.isPaused()) return null;
        Activity activity = fragment.getParentActivity();
        if (activity == null || !resumedActivities.contains(activity)
                || AndroidUtilities.isInPictureInPictureMode(activity)) return null;
        BottomSheet giftSheet = visibleGiftSheet(activity);
        String key = giftSheet != null ? "gifts" : screenKey(fragment);
        int account = giftSheet != null ? giftSheet.getCurrentAccount() : fragment.getCurrentAccount();
        long owner = UserConfig.getInstance(account).getClientUserId();
        if (owner == 0) return null;
        Session target = new Session();
        target.owner = owner;
        if (key.equals("gifts")) {
            target.key = target.label = key;
            return target;
        }
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
            target.key = key;
            target.label = target.key;
        }
        return target;
    }

    private static String screenKey(BaseFragment fragment) {
        if (fragment instanceof ResaleGiftsFragment) return "gifts";
        if (fragment instanceof ProfileActivity) {
            SharedMediaLayout media = ((ProfileActivity) fragment).sharedMediaLayout;
            if (media != null && media.getClosestTab() == SharedMediaLayout.TAB_GIFTS
                    && isVisible(media.giftsContainer)) return "gifts";
            return "profile";
        }
        if (fragment instanceof ProfileActivity2) return "profile";
        if (fragment instanceof DialogsActivity) return "dialogs";
        if (fragment instanceof SettingsActivity || fragment instanceof PrivacySettingsActivity
                || fragment instanceof PrivacyControlActivity || fragment instanceof PrivacyUsersActivity
                || fragment instanceof NotificationsSettingsActivity || fragment instanceof NotificationsCustomSettingsActivity
                || fragment instanceof DataSettingsActivity || fragment instanceof DataUsageActivity
                || fragment instanceof DataUsage2Activity || fragment instanceof LiteModeSettingsActivity
                || fragment instanceof ArchiveSettingsActivity || fragment instanceof SaveToGallerySettingsActivity
                || fragment instanceof TopicsNotifySettingsFragments || fragment instanceof ProxySettingsActivity
                || fragment instanceof RoundVideoSettingsActivity || fragment instanceof AudioRecorderSettingsActivity
                || fragment instanceof HeaderSettingsActivity || fragment instanceof DebugSettingsActivity
                || fragment instanceof ThemeActivity || fragment instanceof UserInfoActivity
                || fragment instanceof SessionsActivity || fragment instanceof ChildgramUsageActivity
                || fragment instanceof ChildgramParentalActivity || fragment instanceof ChildgramParentalPinActivity
                || fragment instanceof BotBiometrySettings || fragment instanceof WebBrowserSettings) return "settings";
        if (fragment instanceof ContactsActivity || fragment instanceof InviteContactsActivity) return "contacts";
        if (fragment instanceof CallLogActivity) return "calls";
        return "other";
    }

    private static boolean isGiftSheet(BottomSheet sheet) {
        return sheet instanceof GiftSheet || sheet instanceof StarGiftSheet || sheet instanceof StarGiftPreviewSheet
                || sheet instanceof SendGiftSheet || sheet instanceof GiftMessageBottomSheet
                || sheet instanceof SelectGiftsBottomSheet || sheet instanceof ResaleGiftsFragment.SelectGiftSheet
                || sheet instanceof GiftOfferSheet || sheet instanceof SellGiftEnterPriceSheet
                || sheet instanceof CraftGiftSheet || sheet instanceof ProfileGiftsContainer.UnpinSheet
                || sheet instanceof ActiveAuctionsSheet || sheet instanceof AuctionJoinSheet
                || sheet instanceof AuctionBidSheet || sheet instanceof AuctionWearingSheet
                || sheet instanceof AcquiredGiftsSheet || sheet instanceof GiftPremiumBottomSheet
                || sheet instanceof StarsIntroActivity.GiftStarsSheet;
    }

    private static BottomSheet visibleGiftSheet(Activity activity) {
        if (activity == null || !resumedActivities.contains(activity) || activity.isFinishing() || activity.isDestroyed()) return null;
        for (int i = visibleSheets.size() - 1; i >= 0; i--) {
            BottomSheet sheet = visibleSheets.get(i);
            if (!sheet.container.isAttachedToWindow()) {
                visibleSheets.remove(i);
            } else if (AndroidUtilities.findActivity(sheet.getContext()) == activity && isVisible(sheet.container)) {
                // A balance or settings sheet can cover a gift without closing it.
                return isGiftSheet(sheet) ? sheet : null;
            }
        }
        return null;
    }

    private static boolean isVisible(View view) {
        return view != null && view.isAttachedToWindow() && view.isShown() && view.getAlpha() > 0
                && view.getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty();
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
