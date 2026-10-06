package ru.pcremote.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * «Мой ПК» asks us to update it (and we ask it to update us): Android installs silently only an app updating
 * ANOTHER app it installed, never one updating itself. Protected by ru.pcremote.permission.PAIRING (signature).
 */
public final class NetPartnerUpdate extends BroadcastReceiver {
    private static final String MOY_ACTION = "ru.pcremote.UPDATE_PARTNER";

    @Override public void onReceive(Context ctx, Intent intent) {
        final PendingResult done = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { NetInstaller.check(app, "partner"); } catch (Throwable e) { NetInstaller.log("partner update: " + e); }
            finally { done.finish(); }
        }, "partner-update").start();
    }

    /** «Мой ПК», please update «Интернет через ПК». */
    static void askMoy(Context ctx) {
        try { ctx.sendBroadcast(new Intent(MOY_ACTION).setPackage("ru.pcremote")); } catch (Exception ignored) {}
    }
}
