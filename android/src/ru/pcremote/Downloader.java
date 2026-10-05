package ru.pcremote;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Files from the PC go to Downloads/PC Remote through the local tunnel, streamed, any size. */
public final class Downloader {
    private static int nextId = 200;

    public static void start(Context ctx, String url, String name, long size) {
        final int id = nextId++;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (android.os.Build.VERSION.SDK_INT >= 29 && !Environment.isExternalStorageManager()) {
                // scoped storage without "all files" access: go through MediaStore so the file lands in Downloads
                try { viaMediaStore(app, nm, id, url, safe, size); } catch (Exception e) {
                    nm.notify(id, new Notification.Builder(app, RemoteService.CHANNEL_PC).setSmallIcon(R.drawable.ic_launcher)
                            .setContentTitle("Не удалось скачать " + safe).setContentText(String.valueOf(e.getMessage())).setAutoCancel(true).build());
                }
                return;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PC Remote");
            if (!dir.isDirectory() && !dir.mkdirs()) dir = new File(app.getExternalFilesDir(null), "downloads");
            if (!dir.isDirectory()) dir.mkdirs();
            File out = new File(dir, safe);
            int n = 1;
            while (out.exists()) { int dot = safe.lastIndexOf('.'); out = new File(dir, dot > 0 ? safe.substring(0, dot) + " (" + n + ")" + safe.substring(dot) : safe + " (" + n + ")"); n++; }
            File part = new File(out.getPath() + ".part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(60000);
                long total = size > 0 ? size : c.getContentLengthLong();
                long done = 0, lastShown = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream f = new FileOutputStream(part)) {
                    byte[] buf = new byte[256 * 1024]; int k;
                    while ((k = in.read(buf)) > 0) {
                        f.write(buf, 0, k); done += k;
                        if (System.currentTimeMillis() - lastShown > 700) {
                            lastShown = System.currentTimeMillis();
                            Notification.Builder b = new Notification.Builder(app, RemoteService.CHANNEL)
                                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Скачиваю " + safe).setOngoing(true).setOnlyAlertOnce(true);
                            if (total > 0) b.setProgress(100, (int) (done * 100 / total), false).setContentText((done >> 20) + " / " + (total >> 20) + " МБ");
                            else b.setProgress(0, 0, true).setContentText((done >> 20) + " МБ");
                            nm.notify(id, b.build());
                        }
                    }
                }
                if (!part.renameTo(out)) throw new java.io.IOException("rename failed");
                nm.notify(id, new Notification.Builder(app, RemoteService.CHANNEL_PC).setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Скачано: " + out.getName()).setContentText(out.getParent()).setAutoCancel(true).build());
            } catch (Exception e) {
                part.delete();
                nm.notify(id, new Notification.Builder(app, RemoteService.CHANNEL_PC).setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle("Не удалось скачать " + safe).setContentText(String.valueOf(e.getMessage())).setAutoCancel(true).build());
            }
        }, "download").start();
        Toast.makeText(ctx, "Скачиваю в Загрузки/PC Remote", Toast.LENGTH_SHORT).show();
    }

    private static void viaMediaStore(Context app, NotificationManager nm, int id, String url, String safe, long size) throws Exception {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe);
        v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PC Remote");
        v.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
        android.content.ContentResolver cr = app.getContentResolver();
        android.net.Uri item = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (item == null) throw new java.io.IOException("MediaStore insert failed");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        long total = size > 0 ? size : c.getContentLengthLong(), done = 0, lastShown = 0;
        try (InputStream in = c.getInputStream(); java.io.OutputStream f = cr.openOutputStream(item)) {
            byte[] buf = new byte[256 * 1024]; int k;
            while ((k = in.read(buf)) > 0) {
                f.write(buf, 0, k); done += k;
                if (System.currentTimeMillis() - lastShown > 700) {
                    lastShown = System.currentTimeMillis();
                    Notification.Builder b = new Notification.Builder(app, RemoteService.CHANNEL)
                            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Скачиваю " + safe).setOngoing(true).setOnlyAlertOnce(true);
                    if (total > 0) b.setProgress(100, (int) (done * 100 / total), false).setContentText((done >> 20) + " / " + (total >> 20) + " МБ");
                    else b.setProgress(0, 0, true).setContentText((done >> 20) + " МБ");
                    nm.notify(id, b.build());
                }
            }
        } catch (Exception e) { cr.delete(item, null, null); throw e; }
        v.clear(); v.put(android.provider.MediaStore.Downloads.IS_PENDING, 0); cr.update(item, v, null, null);
        nm.notify(id, new Notification.Builder(app, RemoteService.CHANNEL_PC).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Скачано: " + safe).setContentText("Загрузки/PC Remote").setAutoCancel(true).build());
    }
}
