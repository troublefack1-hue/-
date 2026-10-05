package ru.pcremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget: wake the PC, put it to sleep, lock it, open the app — without opening anything. */
public class PcWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
            v.setOnClickPendingIntent(R.id.w_wake, service(ctx, 10, RemoteService.ACTION_WAKE, null));
            v.setOnClickPendingIntent(R.id.w_sleep, service(ctx, 11, RemoteService.ACTION_CMD, "sleep"));
            v.setOnClickPendingIntent(R.id.w_lock, service(ctx, 12, RemoteService.ACTION_CMD, "lock"));
            Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.setOnClickPendingIntent(R.id.w_open, PendingIntent.getActivity(ctx, 13, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            mgr.updateAppWidget(id, v);
        }
    }

    private static PendingIntent service(Context ctx, int req, String action, String cmd) {
        Intent i = new Intent(ctx, RemoteService.class).setAction(action);
        if (cmd != null) i.putExtra("cmd", cmd);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return android.os.Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(ctx, req, i, flags) : PendingIntent.getService(ctx, req, i, flags);
    }
}
