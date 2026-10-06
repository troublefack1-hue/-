package ru.pcremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Where a SilentInstaller session reports: done, failed, or Android wants the owner to confirm. */
public final class InstallResult extends BroadcastReceiver {
    private static final String CHANNEL = "updates";

    @Override public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String label = intent.getStringExtra("label");
        if (label == null) label = "приложение";
        PhoneLog.add("install " + label + ": status " + status + " " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        InAppUpdate.onResult(intent.getStringExtra("asset"), status);   // the open app's updater waits for this
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // not silent this time (first install, or the app came from elsewhere): Android's own screen, once
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(confirm); } catch (Exception ignored) {}
            notify(ctx, label + ": подтвердите установку", "Android просит подтвердить один раз, дальше обновления пойдут сами",
                    PendingIntent.getActivity(ctx, 1, confirm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(ctx, label + " обновлено", Toast.LENGTH_SHORT).show();
        } else {
            // the phone refused this build (HyperOS: "Permission denied", Play Protect: VERIFICATION_FAILURE): it will
            // refuse it again — no background retries of it, only the owner's button
            String asset = intent.getStringExtra("asset"), version = intent.getStringExtra("version");
            if (asset != null && version != null)
                ctx.getSharedPreferences("pcremote", Context.MODE_PRIVATE).edit()
                        .putString("upd_fail_" + asset, version + "|" + System.currentTimeMillis()).apply();
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (!InAppUpdate.running)   // the in-app updater goes on with Android's installer instead: no alarm
                Toast.makeText(ctx, label + ": не установилось — " + (msg == null ? "код " + status : msg), Toast.LENGTH_LONG).show();
        }
    }

    private static void notify(Context ctx, String title, String text, PendingIntent tap) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_HIGH));
        nm.notify(7, new Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title).setContentText(text).setContentIntent(tap).setAutoCancel(true).build());
    }
}
