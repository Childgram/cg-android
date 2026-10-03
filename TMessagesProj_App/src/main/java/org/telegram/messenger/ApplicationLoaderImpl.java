package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;
import java.io.File;
import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.Components.CustomUpdateAppAlertDialog;
import org.telegram.ui.Components.CustomUpdateLayout;
import org.telegram.ui.IUpdateLayout;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public boolean isCustomUpdate() {
        return org.telegram.messenger.BuildConfig.CHILDGRAM && !org.telegram.messenger.BuildConfig.CHILDGRAM_DEV;
    }

    @Override
    public void checkUpdate(boolean force, Runnable done) {
        if (isCustomUpdate()) ChildgramUpdateController.getInstance().check(force, done);
    }

    @Override
    public boolean updateCheckFailed() {
        return isCustomUpdate() && ChildgramUpdateController.getInstance().checkFailed();
    }

    @Override
    public BetaUpdate getUpdate() {
        return isCustomUpdate() ? ChildgramUpdateController.getInstance().getUpdate() : null;
    }

    @Override
    public void downloadUpdate() {
        if (isCustomUpdate()) ChildgramUpdateController.getInstance().download();
    }

    @Override
    public void cancelDownloadingUpdate() {
        if (isCustomUpdate()) ChildgramUpdateController.getInstance().cancelDownload();
    }

    @Override
    public boolean isDownloadingUpdate() {
        return isCustomUpdate() && ChildgramUpdateController.getInstance().isDownloading();
    }

    @Override
    public float getDownloadingUpdateProgress() {
        return isCustomUpdate() ? ChildgramUpdateController.getInstance().getProgress() : 0;
    }

    @Override
    public File getDownloadedUpdateFile() {
        return isCustomUpdate() ? ChildgramUpdateController.getInstance().getDownloadedFile() : null;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup container) {
        return isCustomUpdate() ? new CustomUpdateLayout(activity, container) : null;
    }

    @Override
    public boolean showCustomUpdateAppPopup(Context context, BetaUpdate update, int account) {
        if (!isCustomUpdate()) return false;
        new CustomUpdateAppAlertDialog(context, update, account).show();
        return true;
    }
}
