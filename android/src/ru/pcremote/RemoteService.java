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
    public static final String CHANNEL = "pcremote", CHANNEL_RING = "pcremote_ring", CHANNEL_PC = "pcremote_pc";
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
    private volatile boolean lockedOut = false;
    private final java.util.concurrent.ExecutorService fsPool = java.util.concurrent.Executors.newSingleThreadExecutor();
    public static final String ACTION_TERM = "term", ACTION_WAKE = "wake", ACTION_CMD = "cmd";

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("pcremote", MODE_PRIVATE);
        CellularLink.install(this);   // mobile data as the last road when Wi-Fi cannot reach the PC
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Связь с ПК", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel ring = new NotificationChannel(CHANNEL_RING, "Поиск телефона", NotificationManager.IMPORTANCE_HIGH);
        ring.setSound(null, null);
        nm.createNotificationChannel(ring);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_PC, "Уведомления с ПК", NotificationManager.IMPORTANCE_DEFAULT));
        startForeground(NOTIF_ID, notification("Связь с ПК", "ожидание команд"));
        keeper = new Thread(this::keepConnected, "ws-keeper");
        Thread pw = new Thread(this::watchPaths, "paths"); pw.setDaemon(true); pw.start();
        keeper.setDaemon(true); keeper.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_CAST.equals(a)) startCast(intent.getIntExtra("code", 0), (Intent) intent.getParcelableExtra("data"));
        else if (ACTION_CAST_STOP.equals(a)) stopCast();
        else if (ACTION_MUTE.equals(a)) setPhoneMuted(intent.getBooleanExtra("on", false));
        else if (ACTION_STOP_RING.equals(a)) RingActivity.stop();
        else if (ACTION_TERM.equals(a)) termSend(intent.getStringExtra("id"), intent.getStringExtra("data"));
        else if (ACTION_WAKE.equals(a)) wake(this);
        else if (ACTION_CMD.equals(a)) sendCmd(intent.getStringExtra("cmd"));
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
                    c.setConnectTimeout(8000); c.setReadTimeout(120000);   // ntfy keeps the stream alive every ~45 s; a dead TCP must not hang us forever
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()))) {
                        String line;
                        while (running && ws == null && (line = r.readLine()) != null) {
                            if (line.contains("\"event\":\"message\"") && line.contains("reconnect")) {
                                String msg = Pairing.jsonString(line, "message");
                                int k = msg == null ? -1 : msg.indexOf("ip=");
                                if (k >= 0) {   // the PC's public address changed: follow it
                                    String ip = msg.substring(k + 3).trim();
                                    if (ip.matches("[0-9.]{7,15}") && !ip.equals(prefs.getString("host", ""))) {
                                        prefs.edit().putString("host", ip).putString("hostport", ip + ":" + prefs.getInt("port", 8443)).apply();
                                        update("Связь с ПК", "новый адрес " + ip);
                                    }
                                }
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
                    public void onClose(String reason) {
                        lockedOut = reason != null && reason.contains("4029");
                        if (reason != null && reason.contains("4003")) {   // secret revoked on the PC: stop hammering, ask to pair again
                            prefs.edit().remove("secret").apply();
                            update("Доступ отозван", "привяжите телефон заново в приложении");
                        }
                    }
                }, prefs.getString("lan", ""), onWifi(this));
                c.connect();
                c.sendText("{\"t\":\"auth\",\"token\":\"" + prefs.getString("secret", "") + "\"}");
                c.sendText("{\"t\":\"hello_phone\",\"model\":\"" + Build.MODEL.replace('"', ' ') + "\",\"fs\":" + filesAllowed() + ",\"bg\":true}");
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
            if (lockedOut) delay = 60000;   // address locked out on the PC: retrying faster only prolongs it
            waitOrNudge(delay); delay = Math.min(delay * 2, 15000);
        }
    }

    /** Wi-Fi right now? Only then is the PC's LAN address worth a try. */
    /** "key": ["a", "b"] -> "a,b" (flat JSON only). */
    static String jsonStringArray(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int a = json.indexOf('[', k), b = json.indexOf(']', a);
        if (a < 0 || b < 0) return null;
        return json.substring(a + 1, b).replace("\"", "").replace(" ", "");
    }

    /** Every 5 s: is there a better road to the PC than the one in use (cable plugged in, back on
     *  home Wi-Fi)? If so, drop the link; the reconnect takes the better one, and the page follows. */
    private void watchPaths() {
        Paths.pcAddrs = prefs.getString("addrs", "");
        while (running) {
            sleep(5000);
            WsClient c = ws;
            if (c == null || !c.isOpen()) continue;
            try {
                java.util.List<Paths.Candidate> cands = Paths.candidates(Paths.pcAddrs, prefs.getString("lan", ""), onWifi(this), prefs.getString("host", ""));
                if (cands.isEmpty()) continue;
                String peer = c.peer();
                int curRank = Paths.PUBLIC;
                for (Paths.Candidate cd : cands) if (cd.host.equals(peer)) curRank = cd.rank;
                Paths.Candidate best = cands.get(0);
                if (best.rank < curRank && !best.host.equals(peer)) {
                    update("Связь с ПК", "найден путь короче: " + best.host);
                    c.close();                              // keepConnected() reconnects at once, best path first
                    MainActivity.reconnectWeb();
                }
            } catch (Exception ignored) {}
        }
    }

    public static boolean onWifi(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) { return false; }
    }

    private void onMessage(String s) {
        if (s.startsWith("{\"t\": \"status\"") || s.startsWith("{\"t\":\"status\"")) {   // the PC's LAN address may change (Wi-Fi <-> cable)
            String lan = Pairing.jsonString(s, "lan");
            if (lan != null && !lan.isEmpty() && !lan.equals(prefs.getString("lan", ""))) prefs.edit().putString("lan", lan).apply();
            String addrs = jsonStringArray(s, "addrs");   // every address of the PC: USB tethering / hotspot / LAN are one hop away
            if (addrs != null && !addrs.equals(prefs.getString("addrs", ""))) prefs.edit().putString("addrs", addrs).apply();
            Paths.pcAddrs = prefs.getString("addrs", "");
            return;
        }
        if (s.contains("\"t\":\"ring\"") || s.contains("\"t\": \"ring\"")) {
            Intent i = new Intent(this, RingActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // Android 10+ blocks activity starts from the background: a full-screen notification is
            // the sanctioned way to bring the ring screen up over the lock screen.
            PendingIntent fsi = PendingIntent.getActivity(this, 8, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL_RING)
                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Телефон здесь!").setContentText("Сигнал с вашего ПК — нажмите, чтобы выключить")
                    .setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX)
                    .setFullScreenIntent(fsi, true).setContentIntent(fsi).setAutoCancel(true).build();
            getSystemService(NotificationManager.class).notify(9, n);
            try { startActivity(i); } catch (Exception ignored) {}   // works when we are already in front
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
        } else if (s.startsWith("{\"t\":\"pc_notify\"")) {
            try { pcNotify(new JSONObject(s)); } catch (Exception ignored) {}
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

    /** POST to the ntfy wake channel: the PC's wake helper (or router) turns the PC on. */
    public static void wake(Context ctx) {
        final String url = ctx.getSharedPreferences("pcremote", MODE_PRIVATE).getString("wake", "");
        if (url.isEmpty()) { toast(ctx, "Канал включения не настроен на ПК (ntfy_wake_url)"); return; }
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(8000); c.setReadTimeout(8000);
                c.setRequestProperty("Title", "wake");
                c.getOutputStream().write("wake".getBytes());
                int code = c.getResponseCode();
                toast(ctx, code < 300 ? "Сигнал включения отправлен" : "ntfy ответил " + code);
            } catch (Exception e) { toast(ctx, "Не удалось отправить: " + e.getMessage()); }
        }, "wake").start();
    }

    /** sleep / lock / reboot / shutdown from the widget. */
    private void sendCmd(String cmd) {
        WsClient c = ws;
        if (c == null || !c.isOpen()) { toast(this, "ПК не на связи"); return; }
        try { c.sendText("{\"t\":\"cmd\",\"cmd\":\"" + cmd + "\"}"); toast(this, "sleep".equals(cmd) ? "ПК засыпает" : "lock".equals(cmd) ? "ПК заблокирован" : "Команда отправлена"); }
        catch (Exception e) { toast(this, "Не удалось: " + e.getMessage()); }
    }

    private static void toast(Context ctx, String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show());
    }

    private int pcNotifyId = 100;

    /** A Windows toast, mirrored on the phone. */
    private void pcNotify(JSONObject ev) {
        if (MainActivity.visible) return;   // the page shows it as a pill while the app is open
        String app = ev.optString("app", "ПК"), title = ev.optString("title", ""), text = ev.optString("text", "");
        Intent open = new Intent(this, MainActivity.class);
        Notification n = new Notification.Builder(this, CHANNEL_PC)
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle(app + (title.isEmpty() ? "" : ": " + title))
                .setContentText(text.isEmpty() ? title : text).setStyle(new Notification.BigTextStyle().bigText(text.isEmpty() ? title : text))
                .setContentIntent(PendingIntent.getActivity(this, 6, open, PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).setGroup("pc").build();
        pcNotifyId = 100 + (pcNotifyId - 99) % 20;
        getSystemService(NotificationManager.class).notify(pcNotifyId, n);
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
