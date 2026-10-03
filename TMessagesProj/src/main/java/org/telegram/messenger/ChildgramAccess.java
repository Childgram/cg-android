package org.telegram.messenger;

import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.TopicsFragment;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** Account-scoped access decisions. Reading a peer or a forwarded post never grants access. */
public final class ChildgramAccess extends BaseController {
    private static final ConcurrentHashMap<Integer, ChildgramAccess> instances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> botChecks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CommentAccess> comments = new ConcurrentHashMap<>();
    private volatile long ownerId;

    public static ChildgramAccess getInstance(int account) {
        return instances.computeIfAbsent(account, ChildgramAccess::new);
    }

    private ChildgramAccess(int account) {
        super(account);
    }

    private String botKey(long id) {
        final long owner = getUserConfig().getClientUserId();
        if (ownerId != owner) {
            ownerId = owner;
            botChecks.clear();
            comments.clear();
        }
        return "childgram_bot_allowed_" + owner + "_" + id;
    }

    public boolean isAllowed(long dialogId) {
        if (!BuildVars.CHILDGRAM || DialogObject.isEncryptedDialog(dialogId)) return true;
        botKey(0);
        if (dialogId < 0) {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            return chat != null && !chat.min && ChatObject.isInChat(chat);
        }
        if (dialogId == 0) return false;
        TLRPC.User user = getMessagesController().getUser(dialogId);
        if (user == null || user.min) return false;
        if (!user.bot) return true;
        String key = botKey(dialogId);
        Long checked = botChecks.get(dialogId);
        if (checked != null && SystemClock.elapsedRealtime() - checked < 60_000) return true;
        if (getMessagesController().blockePeers.indexOfKey(dialogId) >= 0) return false;
        // Offline access is limited to a permission previously confirmed by the server.
        return getConnectionsManager().getConnectionState() != ConnectionsManager.ConnectionStateConnected
                && MessagesController.getMainSettings(currentAccount).getBoolean(key, false);
    }

    public void invalidateBot(long id) {
        if (!BuildVars.CHILDGRAM || id <= 0) return;
        String key = botKey(id);
        botChecks.remove(id);
        MessagesController.getMainSettings(currentAccount).edit().remove(key).apply();
    }

    public void check(long dialogId, BaseFragment source, Runnable allowed) {
        AndroidUtilities.runOnUIThread(() -> {
            if (isAllowed(dialogId)) {
                allowed.run();
                return;
            }
            final long owner = getUserConfig().getClientUserId();
            getMessagesStorage().getStorageQueue().postRunnable(() -> {
                TLRPC.User storedUser = dialogId > 0 ? getMessagesStorage().getUser(dialogId) : null;
                TLRPC.Chat storedChat = dialogId < 0 ? getMessagesStorage().getChat(-dialogId) : null;
                AndroidUtilities.runOnUIThread(() -> {
                    if (owner != getUserConfig().getClientUserId()) return;
                    if (storedUser != null) getMessagesController().putUser(storedUser, true);
                    if (storedChat != null) getMessagesController().putChat(storedChat, true);
                    checkResolved(dialogId, source, allowed, owner);
                });
            });
        });
    }

