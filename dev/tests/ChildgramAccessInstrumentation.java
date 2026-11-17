package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
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
import org.telegram.messenger.browser.Browser;
import org.telegram.utils.pip.PipContent;
import org.telegram.utils.pip.PipCoordinator;
import org.telegram.utils.pip.PipSession;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
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
        if (failure == null && verifyUsageUi) {
            try { checkUsageWindows(); }
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
        Bundle report = new Bundle();
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
        awaitUsageUi("launched activity has a foreground usage screen", () -> {
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
            awaitUsageUi("shown gift window becomes the visible gift", () -> usageSheetVisible(gift[0])
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
            awaitUsageUi("shown ordinary window covers the gift below", () -> usageSheetVisible(ordinary[0])
                    && visibleGift.invoke(null, activity) == null);
            onMainChecked(() -> {
                Object screen = current.invoke(null);
                expect("covered gift no longer determines the usage category", screen != null
                        && originalKey.equals(fieldValue(screen, "key")));
                ordinary[0].dismiss();
            });
            awaitUsageUi("closing ordinary window reveals the gift again", () -> usageSheetVisible(gift[0])
                    && visibleGift.invoke(null, activity) == gift[0]);
            onMainChecked(() -> gift[0].dismiss());
            awaitUsageUi("closing gift restores the original category and owner", () -> {
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

    private void awaitUsageUi(String label, MainCondition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
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
