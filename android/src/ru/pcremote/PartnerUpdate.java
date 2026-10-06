package ru.pcremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * «Интернет через ПК» asks us to update it (and we ask it to update us): on this owner's HyperOS phone Android
 * installs silently only an app updating ANOTHER app it installed, never one updating itself. Protected by the
 * signature permission ru.pcremote.permission.PAIRING: only our own apps can send this.
 */
public final class PartnerUpdate extends BroadcastReceiver {
    static final String ACTION = "ru.pcremote.UPDATE_PARTNER";
    private static final String NET_ACTION = "ru.pcremote.net.UPDATE_PARTNER";

    @Override public void onReceive(Context ctx, Intent intent) {
        final PendingResult done = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { AutoUpdate.fromPc(app, "partner"); } catch (Throwable e) { PhoneLog.add("partner update: " + e); }
            finally { done.finish(); }
        }, "partner-update").start();
    }

    /** «Интернет через ПК», please update «Мой ПК» (you can do it without a tap; we cannot). */
    static void askNet(Context ctx) {
        try { ctx.sendBroadcast(new Intent(NET_ACTION).setPackage("ru.pcremote.net")); } catch (Exception ignored) {}
    }
}
