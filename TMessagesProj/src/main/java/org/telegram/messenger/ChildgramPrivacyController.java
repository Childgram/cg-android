package org.telegram.messenger;

import android.os.SystemClock;
import android.widget.Toast;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongFunction;

/** Enforces invitation privacy on foreground entry without editing exceptions. */
public final class ChildgramPrivacyController extends BaseController implements NotificationCenter.NotificationCenterDelegate {
    private static final ChildgramPrivacyController[] instances = new ChildgramPrivacyController[UserConfig.MAX_ACCOUNT_COUNT];
    private static boolean foreground;
    private final Runnable retry = this::check;
    private long userId;
    private long retryAt;
    private long retryDelay = 30_000;
    private int requestId;
    private int generation;
    private boolean pending;
    private boolean busy;
    private boolean errorShown;

    private ChildgramPrivacyController(int account) {
        super(account);
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateConnectionState);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
    }

    public static void onForeground() {
        if (!BuildVars.CHILDGRAM || foreground) {
            return;
        }
        foreground = true;
        for (int account = 0; account < instances.length; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                onAccountActivated(account);
            }
        }
    }

    public static void onBackground() {
        foreground = false;
        for (ChildgramPrivacyController controller : instances) {
            if (controller != null) {
                AndroidUtilities.cancelRunOnUIThread(controller.retry);
            }
        }
    }

    public static void onAccountActivated(int account) {
        if (!BuildVars.CHILDGRAM || !foreground || !UserConfig.isValidAccount(account)) {
            return;
        }
        if (instances[account] == null) {
            instances[account] = new ChildgramPrivacyController(account);
        }
        ChildgramPrivacyController controller = instances[account];
        controller.syncAccount();
        controller.pending = true;
        controller.errorShown = false;
        controller.check();
    }

    private void syncAccount() {
        long id = getUserConfig().isClientActivated() ? getUserConfig().getClientUserId() : 0;
        if (id != userId) {
            generation++;
            if (requestId != 0) {
                getConnectionsManager().cancelRequest(requestId, true);
            }
            AndroidUtilities.cancelRunOnUIThread(retry);
            userId = id;
            requestId = 0;
            pending = busy = errorShown = false;
            retryAt = 0;
            retryDelay = 30_000;
        }
    }

    private void check() {
        syncAccount();
        if (!foreground || !pending || busy || userId == 0) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(retry);
        long wait = retryAt - SystemClock.elapsedRealtime();
        if (wait > 0) {
            AndroidUtilities.runOnUIThread(retry, wait);
            return;
        }
        if (getConnectionsManager().getConnectionState() != ConnectionsManager.ConnectionStateConnected) {
            AndroidUtilities.runOnUIThread(retry, 30_000);
            return;
        }
        busy = true;
        final int attempt = generation;
        TL_account.getPrivacy request = new TL_account.getPrivacy();
        request.key = new TLRPC.TL_inputPrivacyKeyChatInvite();
        requestId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            syncAccount();
            if (attempt != generation) {
                return;
            }
            requestId = 0;
            if (!(response instanceof TL_account.privacyRules)) {
                failed(false);
                return;
            }
            TL_account.privacyRules current = (TL_account.privacyRules) response;
            cache(current);
            if (isNobody(current.rules)) {
                completed();
                return;
            }
            if (!foreground) {
                busy = false;
                return;
            }
            final ArrayList<TLRPC.InputPrivacyRule> rules;
            try {
                rules = nobodyRules(current.rules, id -> {
                    TLRPC.User user = getMessagesController().getUser(id);
                    return user == null || user.min ? null : getMessagesController().getInputUser(user);
                });
            } catch (IllegalArgumentException e) {
                // Never submit a partial exception list when a peer or rule is unavailable.
                failed(false);
                return;
            }
            TL_account.setPrivacy update = new TL_account.setPrivacy();
            update.key = new TLRPC.TL_inputPrivacyKeyChatInvite();
            update.rules = rules;
            requestId = getConnectionsManager().sendRequest(update, (result, updateError) -> AndroidUtilities.runOnUIThread(() -> {
                syncAccount();
                if (attempt != generation) {
                    return;
                }
                requestId = 0;
                if (!(result instanceof TL_account.privacyRules)) {
                    failed(false);
                    return;
                }
                TL_account.privacyRules saved = (TL_account.privacyRules) result;
                cache(saved);
                if (!exceptions(current.rules).equals(exceptions(saved.rules))) {
                    // A retry cannot restore server-normalized exceptions safely.
                    failed(true);
                } else if (!isNobody(saved.rules)) {
                    failed(false);
                } else {
                    completed();
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void cache(TL_account.privacyRules rules) {
        getMessagesController().putUsers(rules.users, false);
        getMessagesController().putChats(rules.chats, false);
        getContactsController().setPrivacyRules(rules.rules, ContactsController.PRIVACY_RULES_TYPE_INVITE);
    }

    private void completed() {
        busy = pending = false;
        retryAt = 0;
        retryDelay = 30_000;
    }

    private void failed(boolean exceptionsChanged) {
        busy = false;
        pending = !exceptionsChanged;
        retryAt = SystemClock.elapsedRealtime() + retryDelay;
        retryDelay = Math.min(retryDelay * 2, 300_000);
        if (foreground && (exceptionsChanged || !errorShown)) {
            errorShown = true;
            Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(exceptionsChanged ?
                R.string.ChildgramPrivacyExceptionsChanged : R.string.ChildgramPrivacyCheckFailed), Toast.LENGTH_LONG).show();
        }
        if (foreground && pending) {
            AndroidUtilities.runOnUIThread(retry, Math.max(1, retryAt - SystemClock.elapsedRealtime()));
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appDidLogout) {
            syncAccount();
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            check();
        }
    }

    static boolean isNobody(List<TLRPC.PrivacyRule> rules) {
        boolean nobody = false;
        for (TLRPC.PrivacyRule rule : rules) {
            if (rule instanceof TLRPC.TL_privacyValueAllowAll || rule instanceof TLRPC.TL_privacyValueAllowContacts) {
                return false;
            }
            nobody |= rule instanceof TLRPC.TL_privacyValueDisallowAll;
        }
        return nobody;
    }

    static ArrayList<TLRPC.InputPrivacyRule> nobodyRules(List<TLRPC.PrivacyRule> rules, LongFunction<TLRPC.InputUser> users) {
        ArrayList<TLRPC.InputPrivacyRule> result = new ArrayList<>();
        boolean baseAdded = false;
        for (TLRPC.PrivacyRule rule : rules) {
            if (isBase(rule)) {
                if (!baseAdded) {
                    result.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
                    baseAdded = true;
                }
            } else if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                TLRPC.TL_inputPrivacyValueAllowUsers converted = new TLRPC.TL_inputPrivacyValueAllowUsers();
                for (long id : ((TLRPC.TL_privacyValueAllowUsers) rule).users) {
                    converted.users.add(requireUser(users.apply(id)));
                }
                result.add(converted);
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                TLRPC.TL_inputPrivacyValueDisallowUsers converted = new TLRPC.TL_inputPrivacyValueDisallowUsers();
                for (long id : ((TLRPC.TL_privacyValueDisallowUsers) rule).users) {
                    converted.users.add(requireUser(users.apply(id)));
                }
                result.add(converted);
            } else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                TLRPC.TL_inputPrivacyValueAllowChatParticipants converted = new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
                converted.chats.addAll(((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats);
                result.add(converted);
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                TLRPC.TL_inputPrivacyValueDisallowChatParticipants converted = new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
                converted.chats.addAll(((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats);
                result.add(converted);
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowContacts) {
                result.add(new TLRPC.TL_inputPrivacyValueDisallowContacts());
            } else if (rule instanceof TLRPC.TL_privacyValueAllowCloseFriends) {
                result.add(new TLRPC.TL_inputPrivacyValueAllowCloseFriends());
            } else if (rule instanceof TLRPC.TL_privacyValueAllowPremium) {
                result.add(new TLRPC.TL_inputPrivacyValueAllowPremium());
            } else if (rule instanceof TLRPC.TL_privacyValueAllowBots) {
                result.add(new TLRPC.TL_inputPrivacyValueAllowBots());
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowBots) {
                result.add(new TLRPC.TL_inputPrivacyValueDisallowBots());
            } else {
                throw new IllegalArgumentException("Unsupported invitation privacy rule");
            }
        }
        if (!baseAdded) {
            result.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
        }
        return result;
    }

    private static TLRPC.InputUser requireUser(TLRPC.InputUser user) {
        if (user == null || user instanceof TLRPC.TL_inputUserEmpty) {
            throw new IllegalArgumentException("Missing invitation privacy exception peer");
        }
        return user;
    }

    private static boolean isBase(TLRPC.PrivacyRule rule) {
        return rule instanceof TLRPC.TL_privacyValueAllowAll || rule instanceof TLRPC.TL_privacyValueAllowContacts || rule instanceof TLRPC.TL_privacyValueDisallowAll;
    }

    static Set<String> exceptions(List<TLRPC.PrivacyRule> rules) {
        Set<String> result = new TreeSet<>();
        for (TLRPC.PrivacyRule rule : rules) {
            List<Long> ids = null;
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                ids = ((TLRPC.TL_privacyValueAllowUsers) rule).users;
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                ids = ((TLRPC.TL_privacyValueDisallowUsers) rule).users;
            } else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                ids = ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats;
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                ids = ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats;
            }
            if (ids != null) {
                for (long id : ids) {
                    result.add(rule.getClass().getSimpleName() + ":" + id);
                }
            } else if (!isBase(rule)) {
                result.add(rule.getClass().getSimpleName());
            }
        }
        return result;
    }
}
