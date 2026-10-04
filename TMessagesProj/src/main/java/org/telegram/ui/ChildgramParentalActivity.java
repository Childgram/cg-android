package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChildgramParentalSettings;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Local, installation-wide controls, protected independently of Telegram's app lock. */
public final class ChildgramParentalActivity extends UniversalFragment {
    private boolean authenticated;
    private boolean pinPending;
    private boolean cancelled;
    private boolean promptShown;
    private final Runnable checkAccess = this::ensureAccess;

    @Override public boolean onFragmentCreate() {
        return BuildVars.CHILDGRAM && super.onFragmentCreate();
    }

    @Override public View createView(Context context) {
        View result = super.createView(context);
        listView.setVisibility(View.INVISIBLE);
        return result;
    }

    @Override protected CharSequence getTitle() { return getString(R.string.ChildgramParentalTitle); }

    @Override public void onResume() {
        super.onResume();
        AndroidUtilities.runOnUIThread(checkAccess);
    }

    @Override public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        // Starting navigation inside the previous animation's completion cancels the new animation.
        AndroidUtilities.cancelRunOnUIThread(checkAccess);
        AndroidUtilities.runOnUIThread(checkAccess);
    }

    @Override public void onPause() {
        authenticated = false;
        if (listView != null) listView.setVisibility(View.INVISIBLE);
        AndroidUtilities.cancelRunOnUIThread(checkAccess);
        super.onPause();
    }

    @Override public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(checkAccess);
        super.onFragmentDestroy();
    }

    private void ensureAccess() {
        if (isFinished || isPaused || getParentLayout() == null || !isLastFragment()) return;
        if (getParentLayout().checkTransitionAnimation()) {
            AndroidUtilities.cancelRunOnUIThread(checkAccess);
            AndroidUtilities.runOnUIThread(checkAccess, 100);
            return;
        }
        if (cancelled) {
            super.finishFragment();
            return;
        }
        if (ChildgramParentalSettings.hasPin() && !authenticated) {
            if (!pinPending) {
                pinPending = true;
                presentFragment(new ChildgramParentalPinActivity(false, pin -> {
                    pinPending = false;
                    authenticated = true;
                    pin.finishFragment();
                }).setOnCancelled(() -> AndroidUtilities.runOnUIThread(() -> {
                    cancelled = true;
                    pinPending = false;
                    ensureAccess();
                })));
            }
            return;
        }
        authenticated = true;
        listView.setVisibility(View.VISIBLE);
        listView.adapter.update(false);
        if (!ChildgramParentalSettings.hasPin() && !ChildgramParentalSettings.isSetupAcknowledged() && !promptShown) {
            promptShown = true;
            showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.ChildgramParentalSetPin))
                    .setMessage(getString(R.string.ChildgramParentalWelcome))
                    .setPositiveButton(getString(R.string.ChildgramParentalSetPin), (dialog, which) -> setupPin())
                    .setNegativeButton(getString(R.string.ChildgramParentalLater), (dialog, which) -> confirmWithoutPin())
                    .create());
        }
    }

    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.ChildgramParentalBlock)));
        items.add(UItem.asCheck(1, getString(R.string.ChildgramParentalChannels)).setChecked(ChildgramParentalSettings.blockChannels()));
        items.add(UItem.asCheck(2, getString(R.string.ChildgramParentalGroups)).setChecked(ChildgramParentalSettings.blockGroups()));
        items.add(UItem.asCheck(3, getString(R.string.ChildgramParentalBots)).setChecked(ChildgramParentalSettings.blockBots()));
        items.add(UItem.asCheck(4, getString(R.string.ChildgramParentalInvites)).setChecked(ChildgramParentalSettings.blockInvites()));
        items.add(UItem.asShadow(getString(R.string.ChildgramParentalRestrictionsInfo)));
        items.add(UItem.asShadow(getString(R.string.ChildgramParentalInvitesInfo)));
        items.add(UItem.asHeader(getString(R.string.ChildgramParentalPinHeader)));
        boolean hasPin = ChildgramParentalSettings.hasPin();
        items.add(UItem.asButton(5, getString(hasPin ? R.string.ChildgramParentalChangePin : R.string.ChildgramParentalSetPin)));
        if (hasPin) items.add(UItem.asButton(6, getString(R.string.ChildgramParentalRemovePin)));
        items.add(UItem.asShadow(getString(hasPin ? R.string.ChildgramParentalPinInfo : R.string.ChildgramParentalNoPin)));
        if (!ChildgramParentalSettings.isSetupAcknowledged()) {
            items.add(UItem.asButton(7, getString(R.string.Continue)));
        }
    }

    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!authenticated || isPaused) return;
        switch (item.id) {
            case 1: ChildgramParentalSettings.setBlockChannels(!ChildgramParentalSettings.blockChannels()); break;
            case 2: ChildgramParentalSettings.setBlockGroups(!ChildgramParentalSettings.blockGroups()); break;
            case 3: ChildgramParentalSettings.setBlockBots(!ChildgramParentalSettings.blockBots()); break;
            case 4:
                ChildgramParentalSettings.setBlockInvites(!ChildgramParentalSettings.blockInvites());
                break;
            case 5: setupPin(); return;
            case 6:
                showDialog(new AlertDialog.Builder(getParentActivity())
                        .setTitle(getString(R.string.ChildgramParentalRemovePin))
                        .setMessage(getString(R.string.ChildgramParentalNoPin))
                        .setNegativeButton(getString(R.string.Cancel), null)
                        .setPositiveButton(getString(R.string.ChildgramParentalRemovePin), (dialog, which) -> {
                            if (!authenticated || isPaused) return;
                            if (!ChildgramParentalSettings.removePin()) {
                                showDialog(new AlertDialog.Builder(getParentActivity()).setMessage(getString(R.string.UnknownError))
                                        .setPositiveButton(getString(R.string.OK), null).create());
                            }
                            listView.adapter.update(false);
                        }).create());
                return;
            case 7: finishFragment(); return;
        }
        listView.adapter.update(false);
    }

    private void setupPin() {
        if (!authenticated || isPaused) return;
        presentFragment(new ChildgramParentalPinActivity(true, pin -> {
            authenticated = true;
            pin.finishFragment();
        }));
    }

    private void confirmWithoutPin() {
        if (getParentActivity() == null || !authenticated || isPaused) return;
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.ChildgramParentalWithoutPin))
                .setMessage(getString(R.string.ChildgramParentalNoPin))
                .setPositiveButton(getString(R.string.ChildgramParentalSetPin), (dialog, which) -> setupPin())
                .setNegativeButton(getString(R.string.ChildgramParentalWithoutPin), (dialog, which) -> {
                    if (!authenticated || isPaused) return;
                    ChildgramParentalSettings.setSetupAcknowledged(true);
                    super.finishFragment();
                }).create());
    }

    @Override public void finishFragment() {
        if (!ChildgramParentalSettings.hasPin() && !ChildgramParentalSettings.isSetupAcknowledged()) confirmWithoutPin();
        else super.finishFragment();
    }

    @Override public boolean onBackPressed(boolean invoked) {
        if (!ChildgramParentalSettings.hasPin() && !ChildgramParentalSettings.isSetupAcknowledged()) {
            if (invoked) confirmWithoutPin();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override public boolean isSwipeBackEnabled(android.view.MotionEvent event) { return false; }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
