package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.Collections;

/** Local conversation notification for the Android 14 emulator. */
final class ChildgramTestBubble implements AutoCloseable {
    private static final String ID = "childgram.test.usage.bubble";
    private final Context context;
    private final Intent bubbleIntent;
    private final NotificationManager notifications;
    private final ShortcutManager shortcuts;

    ChildgramTestBubble(Context targetContext, Intent bubbleIntent) {
        context = targetContext;
        this.bubbleIntent = bubbleIntent;
        notifications = context.getSystemService(NotificationManager.class);
        shortcuts = context.getSystemService(ShortcutManager.class);
    }

    void post() {
        String label = "Childgram bubble test";
        NotificationChannel channel = new NotificationChannel(ID, label, NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        notifications.createNotificationChannel(channel);

        Icon icon = Icon.createWithResource(context, context.getApplicationInfo().icon);
        Person peer = new Person.Builder().setName(label).setKey(ID).build();
        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, ID).setShortLabel(label)
                .setLongLived(true).setPerson(peer).setIcon(icon).setIntent(bubbleIntent).build();
        // Adding, unlike pushing, never evicts a real conversation shortcut if the list is full.
        if (!shortcuts.addDynamicShortcuts(Collections.singletonList(shortcut))) {
            throw new AssertionError("Android rate-limited the bubble test shortcut");
        }
        PendingIntent open = PendingIntent.getActivity(context, ID.hashCode(), bubbleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.MessagingStyle style = new Notification.MessagingStyle(new Person.Builder().setName("Me").build())
                .addMessage("Local usage check", System.currentTimeMillis(), peer);
        Notification bubble = new Notification.Builder(context, ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setCategory(Notification.CATEGORY_MESSAGE)
                .setShortcutId(ID).setStyle(style).setContentIntent(open)
                .setBubbleMetadata(new Notification.BubbleMetadata.Builder(open, icon)
                        .setDesiredHeight(400).setAutoExpandBubble(true).build())
                .build();
        notifications.notify(ID, 1, bubble);
    }

    @Override
    public void close() {
        notifications.cancel(ID, 1);
        shortcuts.removeDynamicShortcuts(Collections.singletonList(ID));
        shortcuts.removeLongLivedShortcuts(Collections.singletonList(ID));
        notifications.deleteNotificationChannel(ID);
    }
}
