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
        String failed = p.getString("upd_fail_" + asset, "");
        if (!"manual".equals(why) && failed.startsWith(version + "|")) return true;   // refused by the phone: button only
        if ("open".equals(why) || "push".equals(why)) return false;
        String key = "upd_try_" + asset, was = p.getString(key, "");
        int bar = was.indexOf('|');
        if (bar > 0 && was.substring(0, bar).equals(version)) {
            try { if (System.currentTimeMillis() - Long.parseLong(was.substring(bar + 1)) < 6 * 3600_000L) return true; } catch (NumberFormatException ignored) {}
        }
        p.edit().putString(key, version + "|" + System.currentTimeMillis()).apply();
        return false;
    }

    /** Downloads only on Wi-Fi or by the owner's button: on mobile data every build is 70-330 KB the owner did not
     *  ask for (and on a 12 KB/s link half a minute of a frozen screen). There a notification offers it instead. */
    private static boolean mayDownload(Context ctx, String why) {
        return "manual".equals(why) || !Updater.metered(ctx);
    }

    /** Once per build: "a new version is on the PC, tap to install" — the tap runs the manual update. */
    private static void offer(Context ctx, SharedPreferences p, String label, String version) {
        if (version.equals(p.getString("upd_offered", ""))) return;
        p.edit().putString("upd_offered", version).apply();
        PhoneLog.add("update: " + label + " " + version + " offered (mobile data: no download without a tap)");
        try {
            android.content.Intent open = new android.content.Intent(ctx, MainActivity.class)
                    .putExtra("update_now", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent tap = android.app.PendingIntent.getActivity(ctx, 3, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel("offers", "Новые версии", android.app.NotificationManager.IMPORTANCE_LOW));
            nm.notify(8, new android.app.Notification.Builder(ctx, "offers").setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Новая версия " + version + " на ПК")
                    .setContentText("Нажмите — скачаю и установлю. Сам по мобильной сети не качаю.")
                    .setContentIntent(tap).setAutoCancel(true).build());
        } catch (Exception e) { PhoneLog.add("offer: " + e); }
    }

    /** Blocking; call off the main thread. The background (timer, push, partner) only downloads — an install from
     *  there was refused every time on the owner's phone; the open app installs (InAppUpdate.runAll). */
    static synchronized boolean fromPc(Context ctx, String why) {
        if (!"manual".equals(why) && !"open".equals(why)) return InAppUpdate.prefetch(ctx, why);
        return fromPcOld(ctx, why);
    }

    /** The old way (kept for a phone without the open activity at hand): an install session per app. */
    static synchronized boolean fromPcOld(Context ctx, String why) {
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
                if (!mayDownload(ctx, why)) { offer(ctx, p, app[2], i.version); continue; }
                if (recentlyTried(p, app[1], i.version, why)) { PhoneLog.add("update(" + why + "): " + app[2] + " " + i.version + " waits for a tap or was refused, not again yet"); continue; }
                PhoneLog.add("update(" + why + "): " + app[2] + " " + v + " -> " + i.version + ", downloading");
                SilentInstaller.install(ctx, Updater.downloadPcCached(app[1], ctx.getCacheDir(), i.sha256), app[2] + " " + i.version, app[1], i.version);
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
            boolean background = "timer".equals(why) || "partner".equals(why) || "push".equals(why);
            if (net != null && Updater.compare(net, Updater.CROSS_SINCE) >= 0 && background) {
                // in the background we leave ourselves to «Интернет через ПК» (a self-install needs a tap here);
                // when the owner opens the app we install ourselves — HyperOS aborted every cross-install of this
                // package ("Permission denied", 06.10.2026 10:25-11:03), the tap on open is the way that works
                PhoneLog.add("update(" + why + "): Мой ПК " + cur + " is left to Интернет через ПК " + net);
                if (any) busyUntil = System.currentTimeMillis() + 3 * 60_000;
                return any;
            }
            Updater.Info me = Updater.checkPc(cur, Updater.ASSET);
            if (me == null) PhoneLog.add("update(" + why + "): Мой ПК " + cur + " is current");
            else if (!mayDownload(ctx, why)) offer(ctx, p, "Мой ПК", me.version);
            else if (recentlyTried(p, Updater.ASSET, me.version, why)) PhoneLog.add("update(" + why + "): Мой ПК " + me.version + " waits for a tap or was refused, not again yet");
            else {
                PhoneLog.add("update(" + why + "): Мой ПК " + cur + " -> " + me.version + ", downloading");
                SilentInstaller.install(ctx, Updater.downloadPcCached(Updater.ASSET, ctx.getCacheDir(), me.sha256), "Мой ПК " + me.version, Updater.ASSET, me.version);
                any = true;
            }
        } catch (Throwable e) {
            PhoneLog.add("update(" + why + "): Мой ПК " + cur + " failed: " + e);
        }
        if (any) busyUntil = System.currentTimeMillis() + 3 * 60_000;
        return any;
    }
}
