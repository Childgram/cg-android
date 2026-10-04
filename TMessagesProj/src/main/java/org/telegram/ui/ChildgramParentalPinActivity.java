package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChildgramParentalSettings;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CustomPhoneKeyboardView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.function.Consumer;

/** A separate parental PIN using Telegram's existing PIN fields and numeric keyboard. */
public class ChildgramParentalPinActivity extends BaseFragment {
    private final boolean setup;
    private final Consumer<ChildgramParentalPinActivity> onSuccess;
    private Runnable onCancelled;
    private CodeFieldContainer fields;
    private CustomPhoneKeyboardView keyboard;
    private TextView title;
    private TextView error;
    private String firstPin;
    private boolean completed;
    private boolean processing;
    private boolean needsUnlock;
    private boolean wasPaused;
    private volatile int requestSerial;
    private final Runnable pendingSubmission = this::submit;
    private Runnable pendingResult;

    public ChildgramParentalPinActivity(boolean setup, Consumer<ChildgramParentalPinActivity> onSuccess) {
        this.setup = setup;
        this.onSuccess = onSuccess;
    }

    public ChildgramParentalPinActivity setOnCancelled(Runnable onCancelled) {
        this.onCancelled = onCancelled;
        return this;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.ChildgramParentalTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = content;

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setGravity(Gravity.CENTER);
        form.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        scroll.addView(form, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        title = text(context, 22, Theme.key_windowBackgroundWhiteBlackText);
        title.setTypeface(AndroidUtilities.bold());
        updateTitle();
        form.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        TextView hint = text(context, 16, Theme.key_windowBackgroundWhiteGrayText2);
        hint.setText(LocaleController.getString(R.string.ChildgramParentalPinHint));
        form.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        keyboard = new CustomPhoneKeyboardView(context);
        fields = new CodeFieldContainer(context) {
            @Override
            protected void processNextPressed() {
                submit();
            }
        };
        fields.setNumbersCount(4, CodeFieldContainer.TYPE_PASSCODE);
        for (CodeNumberField field : fields.codeField) {
            field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            field.setShowSoftInputOnFocusCompat(false);
            field.setTransformationMethod(PasswordTransformationMethod.getInstance());
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
            field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            field.setSaveEnabled(false);
            field.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    keyboard.setEditText(field);
                    keyboard.setDispatchBackWhenEmpty(true);
                }
            });
        }
        form.addView(fields, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        error = text(context, 14, Theme.key_text_RedRegular);
        form.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));
        content.addView(keyboard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, CustomPhoneKeyboardView.KEYBOARD_HEIGHT_DP));
        return fragmentView;
    }

    private TextView text(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(color));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private void submit() {
        AndroidUtilities.cancelRunOnUIThread(pendingSubmission);
        if (isPaused || isFinished || completed || processing || fields.getCode().length() != 4 || getParentLayout() == null || !isLastFragment()) {
            return;
        }
        if (getParentLayout().checkTransitionAnimation()) {
            AndroidUtilities.runOnUIThread(pendingSubmission, 100);
            return;
        }
        processing = true;
        String pin = fields.getCode();
        boolean settingPin = setup && !needsUnlock;
        if (settingPin && firstPin == null) {
            firstPin = pin;
            title.setText(LocaleController.getString(R.string.ChildgramParentalPinRepeat));
            error.setText("");
            clearFields();
        } else if (settingPin && !firstPin.equals(pin)) {
            firstPin = null;
            title.setText(LocaleController.getString(R.string.ChildgramParentalPinCreate));
            error.setText(LocaleController.getString(R.string.ChildgramParentalPinMismatch));
            clearFields();
        } else {
            setInputEnabled(false);
            int serial = ++requestSerial;
            Utilities.globalQueue.postRunnable(() -> {
                if (serial != requestSerial) return;
                boolean success = settingPin ? ChildgramParentalSettings.setPin(pin) : ChildgramParentalSettings.verifyPin(pin);
                long retry = ChildgramParentalSettings.getPinRetryMs();
                AndroidUtilities.runOnUIThread(() -> dispatchResult(serial, settingPin, success, retry));
            });
            return;
        }
        processing = false;
    }

    private void dispatchResult(int serial, boolean settingPin, boolean success, long retry) {
        if (serial != requestSerial || isPaused || isFinished) {
            // A PIN saved during a pause also requires authentication on return.
            if (!isFinished && setup && ChildgramParentalSettings.hasPin()) {
                needsUnlock = true;
                firstPin = null;
                clearFields();
                updateTitle();
            }
            return;
        }
        if (getParentLayout() == null || !isLastFragment()) return;
        if (getParentLayout().checkTransitionAnimation()) {
            pendingResult = () -> dispatchResult(serial, settingPin, success, retry);
            AndroidUtilities.runOnUIThread(pendingResult, 100);
            return;
        }
        pendingResult = null;
        if (success && needsUnlock) {
            processing = false;
            needsUnlock = false;
            clearFields();
            error.setText("");
            updateTitle();
        } else if (success) {
            error.setText("");
            for (CodeNumberField field : fields.codeField) {
                field.animateSuccessProgress(1f);
            }
            pendingResult = () -> finishSuccess(serial);
            AndroidUtilities.runOnUIThread(pendingResult, 350);
        } else {
            processing = false;
            clearFields();
            error.setText(retry > 0 ? LocaleController.formatString("TooManyTries", R.string.TooManyTries,
                    LocaleController.formatPluralString("Seconds", Math.max(1, (int) Math.ceil(retry / 1000.0)))) :
                    LocaleController.getString(settingPin ? R.string.UnknownError : R.string.ChildgramParentalPinWrong));
            AndroidUtilities.shakeView(fields);
        }
    }

    private void finishSuccess(int serial) {
        if (serial != requestSerial || isPaused || isFinished || getParentLayout() == null || !isLastFragment()) return;
        if (getParentLayout().checkTransitionAnimation()) {
            AndroidUtilities.runOnUIThread(pendingResult, 100);
            return;
        }
        pendingResult = null;
        completed = true;
        firstPin = null;
        if (onSuccess != null) onSuccess.accept(this);
        else finishFragment();
    }

    private void cancelPending() {
        requestSerial++;
        AndroidUtilities.cancelRunOnUIThread(pendingSubmission);
        if (pendingResult != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingResult);
            pendingResult = null;
        }
    }

    private void updateTitle() {
        title.setText(LocaleController.getString(!setup || needsUnlock ? R.string.ChildgramParentalPinEnter :
                firstPin == null ? R.string.ChildgramParentalPinCreate : R.string.ChildgramParentalPinRepeat));
    }

    private void clearFields() {
        fields.ignoreOnTextChange = true;
        for (CodeNumberField field : fields.codeField) {
            field.setText("");
            field.animateSuccessProgress(0f);
        }
        fields.ignoreOnTextChange = false;
        if (!isPaused && !isFinished) {
            setInputEnabled(true);
            fields.codeField[0].requestFocus();
        }
    }

    private void setInputEnabled(boolean enabled) {
        for (CodeNumberField field : fields.codeField) {
            field.setEnabled(enabled);
            field.setFocusable(enabled);
            field.setFocusableInTouchMode(enabled);
        }
        if (!enabled) keyboard.setEditText(null);
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAltFocusable(getParentActivity(), classGuid);
        if (fields != null) {
            if (setup && wasPaused && ChildgramParentalSettings.hasPin()) {
                needsUnlock = true;
                updateTitle();
            }
            setInputEnabled(true);
            fields.codeField[0].requestFocus();
            AndroidUtilities.hideKeyboard(fields.codeField[0]);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.removeAltFocusable(getParentActivity(), classGuid);
        cancelPending();
        processing = false;
        wasPaused = true;
        firstPin = null;
        if (fields != null && !completed) {
            clearFields();
            if (setup) {
                updateTitle();
            }
        }
    }

    @Override
    public boolean canBeginSlide() {
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        cancelPending();
        firstPin = null;
        if (fields != null) clearFields();
        if (!completed && onCancelled != null) {
            Runnable callback = onCancelled;
            onCancelled = null;
            callback.run();
        }
    }
}
