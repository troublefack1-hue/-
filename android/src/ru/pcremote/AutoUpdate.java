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
    // Cross-updating (06.10.2026, journal of the owner's HyperOS phone): updating ITSELF Android asks every time
    // (status -1), updating an app WE installed earlier goes through silently (status 0). So «Мой ПК» updates
    // «Интернет через ПК» and «Проводник», and «Интернет через ПК» (1.147+) updates «Мой ПК».
    private static final String[][] OTHERS = {
            {"ru.pcremote.net", "pcremote-net.apk", "Интернет через ПК"},
            {"ru.pcremote.files", "pcremote-files.apk", "Проводник"}};
    private static volatile long busyUntil;

    private AutoUpdate() {}

    /** The same version already handed to the installer less than 6 h ago and not installed (Android wants a tap):
     *  downloading it again every 15 minutes cost ~0.8 MB each time, ~77 MB a day on mobile data. On "open" the
     *  owner is looking at the phone: try at once. */
    private static boolean recentlyTried(SharedPreferences p, String asset, String version, String why) {
        if ("open".equals(why) || "push".equals(why)) return false;
        String key = "upd_try_" + asset, was = p.getString(key, "");
        int bar = was.indexOf('|');
        if (bar > 0 && was.substring(0, bar).equals(version)) {
            try { if (System.currentTimeMillis() - Long.parseLong(was.substring(bar + 1)) < 6 * 3600_000L) return true; } catch (NumberFormatException ignored) {}
        }
        p.edit().putString(key, version + "|" + System.currentTimeMillis()).apply();
        return false;
    }

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
                if (recentlyTried(p, app[1], i.version, why)) { PhoneLog.add("update(" + why + "): " + app[2] + " " + i.version + " waits for a tap, not again yet"); continue; }
                PhoneLog.add("update(" + why + "): " + app[2] + " " + v + " -> " + i.version + ", downloading");
                SilentInstaller.install(ctx, Updater.downloadPcUnique(app[1], ctx.getCacheDir(), i.sha256), app[2] + " " + i.version);
                any = true;
            } catch (Throwable e) {
                PhoneLog.add("update(" + why + "): " + app[2] + " failed: " + e);
            }
        }
        String cur = "?";
        try {
            cur = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            String net = null;
            try { net = ctx.getPackageManager().getPackageInfo("ru.pcremote.net", 0).versionName; } catch (Exception notInstalled) {}
            if (net != null && Updater.compare(net, Updater.CROSS_SINCE) >= 0) {
                // never ourselves while it is there: our own install needs a tap here, and two installers of one
                // package at once broke each other (06.10.2026 07:45). On open/manual/partner we just ask it now.
                PhoneLog.add("update(" + why + "): Мой ПК " + cur + " is updated by Интернет через ПК " + net);
                if (!"timer".equals(why) && !"partner".equals(why)) PartnerUpdate.askNet(ctx);
                if (any) busyUntil = System.currentTimeMillis() + 3 * 60_000;
                return any;
            }
            Updater.Info me = Updater.checkPc(cur, Updater.ASSET);
            if (me == null) PhoneLog.add("update(" + why + "): Мой ПК " + cur + " is current");
            else if (recentlyTried(p, Updater.ASSET, me.version, why)) PhoneLog.add("update(" + why + "): Мой ПК " + me.version + " waits for a tap, not again yet");
            else {
                PhoneLog.add("update(" + why + "): Мой ПК " + cur + " -> " + me.version + ", downloading");
                SilentInstaller.install(ctx, Updater.downloadPcUnique(Updater.ASSET, ctx.getCacheDir(), me.sha256), "Мой ПК " + me.version);
                any = true;
            }
        } catch (Throwable e) {
            PhoneLog.add("update(" + why + "): Мой ПК " + cur + " failed: " + e);
        }
        if (any) busyUntil = System.currentTimeMillis() + 3 * 60_000;
        return any;
    }
}
