package ru.pcremote;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Brings our three apps up to the build the PC hands out, silently (SilentInstaller, Android 12+).
 * Runs when «Мой ПК» opens and every 15 minutes from RemoteService, so a fix made on the PC reaches
 * the phone without anyone touching it. Ourselves last: that install replaces this process.
 * Every step goes to PhoneLog: with the screen off this is the only way to see what happened.
 */
final class AutoUpdate {
    private static final String[][] OTHERS = {
            {"ru.pcremote.net", "pcremote-net.apk", "Интернет через ПК"},
            {"ru.pcremote.files", "pcremote-files.apk", "Проводник"}};
    private static volatile long busyUntil;

    private AutoUpdate() {}

    /** Blocking; call off the main thread. Returns true when something was handed to the installer. */
    static synchronized boolean fromPc(Context ctx, String why) {
        if (System.currentTimeMillis() < busyUntil) { PhoneLog.add("update(" + why + "): previous install still running, skip"); return false; }
        SharedPreferences p = ctx.getSharedPreferences("pcremote", Context.MODE_PRIVATE);
        if (!p.contains("secret")) return false;
        Updater.pc = new Updater.Pc(p.getString("host", ""), p.getInt("port", 8443), p.getString("pin", ""), p.getString("secret", ""), p.getString("lan", ""));
        boolean any = false;
        for (String[] app : OTHERS) {
            String v;
            try { v = ctx.getPackageManager().getPackageInfo(app[0], 0).versionName; }
            catch (Exception notInstalled) { continue; }
            try {
                Updater.Info i = Updater.checkPc(v, app[1]);
                if (i == null) { PhoneLog.add("update(" + why + "): " + app[2] + " " + v + " is current"); continue; }
                PhoneLog.add("update(" + why + "): " + app[2] + " " + v + " -> " + i.version + ", downloading");
                SilentInstaller.install(ctx, Updater.downloadPc(app[1], ctx.getCacheDir(), i.sha256), app[2] + " " + i.version);
                any = true;
            } catch (Throwable e) {
                PhoneLog.add("update(" + why + "): " + app[2] + " failed: " + e);
            }
        }
        String cur = "?";
        try {
            cur = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            Updater.Info me = Updater.checkPc(cur, Updater.ASSET);
            if (me == null) PhoneLog.add("update(" + why + "): Мой ПК " + cur + " is current");
            else {
                PhoneLog.add("update(" + why + "): Мой ПК " + cur + " -> " + me.version + ", downloading");
                SilentInstaller.install(ctx, Updater.downloadPc(Updater.ASSET, ctx.getCacheDir(), me.sha256), "Мой ПК " + me.version);
                any = true;
            }
        } catch (Throwable e) {
            PhoneLog.add("update(" + why + "): Мой ПК " + cur + " failed: " + e);
        }
        if (any) busyUntil = System.currentTimeMillis() + 3 * 60_000;
        return any;
    }
}
