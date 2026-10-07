package ru.pcremote;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Updates inside the open app (owner, 06.10.2026: «докачка и установка внутри приложения»).
 *
 * The phone's journal of one day: 30 installs started from the background, all refused ("Permission denied",
 * Play Protect "Install not allowed"); the one started from the open app, with one tap in Android's own window,
 * went through in 9 s. So: the background only downloads, with resume, and keeps the files; the install runs here,
 * in front, one app after another — «Интернет через ПК», «Проводник», ourselves last (that install restarts us).
 * If Android's install session fails, the system installer opens the same file — the way a file manager does it,
 * which on this phone always worked.
 */
final class InAppUpdate {
    static final String[][] APPS = {
            {"ru.pcremote.net", "pcremote-net.apk", "Интернет через ПК"},
            {"ru.pcremote.files", "pcremote-files.apk", "Проводник"},
            {"ru.pcremote", "pcremote.apk", "Мой ПК"}};
    private static volatile String state = "{\"phase\":\"idle\"}";
    static volatile boolean running;
    /** final install status per asset, from InstallResult (PENDING_USER_ACTION is not final) */
    static final ConcurrentHashMap<String, int[]> results = new ConcurrentHashMap<>();

    private InAppUpdate() {}

    static String state() { return state; }

    private static void set(String phase, String label, String note, long done, long total) {
        state = "{\"phase\":\"" + phase + "\",\"app\":\"" + esc(label) + "\",\"note\":\"" + esc(note) + "\",\"done\":" + done
                + ",\"total\":" + total + ",\"running\":" + running + "}";
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static final class Item {
        String pkg, asset, label, have, pc, sha;
        long size;
    }

    private static Updater.Pc pc(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("pcremote", Context.MODE_PRIVATE);
        if (!p.contains("secret")) return null;
        Updater.pc = new Updater.Pc(p.getString("host", ""), p.getInt("port", 8443), p.getString("pin", ""), p.getString("secret", ""), p.getString("lan", ""));
        return Updater.pc;
    }

    /** What the PC has that is newer than what is installed (installed apps only; ourselves always are). */
    static List<Item> pending(Context ctx) throws Exception {
        List<Item> out = new ArrayList<>();
        if (pc(ctx) == null) return out;
        String json = new String(Updater.pcGet("/api/apk", null, null), StandardCharsets.UTF_8);
        String top = Pairing.jsonString(json, "version");
        for (String[] a : APPS) {
            String have;
            try { have = ctx.getPackageManager().getPackageInfo(a[0], 0).versionName; } catch (Exception notInstalled) { continue; }
            int i = json.indexOf("\"" + a[1] + "\"");
            if (i < 0) continue;
            int end = json.indexOf('}', i);
            String obj = json.substring(i, end < 0 ? json.length() : end);
            String v = Pairing.jsonString(obj, "version");
            if (v == null) v = top;                  // an older PC: one version for all three
            if (v == null || Updater.compare(v, have) <= 0) continue;
            Item it = new Item();
            it.pkg = a[0]; it.asset = a[1]; it.label = a[2]; it.have = have; it.pc = v;
            it.sha = Pairing.jsonString(obj, "sha256");
            try { it.size = Long.parseLong(Pairing.jsonString(obj, "size")); } catch (Exception e) { it.size = -1; }
            out.add(it);
        }
        return out;
    }

    static File fileFor(Context ctx, Item it) {
        String tag = it.sha == null || it.sha.length() < 16 ? it.pc : it.sha.substring(0, 16).toLowerCase();
        // the asset with its ".apk": "apk-pcremote-" was a prefix of "apk-pcremote-net-…" too, and cleaning up after
        // «Мой ПК» deleted the downloaded «Интернет через ПК» — 330 KB again on every try (07.10.2026)
        String prefix = "apk-" + it.asset + "-";
        File f = new File(ctx.getCacheDir(), prefix + tag + ".apk");
        File[] all = ctx.getCacheDir().listFiles();
        if (all != null) for (File o : all)   // older builds of this app: not needed any more
            if (o.getName().startsWith(prefix) && !o.getName().startsWith(f.getName())) o.delete();
        return f;
    }

    private static File download(Context ctx, Item it) throws Exception {
        Updater.Pc p = Updater.pc;
        return Fetch.get(p.lan, p.host, p.port, p.pin, p.secret, "/api/apk?name=" + it.asset, fileFor(ctx, it), it.sha,
                (done, total) -> set("download", it.label, it.have + " → " + it.pc, done, total > 0 ? total : it.size));
    }

    /**
     * The background (timer, the PC's push): download what is new, keep it, and say so once. Never installs: from the
     * background that was refused every single time. On mobile data only while the owner does not watch the screen.
     */
    static synchronized boolean prefetch(Context ctx, String why) {
        if (running || MainActivity.visible) return false;
        try {
            List<Item> items = pending(ctx);
            if (items.isEmpty()) return false;
            StringBuilder what = new StringBuilder();
            for (Item it : items) {
                download(ctx, it);
                what.append(what.length() == 0 ? "" : ", ").append(it.label).append(" ").append(it.pc);
            }
            set("idle", "", "", 0, 0);
            SharedPreferences p = ctx.getSharedPreferences("pcremote", Context.MODE_PRIVATE);
            String key = what.toString();
            if (!key.equals(p.getString("upd_ready", ""))) {
                p.edit().putString("upd_ready", key).apply();
                PhoneLog.add("update(" + why + "): downloaded " + key + ", waiting for the app to be opened");
                notifyReady(ctx, key);
            }
            return true;
        } catch (Throwable e) {
            PhoneLog.add("update(" + why + "): download: " + e);
            return false;
        }
    }

    private static void notifyReady(Context ctx, String what) {
        try {
            Intent open = new Intent(ctx, MainActivity.class).putExtra("update_now", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent tap = PendingIntent.getActivity(ctx, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("offers", "Новые версии", NotificationManager.IMPORTANCE_DEFAULT));
            nm.notify(8, new Notification.Builder(ctx, "offers").setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Обновление скачано: " + what)
                    .setContentText("Нажмите — «Мой ПК» откроется и установит, одно нажатие «Установить»")
                    .setContentIntent(tap).setAutoCancel(true).build());
        } catch (Exception e) { PhoneLog.add("notify ready: " + e); }
    }

    /** In front: download (resume) and install every app that has a newer build on the PC, ourselves last. */
    static void runAll(Activity act) {
        if (running) return;
        running = true;
        new Thread(() -> {
            Context ctx = act.getApplicationContext();
            try {
                set("check", "", "проверяю версии на ПК", 0, 0);
                List<Item> items = pending(ctx);
                if (items.isEmpty()) { set("done", "", "всё свежее", 0, 0); return; }
                for (Item it : items) {
                    File apk = download(ctx, it);
                    set("install", it.label, it.have + " → " + it.pc + ": подтвердите «Установить»", 0, 0);
                    PhoneLog.add("update(in-app): " + it.label + " " + it.have + " -> " + it.pc);
                    if (!installHere(act, ctx, it, apk)) systemInstaller(act, ctx, it, apk);
                }
                set("done", "", "готово", 0, 0);
                ctx.getSharedPreferences("pcremote", Context.MODE_PRIVATE).edit().remove("upd_ready").apply();
            } catch (Throwable e) {
                set("error", "", String.valueOf(e.getMessage()), 0, 0);
                PhoneLog.add("update(in-app): " + e);
            } finally {
                running = false;
                String s = state;
                state = s.replace("\"running\":true", "\"running\":false");
            }
        }, "in-app-update").start();
    }

    /** An install session started from the open app; Android's own window asks once. True = installed. */
    private static boolean installHere(Activity act, Context ctx, Item it, File apk) {
        results.remove(it.asset);
        try {
            SilentInstaller.install(ctx, apk, it.label + " " + it.pc, it.asset, it.pc);
        } catch (Exception e) {
            PhoneLog.add("update(in-app): session " + it.label + ": " + e);
            return false;
        }
        long until = System.currentTimeMillis() + 180_000;   // the owner may take a while to tap
        while (System.currentTimeMillis() < until) {
            int[] r = results.get(it.asset);
            if (r != null) return r[0] == PackageInstaller.STATUS_SUCCESS;
            if (installed(ctx, it)) return true;
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return installed(ctx, it);
    }

    /** The fallback: Android's installer opens the downloaded file, like a file manager would. */
    private static void systemInstaller(Activity act, Context ctx, Item it, File apk) {
        set("install", it.label, it.have + " → " + it.pc + ": установщик Android, нажмите «Установить»", 0, 0);
        PhoneLog.add("update(in-app): " + it.label + " via the system installer");
        Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { act.runOnUiThread(() -> { try { act.startActivity(view); } catch (Exception e) { PhoneLog.add("installer: " + e); } }); }
        catch (Exception e) { return; }
        long until = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < until && !installed(ctx, it)) {
            try { Thread.sleep(700); } catch (InterruptedException e) { return; }
        }
    }

    private static boolean installed(Context ctx, Item it) {
        try { return Updater.compare(ctx.getPackageManager().getPackageInfo(it.pkg, 0).versionName, it.pc) >= 0; }
        catch (Exception e) { return false; }
    }

    /** InstallResult hands every final status here. */
    static void onResult(String asset, int status) {
        if (asset != null && status != PackageInstaller.STATUS_PENDING_USER_ACTION) results.put(asset, new int[]{status});
    }
}
