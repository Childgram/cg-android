package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.messenger.browser.Browser;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs against the installed app. Fixtures exist only in its in-memory peer cache. */
public final class ChildgramAccessInstrumentation extends Instrumentation {
    private int assertions;
    private String failure;
    private boolean verifyPrivacy;
    private boolean verifyUsage;
    private boolean usageOnly;
    private ChildgramUsageStorageInstrumentation.Result usageResult;
    private int exceptionCount = -1;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        verifyPrivacy = arguments != null && "true".equals(arguments.getString("verify_privacy"));
        usageOnly = arguments != null && "true".equals(arguments.getString("usage_only"));
        verifyUsage = usageOnly || arguments != null && "true".equals(arguments.getString("verify_usage"));
        start();
    }

    @Override
    public void onStart() {
        runOnMainSync(() -> {
            try {
                ApplicationLoader.postInitApplication();
                if (!usageOnly) runChecks();
            } catch (Throwable error) {
                // Exception messages can contain user data. Report only the type or assertion label.
                failure = error instanceof AssertionError ? error.getMessage() : error.getClass().getSimpleName();
            }
        });
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

    private void runChecks() {
        expect("childgram build required", BuildVars.CHILDGRAM);
        int account = UserConfig.selectedAccount;
        expect("activated test account required", UserConfig.getInstance(account).isClientActivated());
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
