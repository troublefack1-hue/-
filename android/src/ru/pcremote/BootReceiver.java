package ru.pcremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the background link to the PC up after the phone boots. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (ctx.getSharedPreferences("pcremote", Context.MODE_PRIVATE).contains("secret")) {
            RemoteService.ensureRunning(ctx);
        }
    }
}