    private void checkResolved(long dialogId, BaseFragment source, Runnable allowed, long owner) {
        if (isAllowed(dialogId)) {
            allowed.run();
            return;
        }
        int state = getConnectionsManager().getConnectionState();
        if (state != ConnectionsManager.ConnectionStateConnected && state != ConnectionsManager.ConnectionStateUpdating) {
            failed(source);
            return;
        }
        if (dialogId < 0) {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            final TLObject request;
            if (ChatObject.isChannel(chat)) {
                TLRPC.TL_channels_getChannels req = new TLRPC.TL_channels_getChannels();
                TLRPC.InputChannel input;
                if (chat.access_hash == 0 && chat.fromMessageDialogId != 0 && chat.fromMessageId != 0) {
                    TLRPC.TL_inputChannelFromMessage fromMessage = new TLRPC.TL_inputChannelFromMessage();
                    fromMessage.channel_id = chat.id;
                    fromMessage.peer = getMessagesController().getInputPeer(chat.fromMessageDialogId);
                    fromMessage.msg_id = chat.fromMessageId;
                    input = fromMessage;
                } else {
                    input = MessagesController.getInputChannel(chat);
                }
                req.id.add(input);
                request = req;
            } else if (chat != null) {
                TLRPC.TL_messages_getChats req = new TLRPC.TL_messages_getChats();
                req.id.add(chat.id);
                request = req;
            } else {
                failed(source);
                return;
            }
            getConnectionsManager().sendRequest(request, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (owner != getUserConfig().getClientUserId()) return;
                if (res instanceof TLRPC.messages_Chats) {
                    TLRPC.messages_Chats chats = (TLRPC.messages_Chats) res;
                    getMessagesController().putChats(chats.chats, false);
                    getMessagesStorage().putUsersAndChats(null, chats.chats, true, true);
                    if (isAllowed(dialogId)) allowed.run(); else deny(source);
                } else {
                    failed(source);
                }
            }));
            return;
        }
        TLRPC.User user = getMessagesController().getUser(dialogId);
        if (user == null) {
            failed(source);
            return;
        }
        if (user.min) {
            TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
            req.id.add(getMessagesController().getInputUser(user));
            getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (owner != getUserConfig().getClientUserId()) return;
                if (res instanceof Vector) {
                    for (Object item : ((Vector<?>) res).objects) {
                        if (item instanceof TLRPC.User && ((TLRPC.User) item).id == dialogId && !((TLRPC.User) item).min) {
                            getMessagesController().putUser((TLRPC.User) item, false);
                            checkResolved(dialogId, source, allowed, owner);
                            return;
                        }
                    }
                }
                failed(source);
            }));
            return;
        }
        TL_bots.canSendMessage req = new TL_bots.canSendMessage();
        req.bot = getMessagesController().getInputUser(user);
        getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (owner != getUserConfig().getClientUserId()) return;
            final String key = botKey(dialogId);
            if (res instanceof TLRPC.TL_boolTrue) {
                botChecks.put(dialogId, SystemClock.elapsedRealtime());
                MessagesController.getMainSettings(currentAccount).edit().putBoolean(key, true).apply();
                allowed.run();
            } else if (res instanceof TLRPC.TL_boolFalse) {
                botChecks.remove(dialogId);
                MessagesController.getMainSettings(currentAccount).edit().remove(key).apply();
                deny(source);
            } else {
                failed(source);
            }
        }));
    }

    public static void deny(BaseFragment source) {
        notice(source, R.string.ChildgramAccessDenied);
    }

    private static void failed(BaseFragment source) {
        notice(source, R.string.ChildgramAccessCheckFailed);
    }

    private static void notice(BaseFragment source, int stringId) {
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment fragment = source != null && source.getParentActivity() != null ? source : LaunchActivity.getLastFragment();
            if (fragment != null && fragment.getParentActivity() != null) {
                AlertsCreator.showSimpleAlert(fragment, LocaleController.getString(stringId));
            } else {
                Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(stringId), Toast.LENGTH_LONG).show();
            }
        });
    }

    private long fragmentDialogId(BaseFragment fragment) {
        if (!(fragment instanceof ChatActivity || fragment instanceof ProfileActivity || fragment instanceof TopicsFragment)) return 0;
        Bundle args = fragment.getArguments();
        if (args == null) return 0;
        long userId = args.getLong("user_id", 0);
        long chatId = args.getLong("chat_id", 0);
        // Match each destination's own argument precedence; enc_id never overrides a peer.
        if (fragment instanceof ProfileActivity) return userId != 0 ? userId : -chatId;
        return chatId != 0 ? -chatId : userId;
    }

    public boolean isFragmentAllowed(BaseFragment fragment) {
        if (!BuildVars.CHILDGRAM) return true;
        if (fragment instanceof ChatActivity) {
            ChatActivity chat = (ChatActivity) fragment;
            CommentAccess access = chat.getChildgramCommentAccess();
            if (validComment(access) && access.discussion == fragmentDialogId(fragment) && access.thread == chat.getThreadId()) return true;
        }
        long id = fragmentDialogId(fragment);
        return id == 0 || isAllowed(id);
    }

    public boolean guardFragment(BaseFragment fragment, Runnable retry) {
        if (isFragmentAllowed(fragment)) return true;
        BaseFragment source = LaunchActivity.getLastFragment();
        check(fragmentDialogId(fragment), source, () -> {
            if (source == null || source == LaunchActivity.getLastFragment()) retry.run();
        });
        return false;
    }

    public static final class CommentAccess {
        private final long owner, channel, discussion, thread;
        private CommentAccess(long owner, long channel, long discussion, long thread) {
            this.owner = owner;
            this.channel = channel;
            this.discussion = discussion;
            this.thread = thread;
        }
    }

    /** Called only for a server-resolved channel post's discussion messages. */
    public CommentAccess commentAccess(TLRPC.Chat channel, int postId, ArrayList<MessageObject> messages) {
        if (!BuildVars.CHILDGRAM || channel == null || !channel.broadcast || !isAllowed(-channel.id) || messages == null || messages.isEmpty()) return null;
        MessageObject root = messages.get(messages.size() - 1);
        if (root.getDialogId() >= 0) return null;
        boolean foundPost = false;
        for (MessageObject item : messages) {
            TLRPC.MessageFwdHeader forward = item.messageOwner.fwd_from;
            if (item.getDialogId() != root.getDialogId() || forward == null || forward.from_id == null || forward.from_id.channel_id != channel.id) return null;
            foundPost |= forward.channel_post == postId;
        }
        if (!foundPost) return null;
        CommentAccess access = new CommentAccess(getUserConfig().getClientUserId(), channel.id, root.getDialogId(), root.getId());
        for (MessageObject item : messages) {
            comments.put(access.discussion + ":" + item.getId(), access);
        }
        return access;
    }

    private boolean validComment(CommentAccess access) {
        return access != null && access.owner == getUserConfig().getClientUserId() && isAllowed(-access.channel);
    }

    /** Access follows the containing chat, never the attribution of a forwarded copy. */
    public boolean canViewMessage(MessageObject message) {
        if (!BuildVars.CHILDGRAM || message == null) return true;
        if (message.currentAccount != currentAccount) return false;
        long dialogId = message.getDialogId();
        if (isAllowed(dialogId)) return true;
        if (validComment(comments.get(dialogId + ":" + message.getId()))) return true;
        return MessageObject.getReplyToDialogId(message.messageOwner) == dialogId
                && validComment(comments.get(dialogId + ":" + message.getReplyAnyMsgId()));
    }

    public void checkViewMessage(MessageObject message, BaseFragment source, Runnable allowed) {
        if (canViewMessage(message)) {
            allowed.run();
        } else if (message.currentAccount != currentAccount) {
            deny(source);
        } else {
            final BaseFragment origin = LaunchActivity.getLastFragment();
            final int selectedAccount = UserConfig.selectedAccount;
            check(message.getDialogId(), source, () -> {
                if (origin != LaunchActivity.getLastFragment() || selectedAccount != UserConfig.selectedAccount) return;
                if (canViewMessage(message)) allowed.run(); else deny(source);
            });
        }
    }

    public boolean canSend(long dialogId, MessageObject replyToTop) {
        return isAllowed(dialogId) || replyToTop != null && validComment(comments.get(dialogId + ":" + replyToTop.getId()));
    }
}
