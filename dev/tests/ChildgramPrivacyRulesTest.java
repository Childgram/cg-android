package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;

/** Runs against the built application classes; no Telegram account or network is used. */
public final class ChildgramPrivacyRulesTest {
    public static void main(String[] args) {
        TLRPC.TL_privacyValueAllowUsers allow = new TLRPC.TL_privacyValueAllowUsers();
        allow.users.addAll(Arrays.asList(41L, 42L));
        TLRPC.TL_privacyValueDisallowUsers deny = new TLRPC.TL_privacyValueDisallowUsers();
        deny.users.add(43L);
        TLRPC.TL_privacyValueAllowChatParticipants allowChat = new TLRPC.TL_privacyValueAllowChatParticipants();
        allowChat.chats.add(51L);
        TLRPC.TL_privacyValueDisallowChatParticipants denyChat = new TLRPC.TL_privacyValueDisallowChatParticipants();
        denyChat.chats.add(52L);
        ArrayList<TLRPC.PrivacyRule> before = new ArrayList<>(Arrays.asList(
            allow, deny, allowChat, denyChat, new TLRPC.TL_privacyValueAllowContacts(),
            new TLRPC.TL_privacyValueDisallowAll(), new TLRPC.TL_privacyValueAllowPremium(),
            new TLRPC.TL_privacyValueAllowBots(), new TLRPC.TL_privacyValueDisallowBots(),
            new TLRPC.TL_privacyValueDisallowContacts(), new TLRPC.TL_privacyValueAllowCloseFriends()));
        check(!ChildgramPrivacyController.isNobody(before), "Contacts must not be mistaken for Nobody");
        ArrayList<TLRPC.InputPrivacyRule> actual = ChildgramPrivacyController.nobodyRules(before, id -> {
            TLRPC.TL_inputUser user = new TLRPC.TL_inputUser();
            user.user_id = id;
            user.access_hash = id * 100;
            return user;
        });
        check(actual.size() == 10, "Keep all exception types and one base rule");
        TLRPC.TL_inputPrivacyValueAllowUsers inputAllow = (TLRPC.TL_inputPrivacyValueAllowUsers) actual.get(0);
        check(inputAllow.users.size() == 2 && inputAllow.users.get(1).user_id == 42 && inputAllow.users.get(1).access_hash == 4200,
            "Keep exact exception peers and access hashes");
        check(((TLRPC.TL_inputPrivacyValueDisallowUsers) actual.get(1)).users.get(0).user_id == 43, "Keep denied user");
        check(((TLRPC.TL_inputPrivacyValueAllowChatParticipants) actual.get(2)).chats.equals(allowChat.chats), "Keep allowed chats");
        check(((TLRPC.TL_inputPrivacyValueDisallowChatParticipants) actual.get(3)).chats.equals(denyChat.chats), "Keep denied chats");
        check(actual.get(4) instanceof TLRPC.TL_inputPrivacyValueDisallowAll, "Replace base in place");
        check(actual.get(5) instanceof TLRPC.TL_inputPrivacyValueAllowPremium, "Keep Premium exception");
        check(actual.get(6) instanceof TLRPC.TL_inputPrivacyValueAllowBots, "Keep bot allowance");
        check(actual.get(7) instanceof TLRPC.TL_inputPrivacyValueDisallowBots, "Keep bot denial");
        check(actual.get(8) instanceof TLRPC.TL_inputPrivacyValueDisallowContacts, "Keep contact denial");
        check(actual.get(9) instanceof TLRPC.TL_inputPrivacyValueAllowCloseFriends, "Keep close friends exception");
        check(before.size() == 11 && before.get(4) instanceof TLRPC.TL_privacyValueAllowContacts, "Do not mutate fetched rules");
        ArrayList<TLRPC.PrivacyRule> saved = new ArrayList<>(before);
        saved.remove(4);
        check(ChildgramPrivacyController.isNobody(saved), "Nobody allows explicit exceptions");
        check(ChildgramPrivacyController.exceptions(before).equals(ChildgramPrivacyController.exceptions(saved)), "Base change preserves exceptions");
        saved.remove(0);
        check(!ChildgramPrivacyController.exceptions(before).equals(ChildgramPrivacyController.exceptions(saved)), "Detect a server-dropped exception");
        rejected(() -> ChildgramPrivacyController.nobodyRules(before, id -> null), "Missing users must abort the entire conversion");
        rejected(() -> ChildgramPrivacyController.nobodyRules(Arrays.asList(new TLRPC.PrivacyRule() {}), id -> null), "Unknown rule must fail closed");
        check(ChildgramPrivacyController.nobodyRules(new ArrayList<>(), id -> null).get(0) instanceof TLRPC.TL_inputPrivacyValueDisallowAll,
            "An absent base becomes Nobody");
        System.out.println("Childgram privacy rule checks passed");
    }

    private static void rejected(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // No partial update may be sent.
        }
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
