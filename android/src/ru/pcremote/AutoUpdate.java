package ru.pcremote;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Brings our three apps up to the build the PC hands out, silently (SilentInstaller, Android 12+).
 * Runs when «Мой ПК» opens and every 15 minutes from RemoteService, so a fix made on the PC reaches
 * the phone without anyone touching it. Ourselves last: that install replaces this process.
 */
final class AutoUpdate {
    private static final String[][] OTHERS = {
            {"ru.pcremote.net", "pcremote-net.apk", "Интернет через ПК"},
            {"ru.pcremote.files", "pcremote-files.apk", "Проводник"}};
    private static volatile long busyUntil;

    private AutoUpdate() {}

    /** Blocking; call off the main thread. Returns true when something was handed to the installer. */
    static synchronized boolean fromPc(Context ctx) {
        if (System.currentTimeMillis() < busyUntil) return false;   // an install is still running
        SharedPreferences p = ctx.getSharedPreferences("pcremote", Context.MODE_PRIVATE);
        if (!p.contains("secret")) return false;
        Updater.pc = new Updater.Pc(p.getString("host", ""), p.getInt("port", 8443), p.getString("pin", ""), p.getString("secret", ""), p.getString("lan", ""));
        boolean any = false;
        for (String[] app : OTHERS) {
            try {
                String v = ctx.getPackageManager().getPackageInfo(app[0], 0).versionName;
                Updater.Info i = Updater.checkPc(v, app[1]);
                if (i == null) continue;
                SilentInstaller.install(ctx, Updater.downloadPc(app[1], ctx.getCacheDir(), i.sha256), app[2] + " " + i.version);
                any = true;
            } catch (Exception ignored) {}   // not installed, or nothing newer on the PC
        }
        try {
            String cur = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            Updater.Info me = Updater.checkPc(cur, Updater.ASSET);
            if (me != null) {
                SilentInstaller.install(ctx, Updater.downloadPc(Updater.ASSET, ctx.getCacheDir(), me.sha256), "Мой ПК " + me.version);
                any = true;
            }
        } catch (Exception ignored) {}
        if (any) busyUntil = System.currentTimeMillis() + 3 * 60_000;
        return any;
    }
}
