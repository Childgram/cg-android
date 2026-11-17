package org.telegram.messenger;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.BottomSheetTabsOverlay;
import org.telegram.ui.BubbleActivity;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChildgramParentalActivity;
import org.telegram.ui.ChildgramParentalPinActivity;
import org.telegram.ui.ChildgramUsageActivity;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Stars.CraftGiftSheet;
import org.telegram.ui.bots.BotWebViewAttachedSheet;
import org.telegram.ui.bots.BotWebViewSheet;
import org.telegram.ui.bots.WebViewRequestProps;
import org.telegram.messenger.browser.Browser;
import org.telegram.utils.pip.PipContent;
import org.telegram.utils.pip.PipCoordinator;
import org.telegram.utils.pip.PipSession;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs against the installed app with synthetic peers and optional local usage UI fixtures. */
public final class ChildgramAccessInstrumentation extends Instrumentation {
    private int assertions;
    private String failure;
    private boolean verifyPrivacy;
    private boolean verifyUsage;
    private boolean verifyUsageUi;
    private boolean verifyBubble;
    private final Bundle bubbleTimes = new Bundle();
    private boolean verifyPip;
    private String pipMode;
    private boolean usageOnly;
    private ChildgramUsageStorageInstrumentation.Result usageResult;
    private int exceptionCount = -1;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        verifyPrivacy = arguments != null && "true".equals(arguments.getString("verify_privacy"));
        usageOnly = arguments != null && "true".equals(arguments.getString("usage_only"));
        verifyUsage = usageOnly || arguments != null && "true".equals(arguments.getString("verify_usage"));
        verifyUsageUi = arguments != null && "true".equals(arguments.getString("verify_usage_ui"));
        verifyBubble = arguments != null && "true".equals(arguments.getString("verify_bubble"));
        verifyPip = arguments != null && "true".equals(arguments.getString("verify_pip"));
        pipMode = arguments == null ? null : arguments.getString("pip_mode");
        start();
    }

    @Override
    public void onStart() {
        runOnMainSync(() -> {
            try {
                ApplicationLoader.postInitApplication();
                checkUsageScreens();
                if (!usageOnly) runChecks();
            } catch (Throwable error) {
                // Exception messages can contain user data. Report only the type or assertion label.
                failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName();
            }
        });
        if (failure == null && !usageOnly) {
            try { checkResolvedLinks(); }
            catch (Throwable error) { failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName(); }
        }
        if (failure == null && verifyUsageUi) {
            try { checkUsageWindows(); }
            catch (Throwable error) { failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName(); }
        }
        if (failure == null && !usageOnly) {
            try { checkWebViewWindows(); }
            catch (Throwable error) { failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName(); }
        }
        if (failure == null && verifyBubble) {
            try { checkBubbleUsage(); }
            catch (Throwable error) { failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName(); }
        }
        if (failure == null && verifyPip) {
            try { assertions += ChildgramPipChecks.run(this, pipMode); }
            catch (Throwable error) { failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName(); }
        }
        if (failure == null && verifyUsage) {
            try {
                usageResult = ChildgramUsageStorageInstrumentation.run();
                assertions += usageResult.assertions;
            } catch (Throwable error) { failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName(); }
        }
        if (failure == null && verifyPrivacy) {
            try { verifyPrivacy(); }
            catch (Throwable error) { failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName(); }
        }
        Bundle report = new Bundle(bubbleTimes);
        if (usageResult != null) {
            report.putInt("usageAssertions", usageResult.assertions);
            report.putLong("usageBytesBefore", usageResult.bytesBefore);
            report.putLong("usageBytesAfter", usageResult.bytesAfter);
        }
        if (exceptionCount >= 0) {
            report.putBoolean("nobody", failure == null);
            report.putInt("exceptionCount", exceptionCount);
        }
        report.putString("status", failure == null ? "passed" : "failed");
        report.putInt("assertions", assertions);
        if (failure != null) report.putString("failure", failure);
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, report);
    }

    private void verifyPrivacy() throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<TLObject> result = new AtomicReference<>();
        int account = UserConfig.selectedAccount;
        TL_account.getPrivacy request = new TL_account.getPrivacy();
        request.key = new TLRPC.TL_inputPrivacyKeyChatInvite();
        int requestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            result.set(response);
            ready.countDown();
        });
        if (!ready.await(30, TimeUnit.SECONDS)) {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
            throw new AssertionError("privacy read timed out");
        }
        expect("privacy read succeeded", result.get() instanceof TL_account.privacyRules);
        boolean nobody = false, otherBase = false;
        exceptionCount = 0;
        for (TLRPC.PrivacyRule rule : ((TL_account.privacyRules) result.get()).rules) {
            nobody |= rule instanceof TLRPC.TL_privacyValueDisallowAll;
            otherBase |= rule instanceof TLRPC.TL_privacyValueAllowAll || rule instanceof TLRPC.TL_privacyValueAllowContacts;
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers) exceptionCount += ((TLRPC.TL_privacyValueAllowUsers) rule).users.size();
            else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) exceptionCount += ((TLRPC.TL_privacyValueDisallowUsers) rule).users.size();
            else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) exceptionCount += ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats.size();
            else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) exceptionCount += ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats.size();
            else if (!(rule instanceof TLRPC.TL_privacyValueDisallowAll || rule instanceof TLRPC.TL_privacyValueAllowAll || rule instanceof TLRPC.TL_privacyValueAllowContacts)) exceptionCount++;
        }
        expect("invite base is nobody", nobody && !otherBase);
    }

    private void expect(String label, boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private void checkResolvedLinks() throws InterruptedException {
        int account = UserConfig.selectedAccount;
        long id = 8_200_000_000_000L + (System.nanoTime() & 0xfffffff);
        ArrayList<Long> opened = new ArrayList<>();
        CountDownLatch denied = new CountDownLatch(1);
        runOnMainSync(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            UserNameResolver resolver = controller.getUserNameResolver();
            TLRPC.User peer = new TLRPC.TL_user(), renamed = new TLRPC.TL_user();
            peer.id = id;
            peer.username = "childgram_fixture_" + id;
            renamed.username = peer.username + "_old";
            resolver.update(renamed, peer);

            Utilities.Callback<Long> link = ChildgramAccess.guardResolvedLink(account, null, denied::countDown, opened::add);
            link.run(null);
            link.run(Long.MAX_VALUE);
            resolver.resolve(peer.username, link);
            Browser.Progress progress = new Browser.Progress();
            resolver.resolve(peer.username, ChildgramAccess.guardResolvedLink(account, progress, denied::countDown, opened::add));
            progress.cancel();
            // Reassign the name before the posted access checks grant the original peer.
            TLRPC.User replacement = new TLRPC.TL_user();
            replacement.id = id + 1;
            replacement.username = peer.username;
            resolver.update(renamed, replacement);
            controller.putUser(peer, true);
            link.run(-id); // Unknown synthetic chat, never sent to Telegram.
        });
        expect("unresolved chat finishes loading on denial", denied.await(10, TimeUnit.SECONDS));
        runOnMainSync(() -> {});
        expect("resolver errors pass through and only the uncancelled original peer opens once",
                opened.equals(Arrays.asList(null, Long.MAX_VALUE, id)));
    }

    private enum WebViewAction { OPEN, DISMISS, RELEASE, SWITCH, DISMISS_AND_SWITCH, SWITCH_BACK }

    private void checkWebViewWindows() throws Exception {
        if (LaunchActivity.instance == null) {
            startActivitySync(new Intent(getTargetContext(), LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        Method currentFragment = LaunchActivity.class.getDeclaredMethod("getSafeLastFragment");
        BaseFragment[] source = new BaseFragment[1];
        awaitUi("mini app has a foreground source", () -> {
            source[0] = (BaseFragment) currentFragment.invoke(null);
            return !ApplicationLoader.mainInterfacePaused && source[0] != null && source[0].getLayoutContainer() != null;
        });
        for (boolean attached : new boolean[] {false, true}) {
            for (WebViewAction action : WebViewAction.values()) {
                checkWebViewWindow(source[0], attached, action);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void checkWebViewWindow(BaseFragment source, boolean attached, WebViewAction action) throws Exception {
        int account = UserConfig.selectedAccount;
        int other = (account + 1) % UserConfig.MAX_ACCOUNT_COUNT;
        String label = (attached ? "attached " : "dialog ") + action;
        BottomSheetTabsOverlay.Sheet[] sheet = new BottomSheetTabsOverlay.Sheet[1];
        int[] showCalls = {0};
        WebViewRequestProps props = new WebViewRequestProps();
        TLRPC.User bot = new TLRPC.TL_user();
        bot.id = 8_300_000_000_000L + (System.nanoTime() & 0xfffffff);
        bot.first_name = "Web view fixture";
        bot.bot = true;
        ChildgramAccess policy = ChildgramAccess.getInstance(account);
        Map<Long, Long> grants = (Map<Long, Long>) fieldValue(policy, "botChecks");
        try {
            onMainChecked(() -> {
                Activity activity = source.getParentActivity();
                MessagesController.getInstance(account).putUser(bot, true);
                expect(label + " starts without permission", !policy.isAllowed(bot.id));
                props.currentAccount = account;
                props.botId = props.peerId = bot.id;
                props.applyResponse(new TLRPC.TL_webViewResultUrl()); // No remote URL or session to poll.
                if (attached) {
                    sheet[0] = new BotWebViewAttachedSheet(source) {
                        @Override public void show() { showCalls[0]++; super.show(); }
                    };
                } else {
                    sheet[0] = new BotWebViewSheet(activity, source.getResourceProvider()) {
                        @Override public void show() { showCalls[0]++; super.show(); }
                    };
                }
                View view = (View) sheet[0].getWindowView();
                if (!attached) ((BotWebViewSheet) sheet[0]).setParentActivity(activity);
                requestWebView(sheet[0], source, props);
                sheet[0].show();
                expect(label + " waits for access", !view.isAttachedToWindow() && showCalls[0] == 1);
                if (action == WebViewAction.DISMISS || action == WebViewAction.DISMISS_AND_SWITCH) sheet[0].dismiss(false);
                if (action == WebViewAction.RELEASE) sheet[0].release();
                if (action == WebViewAction.SWITCH || action == WebViewAction.DISMISS_AND_SWITCH || action == WebViewAction.SWITCH_BACK) {
                    UserConfig.setSelectedAccount(other);
                    if (action == WebViewAction.SWITCH_BACK) UserConfig.setSelectedAccount(account);
                }
                // The real posted check sees the same in-memory grant as a successful bot permission response.
                grants.put(bot.id, SystemClock.elapsedRealtime());
            });
            waitForIdleSync();
            if (action == WebViewAction.OPEN) {
                awaitUi(label + " appears", () -> ((View) sheet[0].getWindowView()).isAttachedToWindow());
            }
            onMainChecked(() -> {
                View view = (View) sheet[0].getWindowView();
                expect(label + " received a valid grant", policy.isAllowed(bot.id));
                boolean open = action == WebViewAction.OPEN;
                expect(label + " attaches only the live window", showCalls[0] == (open ? 2 : 1) && view.isAttachedToWindow() == open);
                if (open) {
                    grants.remove(bot.id);
                    requestWebView(sheet[0], source, props);
                    sheet[0].dismiss(false);
                    UserConfig.setSelectedAccount(other);
                    UserConfig.setSelectedAccount(account);
                    grants.put(bot.id, SystemClock.elapsedRealtime());
                }
            });
            if (action == WebViewAction.OPEN) {
                awaitUi(label + " closes", () -> !((View) sheet[0].getWindowView()).isAttachedToWindow());
                expect(label + " stays closed after switching back", showCalls[0] == 2);
            }
        } finally {
            onMainChecked(() -> {
                if (UserConfig.selectedAccount != account) UserConfig.setSelectedAccount(account);
                if (sheet[0] != null) sheet[0].dismiss(false);
                grants.remove(bot.id);
            });
        }
    }

    private static void requestWebView(BottomSheetTabsOverlay.Sheet sheet, BaseFragment source, WebViewRequestProps props) {
        if (sheet instanceof BotWebViewSheet) ((BotWebViewSheet) sheet).requestWebView(source, props);
        else ((BotWebViewAttachedSheet) sheet).requestWebView(source, props);
    }

    private void runChecks() throws Exception {
        expect("childgram build required", BuildVars.CHILDGRAM);
        int account = UserConfig.selectedAccount;
        expect("activated test account required", UserConfig.getInstance(account).isClientActivated());
        checkVideoUsage(account);
        MessagesController controller = MessagesController.getInstance(account);
        expect("childgram disables embedded browser preference", !controller.isWebBrowserInAppEnabled());
        expect("childgram ignores embedded browser exceptions", !controller.isWebBrowserOpenInApp("https://example.org/test"));
        expect("channel preview is an internal destination", Browser.isInternalUrl("https://t.me/s/childgram_example", null));
        expect("tg destination stays internal", Browser.isInternalUrl("tg://resolve?domain=childgram_example", null));
        expect("ordinary telegram blog uses external browser", !Browser.isInternalUrl("https://telegram.org/blog/example", null));
        expect("Android intent must be unpacked before routing", !Browser.isInternalUrl("intent://t.me/s/childgram_example#Intent;scheme=https;end", null));
        RecordingContext browserContext = new RecordingContext(getTargetContext());
        expect("ordinary website opens externally", Browser.openInExternalApp(browserContext, "https://example.org/test", false));
        expect("website uses a browser selector", browserContext.launched != null && browserContext.launched.getSelector() != null
                && browserContext.launched.getSelector().hasCategory(Intent.CATEGORY_APP_BROWSER));
        browserContext.launched = null;
        expect("website intent target remains usable", Browser.openInExternalApp(browserContext,
                "intent://example.org/test#Intent;scheme=https;package=org.telegram.messenger;end", true));
        expect("intent target cannot choose another Telegram client", browserContext.launched != null
                && "https://example.org/test".equals(browserContext.launched.getDataString())
                && browserContext.launched.getPackage() == null && browserContext.launched.getComponent() == null
                && browserContext.launched.getSelector() != null);
        ChildgramAccess policy = ChildgramAccess.getInstance(account);
        long fixture = 8_000_000_000_000L + (System.nanoTime() & 0xfffffff);

        TLRPC.TL_user human = new TLRPC.TL_user();
        human.id = fixture;
        human.first_name = "Access test";
        controller.putUser(human, true);
        expect("ordinary user allowed", policy.isAllowed(human.id));

        TLRPC.TL_user bot = new TLRPC.TL_user();
        bot.id = fixture + 1;
        bot.bot = true;
        bot.first_name = "Access test bot";
        controller.putUser(bot, true);
        expect("unconfirmed bot denied", !policy.isAllowed(bot.id));
        expect("missing peer denied", !policy.isAllowed(fixture + 2));

        TLRPC.TL_channel channel = new TLRPC.TL_channel();
        channel.id = fixture + 3;
        channel.broadcast = true;
        channel.title = "Access test channel";
        controller.putChat(channel, true);
        expect("member channel allowed", policy.isAllowed(-channel.id));
        channel.min = true;
        expect("minimal peer never proves membership", !policy.isAllowed(-channel.id));
        channel.min = false;
        channel.left = true;
        expect("left channel denied", !policy.isAllowed(-channel.id));
        channel.left = false;
        channel.kicked = true;
        expect("kicked channel denied", !policy.isAllowed(-channel.id));
        channel.kicked = false;
        channel.deactivated = true;
        expect("deactivated channel denied", !policy.isAllowed(-channel.id));
        channel.deactivated = false;
        channel.creator = true;
        expect("own channel allowed", policy.isAllowed(-channel.id));

        TLRPC.TL_channel discussion = new TLRPC.TL_channel();
        discussion.id = fixture + 4;
        discussion.megagroup = true;
        discussion.left = true;
        discussion.title = "Access test discussion";
        controller.putChat(discussion, true);
        expect("unjoined discussion denied", !policy.isAllowed(-discussion.id));

        Bundle mixed = new Bundle();
        mixed.putLong("user_id", human.id);
        mixed.putLong("chat_id", discussion.id);
        mixed.putInt("enc_id", 42);
        ChatActivity mixedChat = new ChatActivity(mixed);
        mixedChat.setCurrentAccount(account);
        expect("chat argument precedence cannot bypass policy", !policy.isFragmentAllowed(mixedChat));
        ProfileActivity mixedProfile = new ProfileActivity(mixed);
        mixedProfile.setCurrentAccount(account);
        expect("profile preserves ordinary user precedence", policy.isFragmentAllowed(mixedProfile));

        MessageObject root = comment(account, discussion.id, channel.id, 10, 20);
        MessageObject album = comment(account, discussion.id, channel.id, 9, 19);
        ArrayList<MessageObject> thread = new ArrayList<>();
        thread.add(album);
        thread.add(root);
        expect("resolved comment context allowed", policy.commentAccess(channel, 10, thread) != null);
        expect("requested post binding required", policy.commentAccess(channel, 11, thread) == null);
        expect("comment send remains scoped", policy.canSend(-discussion.id, root));
        expect("general discussion remains denied", !policy.canSend(-discussion.id, null));
        expect("other thread denied", !policy.canSend(-discussion.id, comment(account, discussion.id, channel.id, 10, 21)));

        expect("local media preview allowed", policy.canViewMessage(null));
        expect("resolved discussion root media allowed", policy.canViewMessage(root));
        expect("other server-verified album media allowed", policy.canViewMessage(album));
        MessageObject reply = comment(account, discussion.id, channel.id, 10, 22);
        reply.messageOwner.reply_to = new TLRPC.TL_messageReplyHeader();
        reply.messageOwner.reply_to.reply_to_top_id = root.getId();
        expect("resolved thread media allowed", policy.canViewMessage(reply));
        reply.messageOwner.reply_to.reply_to_top_id++;
        expect("other discussion thread media denied", !policy.canViewMessage(reply));
        reply.messageOwner.reply_to.reply_to_top_id = root.getId();
        reply.messageOwner.reply_to.reply_to_peer_id = new TLRPC.TL_peerChannel();
        reply.messageOwner.reply_to.reply_to_peer_id.channel_id = channel.id;
        expect("cross-peer reply cannot inherit comment access", !policy.canViewMessage(reply));
        expect("forward attribution cannot grant source access", !policy.canViewMessage(comment(account, discussion.id, channel.id, 10, 23)));
        MessageObject forwarded = comment(account, discussion.id, discussion.id, 12, 24);
        forwarded.messageOwner.peer_id = new TLRPC.TL_peerUser();
        forwarded.messageOwner.peer_id.user_id = human.id;
        forwarded.messageOwner.dialog_id = human.id;
        expect("forwarded copy in ordinary chat remains allowed", policy.canViewMessage(forwarded));
        forwarded.currentAccount = (account + 1) % UserConfig.MAX_ACCOUNT_COUNT;
        expect("message from another account denied", !policy.canViewMessage(forwarded));

        Bundle args = new Bundle();
        args.putLong("chat_id", discussion.id);
        ChatActivity comments = new ChatActivity(args);
        comments.setCurrentAccount(account);
        comments.setThreadMessages(thread, channel, 10, 0, 0, null);
        expect("comment screen allowed without group membership", policy.isFragmentAllowed(comments));
        comments.getArguments().putLong("chat_id", fixture + 5);
        expect("comment capability cannot open another group", !policy.isFragmentAllowed(comments));
        comments.getArguments().putLong("chat_id", discussion.id);
        channel.left = true;
        expect("leaving parent revokes comments", !policy.isFragmentAllowed(comments));
        expect("leaving parent revokes comment sending", !policy.canSend(-discussion.id, root));
        expect("leaving parent revokes comment media", !policy.canViewMessage(root));
    }

    private void checkUsageScreens() throws Exception {
        Method screenKey = ChildgramUsageTracker.class.getDeclaredMethod("screenKey", BaseFragment.class);
        screenKey.setAccessible(true);
        // Constructors only: no navigation, onFragmentCreate, account requests or real usage intervals.
        ChildgramUsageActivity usage = new ChildgramUsageActivity();
        Object[][] screens = {
                {new DialogsActivity(new Bundle()), "dialogs"},
                {new SettingsActivity(), "settings"},
                {new ContactsActivity(new Bundle()), "contacts"},
                {new CallLogActivity(), "calls"},
                {new ProfileActivity(new Bundle()), "profile"},
                {usage, "settings"},
                {new ChildgramParentalActivity(), "settings"},
                {new ChildgramParentalPinActivity(false, null), "settings"},
                {new SettingsActivity() {}, "settings"},
                {new BaseFragment() {}, "other"}
        };
        for (int i = 0; i < screens.length; i++)
            expect("screen category is stable for fixture " + i, screens[i][1].equals(screenKey.invoke(null, screens[i][0])));

        Method giftType = ChildgramUsageTracker.class.getDeclaredMethod("isGiftSheet", BottomSheet.class);
        giftType.setAccessible(true);
        Method visibleGift = ChildgramUsageTracker.class.getDeclaredMethod("visibleGiftSheet", Activity.class);
        visibleGift.setAccessible(true);
        Field sheetsField = ChildgramUsageTracker.class.getDeclaredField("visibleSheets");
        sheetsField.setAccessible(true);
        List<?> sheets = (List<?>) sheetsField.get(null);
        int originalCount = sheets.size();
        // CraftGiftSheet has no network-backed catalogue constructor. Do not show these fixture windows.
        BottomSheet first = new CraftGiftSheet(getTargetContext());
        BottomSheet second = new CraftGiftSheet(getTargetContext()) {};
        BottomSheet ordinary = new BottomSheet(getTargetContext(), false);
        try {
            expect("craft gift window is classified as gifts", Boolean.TRUE.equals(giftType.invoke(null, first)));
            expect("anonymous gift window retains its category", Boolean.TRUE.equals(giftType.invoke(null, second)));
            expect("ordinary window is not a gift", Boolean.FALSE.equals(giftType.invoke(null, ordinary)));
            ChildgramUsageTracker.onSheetVisibility(ordinary, true);
            expect("ordinary windows participate in overlay ordering", sheets.size() == originalCount + 1
                    && sheets.get(sheets.size() - 1) == ordinary);
            ChildgramUsageTracker.onSheetVisibility(ordinary, false);
            ChildgramUsageTracker.onSheetVisibility(first, true);
            ChildgramUsageTracker.onSheetVisibility(first, true);
            expect("duplicate attachment does not duplicate a gift window", sheets.size() == originalCount + 1);
            ChildgramUsageTracker.onSheetVisibility(second, true);
            expect("nested gift is tracked above its parent", sheets.size() == originalCount + 2 && sheets.get(sheets.size() - 1) == second);
            expect("gift windows do not count for an activity that is not resumed", visibleGift.invoke(null, new Activity()) == null);
            ChildgramUsageTracker.onSheetVisibility(ordinary, true);
            expect("ordinary overlay sits above retained gift windows", sheets.size() == originalCount + 3
                    && sheets.get(sheets.size() - 1) == ordinary && sheets.contains(first) && sheets.contains(second));
            ChildgramUsageTracker.onSheetVisibility(ordinary, false);
            expect("closing ordinary overlay restores the top gift", sheets.size() == originalCount + 2
                    && sheets.get(sheets.size() - 1) == second);
            ChildgramUsageTracker.onSheetVisibility(second, false);
            expect("closing nested gift restores its parent", sheets.size() == originalCount + 1 && sheets.get(sheets.size() - 1) == first);
            ChildgramUsageTracker.onSheetVisibility(second, true);
            ChildgramUsageTracker.onSheetVisibility(first, false);
            expect("detaching older gift preserves the nested gift", !sheets.contains(first) && sheets.get(sheets.size() - 1) == second);
        } finally {
            ChildgramUsageTracker.onSheetVisibility(first, false);
            ChildgramUsageTracker.onSheetVisibility(second, false);
            ChildgramUsageTracker.onSheetVisibility(ordinary, false);
        }
        expect("gift fixture leaves existing tracking unchanged", sheets.size() == originalCount);

        Method label = ChildgramUsageActivity.class.getDeclaredMethod("label", ChildgramUsageStorage.Entry.class);
        label.setAccessible(true);
        ChildgramUsageStorage.Entry entry = new ChildgramUsageStorage.Entry();
        entry.key = entry.label = "gifts";
        int giftLabel = getTargetContext().getResources().getIdentifier("ChildgramUsageGifts", "string", getTargetContext().getPackageName());
        expect("gift statistics have a translated label", giftLabel != 0 && LocaleController.getString(giftLabel).equals(label.invoke(usage, entry)));
        entry.key = entry.label = "other";
        int otherLabel = getTargetContext().getResources().getIdentifier("ChildgramUsageOther", "string", getTargetContext().getPackageName());
        expect("old other records keep their original category", otherLabel != 0 && LocaleController.getString(otherLabel).equals(label.invoke(usage, entry)));
    }

    private void checkUsageWindows() throws Exception {
        // LaunchActivity is singleTask: startActivitySync must create it, not wait for a reused activity.
        expect("usage UI check starts in a fresh instrumentation process", LaunchActivity.instance == null);
        Activity activity = startActivitySync(new Intent(getTargetContext(), LaunchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Method current = ChildgramUsageTracker.class.getDeclaredMethod("currentScreen");
        current.setAccessible(true);
        Method visibleGift = ChildgramUsageTracker.class.getDeclaredMethod("visibleGiftSheet", Activity.class);
        visibleGift.setAccessible(true);
        Object[] original = new Object[1];
        awaitUi("launched activity has a foreground usage screen", () -> {
            original[0] = current.invoke(null);
            return original[0] != null;
        });
        String originalKey = (String) fieldValue(original[0], "key");
        long originalOwner = (Long) fieldValue(original[0], "owner");
        expect("usage UI check has a non-gift base screen", !originalKey.equals("gifts"));
        BottomSheet[] gift = new BottomSheet[1], ordinary = new BottomSheet[1];
        try {
            onMainChecked(() -> {
                gift[0] = new CraftGiftSheet(activity);
                showUsageSheet(gift[0], activity);
            });
            awaitUi("shown gift window becomes the visible gift", () -> usageSheetVisible(gift[0])
                    && visibleGift.invoke(null, activity) == gift[0]);
            onMainChecked(() -> {
                Object screen = current.invoke(null);
                expect("shown gift counts as gifts", screen != null && fieldValue(screen, "key").equals("gifts"));
                expect("gift browsing does not count as full-screen video", Boolean.FALSE.equals(fieldValue(screen, "video")));
                expect("gift time belongs to the gift window's account", (Long) fieldValue(screen, "owner")
                        == UserConfig.getInstance(gift[0].getCurrentAccount()).getClientUserId());
                ordinary[0] = new BottomSheet(activity, false);
                showUsageSheet(ordinary[0], activity);
            });
            awaitUi("shown ordinary window covers the gift below", () -> usageSheetVisible(ordinary[0])
                    && visibleGift.invoke(null, activity) == null);
            onMainChecked(() -> {
                Object screen = current.invoke(null);
                expect("covered gift no longer determines the usage category", screen != null
                        && originalKey.equals(fieldValue(screen, "key")));
                ordinary[0].dismiss();
            });
            awaitUi("closing ordinary window reveals the gift again", () -> usageSheetVisible(gift[0])
                    && visibleGift.invoke(null, activity) == gift[0]);
            onMainChecked(() -> gift[0].dismiss());
            awaitUi("closing gift restores the original category and owner", () -> {
                Object screen = current.invoke(null);
                return visibleGift.invoke(null, activity) == null && screen != null
                        && originalKey.equals(fieldValue(screen, "key")) && (Long) fieldValue(screen, "owner") == originalOwner;
            });
        } finally {
            onMainChecked(() -> {
                if (ordinary[0] != null) ordinary[0].dismiss();
                if (gift[0] != null) gift[0].dismiss();
            });
        }
    }

    private void checkBubbleUsage() throws Exception {
        Context context = getTargetContext();
        int account = UserConfig.selectedAccount;
        long owner = UserConfig.getInstance(account).getClientUserId();
        expect("bubble test has an active account", owner != 0);
        long mainPeer = 8_400_000_000_000L + (System.nanoTime() & 0xfffffff);
        long bubblePeer = mainPeer + 1;
        onMainChecked(() -> {
            for (long id : new long[]{mainPeer, bubblePeer}) {
                TLRPC.User user = new TLRPC.TL_user();
                user.id = id;
                user.first_name = id == mainPeer ? "Main window fixture" : "Bubble fixture";
                MessagesController.getInstance(account).putUser(user, true);
            }
        });
        Intent main = chatIntent(LaunchActivity.class, account, mainPeer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent bubble = chatIntent(BubbleActivity.class, account, bubblePeer);
        Field session = ChildgramUsageTracker.class.getDeclaredField("session");
        session.setAccessible(true);
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        expect("notifications and Android bubbles enabled", notifications.areNotificationsEnabled() && notifications.areBubblesEnabled());
        String launcher = homePackage();
        int originalPreference = notifications.getBubblePreference();
        boolean finishedOnHome = false;
        try (ChildgramUsageStorageInstrumentation.BubbleFixture stats = ChildgramUsageStorageInstrumentation.openBubbleFixture(owner);
             ChildgramTestBubble notification = new ChildgramTestBubble(context, bubble)) {
            try {
                if (originalPreference != NotificationManager.BUBBLE_PREFERENCE_ALL) {
                    setBubblePreference(NotificationManager.BUBBLE_PREFERENCE_ALL);
                }
                context.startActivity(new Intent(main));
                awaitTrackedChat(session, owner, mainPeer, false);
                SystemClock.sleep(1500);
                notification.post();
                awaitTrackedChat(session, owner, bubblePeer, true);
                long[] enteredBubble = stats.totals(mainPeer, bubblePeer);
                expect("main chat time saved on entering bubble", enteredBubble[0] >= 1000);
                SystemClock.sleep(1500);

                goHome(session, launcher);
                long[] home = stats.totals(mainPeer, bubblePeer);
                expect("only bubble chat gained time in bubble", home[0] == enteredBubble[0] && home[1] - enteredBubble[1] >= 1000);
                long homeStart = SystemClock.elapsedRealtime();
                // Longer than a storage checkpoint: a wrongly running screen session would be saved.
                SystemClock.sleep(11000);
                onMainChecked(() -> expect("no active screen session while on Home", session.get(null) == null));
                long[] idle = stats.totals(mainPeer, bubblePeer);
                expect("Home adds no time to either chat or total screen usage", Arrays.equals(home, idle));
                bubbleTimes.putLong("homeWaitMs", SystemClock.elapsedRealtime() - homeStart);
                bubbleTimes.putLong("homeAddedMs", idle[2] - home[2]);

                long returnStart = SystemClock.elapsedRealtime();
                context.startActivity(new Intent(main));
                awaitTrackedChat(session, owner, mainPeer, false);
                SystemClock.sleep(1500);
                goHome(session, launcher);
                finishedOnHome = true;
                long[] returned = stats.totals(mainPeer, bubblePeer);
                expect("return resumes only the main chat", returned[0] - idle[0] >= 1000 && returned[1] == idle[1]);
                expect("return does not charge time spent on Home",
                        returned[0] - idle[0] <= SystemClock.elapsedRealtime() - returnStart);
                bubbleTimes.putLong("mainChatMs", home[0]);
                bubbleTimes.putLong("bubbleChatMs", home[1]);
                bubbleTimes.putLong("mainChatAfterReturnMs", returned[0] - idle[0]);
            } finally {
                // End any foreground interval before the isolated storage is detached.
                try {
                    if (!finishedOnHome) goHome(session, launcher);
                } finally {
                    if (originalPreference != NotificationManager.BUBBLE_PREFERENCE_ALL) setBubblePreference(originalPreference);
                }
            }
        }
    }

    private Intent chatIntent(Class<?> activity, int account, long peer) {
        return new Intent(getTargetContext(), activity).setAction("com.tmessages.openchat.childgram.test")
                .putExtra("currentAccount", account).putExtra("userId", peer);
    }

    private void setBubblePreference(int preference) throws Exception {
        String command = "cmd notification set_bubbles " + getTargetContext().getPackageName() + " " + preference;
        try (BufferedReader output = shellOutput(command)) {
            while (output.readLine() != null) { /* Wait for the command to finish. */ }
        }
        expect("bubble preference updated", getTargetContext().getSystemService(NotificationManager.class).getBubblePreference() == preference);
    }

    private void awaitTrackedChat(Field session, long owner, long peer, boolean bubble) throws Exception {
        awaitUi(bubble ? "system bubble counts its own chat" : "main window counts its own chat", 20000, () -> {
            Activity window = bubble ? (Activity) (Object) BubbleActivity.instance : (Activity) (Object) LaunchActivity.instance;
            if (window == null || !window.getWindow().getDecorView().isShown()) return false;
            if (bubble ? !window.isLaunchedFromBubble() : !LaunchActivity.isResumed || BubbleActivity.instance != null) return false;
            Object current = session.get(null);
            return current != null && (Long) fieldValue(current, "owner") == owner
                    && ("chat:" + peer).equals(fieldValue(current, "key"));
        });
    }

    private BufferedReader shellOutput(String command) {
        return new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))));
    }

    private String homePackage() throws Exception {
        try (BufferedReader output = shellOutput("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")) {
            String line;
            while ((line = output.readLine()) != null) {
                ComponentName home = ComponentName.unflattenFromString(line.trim());
                if (home != null) return home.getPackageName();
            }
        }
        throw new AssertionError("Android has no default Home screen");
    }

    private void goHome(Field session, String launcher) throws Exception {
        expect("Android accepts Home", getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
        awaitUi("Home pauses both Telegram windows and ends screen accounting", 15000,
                () -> BubbleActivity.instance == null && !LaunchActivity.isResumed && session.get(null) == null);
        // A collapsed bubble can keep an accessibility window above the launcher.
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            try (BufferedReader tasks = shellOutput("dumpsys activity activities")) {
                String line;
                while ((line = tasks.readLine()) != null) {
                    if (line.contains("topResumedActivity=") && line.contains(" " + launcher + "/")) return;
                }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Android launcher resumed after Home");
    }

    private static void showUsageSheet(BottomSheet sheet, Context context) {
        TextView content = new TextView(context);
        content.setText("Childgram usage check");
        content.setMinHeight(AndroidUtilities.dp(180));
        sheet.setCustomView(content);
        sheet.show();
    }

    private static boolean usageSheetVisible(BottomSheet sheet) {
        Rect bounds = new Rect();
        return sheet.getContainerView().isAttachedToWindow() && sheet.getContainerView().isShown()
                && sheet.getContainerView().getGlobalVisibleRect(bounds) && !bounds.isEmpty();
    }

    private interface MainAction { void run() throws Exception; }
    private interface MainCondition { boolean matches() throws Exception; }

    private void onMainChecked(MainAction action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() instanceof Exception) throw (Exception) error.get();
        if (error.get() instanceof Error) throw (Error) error.get();
    }

    private void awaitUi(String label, MainCondition condition) throws Exception {
        awaitUi(label, 5000, condition);
    }

    private void awaitUi(String label, long timeoutMs, MainCondition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        boolean[] ready = {false};
        do {
            onMainChecked(() -> ready[0] = condition.matches());
            if (ready[0]) break;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        expect(label, ready[0]);
    }

    private static Object fieldValue(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private void checkVideoUsage(int account) throws Exception {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = "";
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = UserConfig.getInstance(account).getClientUserId();
        message.media = new TLRPC.TL_messageMediaDocument();
        message.media.document = new TLRPC.TL_document();
        message.media.document.mime_type = "video/mp4";
        message.media.document.attributes.add(new TLRPC.TL_documentAttributeVideo());
        MessageObject video = new MessageObject(account, message, false, false);
        PhotoViewer viewer = new PhotoViewer();
        setField(viewer, "isVisible", true);
        setField(viewer, "placeProvider", new PhotoViewer.EmptyPhotoViewerProvider());
        setField(viewer, "parentActivity", new Activity());
        setField(viewer, "currentMessageObject", video);

        // No decoder, media file or global player is started by this isolated fixture.
        Class<?> playerClass = Class.forName("org.telegram.ui.Components.VideoPlayer");
        Object player = playerClass.getConstructor(boolean.class, boolean.class).newInstance(false, true);
        Field exoField = playerClass.getField("player");
        boolean[] playing = {true};
        try {
            exoField.set(player, Proxy.newProxyInstance(exoField.getType().getClassLoader(),
                    new Class<?>[]{exoField.getType()}, (proxy, method, args) -> {
                        if (method.getName().equals("isPlaying")) return playing[0];
                        throw new AssertionError("unexpected player call in usage fixture");
                    }));
            setField(viewer, "videoPlayer", player);
            expect("full-screen video belongs to its chat", viewer.getChildgramVideoMessage() == video);
            expect("playing full-screen video counts", viewer.isChildgramVideoPlaying());
            playing[0] = false;
            expect("paused video retains chat context", viewer.getChildgramVideoMessage() == video);
            expect("paused video does not count as playback", !viewer.isChildgramVideoPlaying());
            playing[0] = true;
            setField(viewer, "pipPrimaryHidden", true);
            expect("hidden primary during system PiP transition is excluded", viewer.getChildgramVideoMessage() == null);
            setField(viewer, "pipPrimaryHidden", false);

            Constructor<PipSession> constructor = PipSession.class.getDeclaredConstructor(PipCoordinator.class, PipContent.class);
            constructor.setAccessible(true);
            PipSession session = constructor.newInstance(null, null);
            setField(viewer, "pipSession", session);
            expect("primary PiP session keeps full-screen playback", viewer.isChildgramVideoPlaying());
            setField(session, "state", PipSession.STATE_FLOATING);
            expect("floating session without legacy inline flag is excluded", viewer.getChildgramVideoMessage() == null);
            expect("floating playback does not count", !viewer.isChildgramVideoPlaying());
            setField(session, "state", PipSession.STATE_SYSTEM);
            expect("system session before Android mode callback is excluded", viewer.getChildgramVideoMessage() == null);
            setField(session, "state", PipSession.STATE_PRIMARY);
            expect("return to full-screen resumes playback accounting", viewer.isChildgramVideoPlaying());
        } finally {
            exoField.set(player, null);
            playerClass.getMethod("releasePlayer", boolean.class).invoke(player, false);
        }
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private MessageObject comment(int account, long discussion, long channel, int post, int id) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.message = "Access test comment";
        message.peer_id = new TLRPC.TL_peerChannel();
        message.peer_id.channel_id = discussion;
        message.from_id = new TLRPC.TL_peerChannel();
        message.from_id.channel_id = channel;
        message.fwd_from = new TLRPC.TL_messageFwdHeader();
        message.fwd_from.from_id = new TLRPC.TL_peerChannel();
        message.fwd_from.from_id.channel_id = channel;
        message.fwd_from.channel_post = post;
        return new MessageObject(account, message, false, false);
    }

    private static final class RecordingContext extends ContextWrapper {
        Intent launched;

        RecordingContext(Context context) {
            super(context);
        }

        @Override
        public void startActivity(Intent intent) {
            launched = intent;
        }
    }
}
