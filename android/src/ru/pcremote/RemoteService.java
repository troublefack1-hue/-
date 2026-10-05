package ru.pcremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import org.json.JSONObject;
import java.nio.ByteBuffer;

/**
 * Foreground service that keeps a WebSocket to the PC open in the background:
 *  - "ring" from the PC makes the phone sound an alarm (find my phone);
 *  - screen cast: phone screen (+ sound on Android 10+) streamed to the PC.
 * Starts at boot and whenever the app is opened after pairing.
 */
public class RemoteService extends Service {
    public static final String ACTION_START = "start", ACTION_CAST = "cast", ACTION_CAST_STOP = "cast_stop",
            ACTION_MUTE = "mute", ACTION_STOP_RING = "stop_ring";
    public static final String CHANNEL = "pcremote", CHANNEL_RING = "pcremote_ring";
    private static final int NOTIF_ID = 1;
    public static volatile boolean casting = false, phoneMuted = false;

    private SharedPreferences prefs;
    private WsClient ws;
    private volatile boolean running = true;
    private Thread keeper;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private AudioRecord audio;
    private Thread audioThread;
    private int savedVolume = -1;
    private long lastFrameAt = 0;
    private PhoneFs fs;
    private final java.util.concurrent.ExecutorService fsPool = java.util.concurrent.Executors.newSingleThreadExecutor();
    public static final String ACTION_TERM = "term";

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("pcremote", MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Связь с ПК", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel ring = new NotificationChannel(CHANNEL_RING, "Поиск телефона", NotificationManager.IMPORTANCE_HIGH);
        ring.setSound(null, null);
        nm.createNotificationChannel(ring);
        startForeground(NOTIF_ID, notification("Связь с ПК", "ожидание команд"));
        keeper = new Thread(this::keepConnected, "ws-keeper");
        keeper.setDaemon(true); keeper.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_CAST.equals(a)) startCast(intent.getIntExtra("code", 0), (Intent) intent.getParcelableExtra("data"));
        else if (ACTION_CAST_STOP.equals(a)) stopCast();
        else if (ACTION_MUTE.equals(a)) setPhoneMuted(intent.getBooleanExtra("on", false));
        else if (ACTION_STOP_RING.equals(a)) RingActivity.stop();
        else if (ACTION_TERM.equals(a)) termSend(intent.getStringExtra("id"), intent.getStringExtra("data"));
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        running = false; stopCast();
        if (ws != null) ws.close();
        super.onDestroy();
    }

    // ------------------------------------------------------ connection ---
    private final Object wake = new Object();
    private Thread nudgeThread;

    /** While our link is down, long-poll the PC's nudge channel: the PC pushes
     *  "reconnect" when it has repaired its side, and we retry at once. */
    private void startNudgeListener() {
        final String url = prefs.getString("ntfy", "");
        if (url.isEmpty() || (nudgeThread != null && nudgeThread.isAlive())) return;
        nudgeThread = new Thread(() -> {
            while (running && ws == null) {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url + "/json?since=10s").openConnection();
                    c.setConnectTimeout(8000); c.setReadTimeout(0);
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()))) {
                        String line;
                        while (running && ws == null && (line = r.readLine()) != null) {
                            if (line.contains("\"event\":\"message\"") && line.contains("reconnect")) {
                                synchronized (wake) { wake.notifyAll(); }
                            }
                        }
                    }
                } catch (Exception e) { sleep(3000); }
            }
        }, "nudge-listener");
        nudgeThread.setDaemon(true); nudgeThread.start();
    }

    private void waitOrNudge(long ms) {
        synchronized (wake) { try { wake.wait(ms); } catch (InterruptedException ignored) {} }
    }

    private void keepConnected() {
        int delay = 1000;
        while (running) {
            if (!prefs.contains("secret")) { sleep(5000); continue; }
            try {
                WsClient c = new WsClient(prefs.getString("host", ""), prefs.getInt("port", 8443), prefs.getString("pin", ""),
                        "/ws/phone", new WsClient.Listener() {
                    public void onText(String s) { onMessage(s); }
                    public void onBinary(byte[] b) { if (b.length > 0 && b[0] == 0x07 && fs != null) fs.writeChunk(b); }
                    public void onClose(String reason) {}
                });
                c.connect();
                c.sendText("{\"t\":\"auth\",\"token\":\"" + prefs.getString("secret", "") + "\"}");
                c.sendText("{\"t\":\"profile\",\"name\":\"idle\"}");   // no video for the background link
                c.sendText("{\"t\":\"hello_phone\",\"model\":\"" + Build.MODEL.replace('"', ' ') + "\",\"fs\":" + filesAllowed() + "}");
                ws = c; delay = 1000;
                final WsClient cc = c;
                fs = new PhoneFs(new PhoneFs.Sender() {
                    public void text(String j) throws java.io.IOException { cc.sendText(j); }
                    public void binary(byte[] f) throws java.io.IOException { cc.sendBinary(f); }
                });
                update("Связь с ПК", casting ? "трансляция экрана" : "подключено");
                c.run();  // blocks until closed
            } catch (Exception e) {
                update("Связь с ПК", "нет связи, повтор…");
            }
            ws = null;
            startNudgeListener();                               // the PC can wake us up early
            waitOrNudge(delay); delay = Math.min(delay * 2, 15000);
        }
    }

    private void onMessage(String s) {
        if (s.contains("\"t\":\"ring\"") || s.contains("\"t\": \"ring\"")) {
            Intent i = new Intent(this, RingActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } else if (s.startsWith("{\"t\":\"pfs\"") || s.startsWith("{\"t\": \"pfs\"")) {
            final PhoneFs f = fs;
            if (f == null) return;
            try {
                final JSONObject ev = new JSONObject(s);
                if (!filesAllowed()) {
                    WsClient c = ws;
                    if (c != null) c.sendText("{\"t\":\"pfs_r\",\"id\":\"" + ev.optString("id") + "\",\"ok\":false,\"error\":\"доступ к файлам выключен на телефоне\"}");
                    return;
                }
                fsPool.execute(() -> f.handle(ev));
            } catch (Exception ignored) {}
        } else if (s.startsWith("{\"t\":\"attention\"")) {
            try { attention(new JSONObject(s)); } catch (Exception ignored) {}
        }
    }

    /** "All files access" granted (Android 11+) or legacy storage permission, and the switch is on. */
    public boolean filesAllowed() {
        if (!prefs.getBoolean("pfs", true)) return false;
        if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
        return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Claude on the PC is waiting for an answer: show it as a notification with Yes / No buttons. */
    private void attention(JSONObject ev) {
        String term = ev.optString("term", ""), text = ev.optString("text", "Claude ждёт ответа");
        Intent open = new Intent(this, MainActivity.class);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_RING)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Claude ждёт ответа")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true);
        Intent yes = new Intent(this, RemoteService.class).setAction(ACTION_TERM).putExtra("id", term).putExtra("data", "\r");
        Intent no = new Intent(this, RemoteService.class).setAction(ACTION_TERM).putExtra("id", term).putExtra("data", "\u001b");
        b.addAction(new Notification.Action.Builder(null, "Да", PendingIntent.getService(this, 4, yes, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        b.addAction(new Notification.Action.Builder(null, "Нет", PendingIntent.getService(this, 5, no, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        getSystemService(NotificationManager.class).notify(7, b.build());
    }

    private void termSend(String id, String data) {
        WsClient c = ws;
        try {
            if (c != null && c.isOpen()) c.sendText(new JSONObject().put("t", "term_in").put("id", id).put("data", data).toString());
        } catch (Exception ignored) {}
        getSystemService(NotificationManager.class).cancel(7);
    }

    // ------------------------------------------------------------ cast ---
    private void startCast(int code, Intent data) {
        if (casting || data == null) return;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        float scale = Math.min(1f, 720f / Math.min(dm.widthPixels, dm.heightPixels));
        final int w = Math.round(dm.widthPixels * scale) & ~1, h = Math.round(dm.heightPixels * scale) & ~1;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            try {
                long now = System.currentTimeMillis();
                if (now - lastFrameAt < 80) return;  // ~12 fps max
                lastFrameAt = now;
                Image.Plane p = img.getPlanes()[0];
                int stride = p.getRowStride() / p.getPixelStride();
                Bitmap bmp = Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888);
                bmp.copyPixelsFromBuffer(p.getBuffer());
                if (stride != w) bmp = Bitmap.createBitmap(bmp, 0, 0, w, h);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(96 * 1024);
                bos.write(0x03);
                bmp.compress(Bitmap.CompressFormat.JPEG, 60, bos);
                bmp.recycle();
                WsClient c = ws;
                if (c != null && c.isOpen()) c.sendBinary(bos.toByteArray());
            } catch (Exception ignored) {
            } finally { img.close(); }
        }, null);
        display = projection.createVirtualDisplay("pcremote", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        casting = true;
        startCastAudio();
        update("Трансляция на ПК", "экран телефона идёт на ПК");
    }

    /** Phone sound -> PC. Needs Android 10+; apps may opt out (then silence). */
    private void startCastAudio() {
        if (Build.VERSION.SDK_INT < 29 || projection == null) return;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            final int rate = 16000;
            int buf = Math.max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2);
            audio = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(buf).build();
            audio.startRecording();
            audioThread = new Thread(() -> {
                byte[] chunk = new byte[rate / 20 * 2 + 3];  // 50 ms
                chunk[0] = 0x04; chunk[1] = (byte) (rate & 0xFF); chunk[2] = (byte) (rate >> 8);
                while (casting && audio != null) {
                    int n = audio.read(chunk, 3, chunk.length - 3);
                    if (n <= 0) continue;
                    WsClient c = ws;
                    try { if (c != null && c.isOpen()) c.sendBinary(n == chunk.length - 3 ? chunk : java.util.Arrays.copyOf(chunk, n + 3)); }
                    catch (Exception ignored) {}
                }
            }, "cast-audio");
            audioThread.setDaemon(true); audioThread.start();
        } catch (Exception ignored) { audio = null; }
    }

    private void stopCast() {
        if (!casting) return;
        casting = false;
        try { if (audio != null) { audio.stop(); audio.release(); } } catch (Exception ignored) {}
        audio = null;
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        display = null; reader = null; projection = null;
        setPhoneMuted(false);
        WsClient c = ws;
        try { if (c != null && c.isOpen()) c.sendText("{\"t\":\"cast_stop\"}"); } catch (Exception ignored) {}
        update("Связь с ПК", "подключено");
    }

    /** Silence the phone itself while its sound plays on the PC. */
    private void setPhoneMuted(boolean on) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (on && !phoneMuted) {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            phoneMuted = true;
        } else if (!on && phoneMuted) {
            if (savedVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            phoneMuted = false;
        }
    }

    // ---------------------------------------------------------- notifs ---
    private Notification notification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(text)
                .setContentIntent(pi).setOngoing(true);
        if (casting) {
            Intent stop = new Intent(this, RemoteService.class).setAction(ACTION_CAST_STOP);
            b.addAction(new Notification.Action.Builder(null, "Остановить трансляцию",
                    PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build());
            Intent mute = new Intent(this, RemoteService.class).setAction(ACTION_MUTE).putExtra("on", !phoneMuted);
            b.addAction(new Notification.Action.Builder(null, phoneMuted ? "Звук на телефоне" : "Заглушить телефон",
                    PendingIntent.getService(this, 2, mute, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        return b.build();
    }

    private void update(String title, String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification(title, text));
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    public static void ensureRunning(Context ctx) {
        Intent i = new Intent(ctx, RemoteService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }
}
