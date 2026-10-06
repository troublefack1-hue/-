package ru.pcremote.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ru.pcremote.Updater;

/**
 * «Интернет через ПК» keeps itself up to date on its own, without «Мой ПК»: when it opens and every 15 minutes
 * while the tunnel runs it asks the paired PC for a newer build and installs it through a PackageInstaller
 * session (no question on Android 12+ when it updates itself; otherwise Android asks once). The update restarts
 * the process, BootReceiver (MY_PACKAGE_REPLACED) brings the tunnel back. Every step goes to
 * /sdcard/Download/PCRemote/net-log.txt.
 */
public final class NetInstaller extends BroadcastReceiver {
    private static final String ASSET = "pcremote-net.apk", ACTION = "ru.pcremote.net.INSTALL_RESULT";

    static void log(String msg) {
        // to the PC first: this app has no "all files" access, the file below usually cannot be written
        final String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
        Thread t = new Thread(() -> Updater.pcLog("net", line), "netlog"); t.setDaemon(true); t.start();
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download/PCRemote");
            if (dir.isDirectory() || dir.mkdirs()) {
                File f = new File(dir, "net-log.txt");
                if (f.length() > 256 * 1024) f.delete();
                try (FileOutputStream o = new FileOutputStream(f, true)) {
                    o.write((new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + msg + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignored) {}
    }

    /** Blocking, off the main thread. why = "open" (the owner is looking: no backoff) or "timer".
     *  Updates «Мой ПК» (silent once we installed it: Android asks only for an app updating ITSELF), then ourselves,
     *  unless «Мой ПК» 1.147+ is there to do that silently. */
    static synchronized void check(Context ctx, String why) {
        String moy = null;
        try { moy = ctx.getPackageManager().getPackageInfo("ru.pcremote", 0).versionName; } catch (Exception notInstalled) {}
        SharedPreferences p0 = ctx.getSharedPreferences("pcnet", Context.MODE_PRIVATE);
        if (p0.contains("secret") && moy != null) {
            try {
                Updater.pc = new Updater.Pc(p0.getString("host", ""), p0.getInt("port", 8443), p0.getString("pin", ""), p0.getString("secret", ""), p0.getString("lan", ""));
                Updater.Info m = Updater.checkPc(moy, "pcremote.apk");
                if (m == null) log("update(" + why + "): Мой ПК " + moy + " is current");
                else if (!backoff(p0, "upd_try_moy", m.version, why)) {
                    log("update(" + why + "): Мой ПК " + moy + " -> " + m.version + ", downloading");
                    install(ctx, Updater.downloadPcUnique("pcremote.apk", ctx.getCacheDir(), m.sha256), "Мой ПК " + m.version);
                }
            } catch (Throwable e) { log("update(" + why + "): Мой ПК failed: " + e); }
            if (Updater.compare(moy, Updater.CROSS_SINCE) >= 0) {
                // never ourselves while «Мой ПК» 1.147+ is there: it updates us without a tap, and two installers
                // of one package at once broke each other (06.10.2026 07:45: ours aborted, "Permission denied")
                if (!"timer".equals(why) && !"partner".equals(why)) NetPartnerUpdate.askMoy(ctx);
                return;
            }
        }
        checkSelf(ctx, why);
    }

    /** true = the same version was handed to the installer less than 6 h ago (Android wants a tap): wait. */
    private static boolean backoff(SharedPreferences p, String key, String version, String why) {
        String was = p.getString(key, "");
        if (!"open".equals(why) && was.startsWith(version + "|")) {
            try { if (System.currentTimeMillis() - Long.parseLong(was.substring(was.indexOf('|') + 1)) < 6 * 3600_000L) return true; }
            catch (NumberFormatException ignored) {}
        }
        p.edit().putString(key, version + "|" + System.currentTimeMillis()).apply();
        return false;
    }

    private static void checkSelf(Context ctx, String why) {
        SharedPreferences p = ctx.getSharedPreferences("pcnet", Context.MODE_PRIVATE);
        if (!p.contains("secret")) return;
        String cur = "?";
        try {
            Updater.pc = new Updater.Pc(p.getString("host", ""), p.getInt("port", 8443), p.getString("pin", ""), p.getString("secret", ""), p.getString("lan", ""));
            cur = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            Updater.Info i = Updater.checkPc(cur, ASSET);
            if (i == null) { log("update(" + why + "): " + cur + " is current"); return; }
            if (backoff(p, "upd_try", i.version, why)) return;
            log("update(" + why + "): " + cur + " -> " + i.version + ", downloading");
            install(ctx, Updater.downloadPcUnique(ASSET, ctx.getCacheDir(), i.sha256), "Интернет через ПК " + i.version);
        } catch (Throwable e) {
            log("update(" + why + "): " + cur + " failed: " + e);
        }
    }

    private static void install(Context ctx, File apk, String version) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent result = new Intent(ctx, NetInstaller.class).setAction(ACTION).putExtra("v", version);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(ctx, id, result, flags).getIntentSender());
            log("install " + version + ": session " + id + " committed (" + apk.length() + " bytes, sdk " + Build.VERSION.SDK_INT + ")");
        } catch (Exception e) {
            try { pi.abandonSession(id); } catch (Exception ignored) {}
            throw e;
        } finally {
            if (apk.getName().startsWith("upd-")) apk.delete();   // the session holds its own copy now
        }
    }

    /** The session's answer: done, failed, or Android wants the owner's tap (notification + its screen). */
    @Override public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String v = intent.getStringExtra("v");
        log("install " + v + ": status " + status + " " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return;
        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm == null) return;
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(confirm); } catch (Exception ignored) {}
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("updates", "Обновления", NotificationManager.IMPORTANCE_HIGH));
        nm.notify(8, new Notification.Builder(ctx, "updates").setSmallIcon(ctx.getApplicationInfo().icon)
                .setContentTitle(v + ": подтвердите установку").setContentText("Android просит подтвердить обновление")
                .setContentIntent(PendingIntent.getActivity(ctx, 2, confirm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).build());
    }
}
