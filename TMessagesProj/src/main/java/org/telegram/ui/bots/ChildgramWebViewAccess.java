package org.telegram.ui.bots;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChildgramAccess;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/** One sheet's pending access check. All calls run on the UI thread. */
final class ChildgramWebViewAccess {
    private int generation;
    private int accountSelection = UserConfig.getAccountSelectionVersion();
    private boolean waiting, closed;

    boolean canShow(int account, long botId) {
        return !BuildVars.CHILDGRAM || !closed && !waiting && UserConfig.selectedAccount == account
                && accountSelection == UserConfig.getAccountSelectionVersion()
                && ChildgramAccess.getInstance(account).isAllowed(botId);
    }

    void request(BaseFragment fragment, WebViewRequestProps props, Runnable load, Runnable show) {
        final int request = ++generation;
        waiting = false;
        accountSelection = UserConfig.getAccountSelectionVersion();
        final int account = props.currentAccount;
        final long botId = props.botId;
        if (canShow(account, botId)) {
            load.run();
            return; // The caller shows a synchronously allowed request.
        }
        if (closed || UserConfig.selectedAccount != account) return;

        final BaseFragment source = fragment != null ? fragment : LaunchActivity.getSafeLastFragment();
        final long owner = UserConfig.getInstance(account).getClientUserId();
        waiting = true;
        ChildgramAccess.getInstance(account).check(botId, fragment, () -> {
            if (request != generation) return;
            waiting = false;
            if (props.currentAccount != account || props.botId != botId || !canShow(account, botId)
                    || owner != UserConfig.getInstance(account).getClientUserId() || ApplicationLoader.mainInterfacePaused
                    || source == null || source.getParentActivity() == null || LaunchActivity.getSafeLastFragment() != source) return;
            load.run();
            show.run();
        }, () -> {
            if (request == generation) waiting = false;
        });
    }

    void close() {
        closed = true;
        generation++;
    }
}
