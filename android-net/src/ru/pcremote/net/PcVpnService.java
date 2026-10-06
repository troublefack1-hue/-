package ru.pcremote.net;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import hev.htproxy.TProxyService;

/**
 * The VPN interface: every packet of the phone goes to hev-socks5-tunnel (native, bundled),
 * which turns it into SOCKS5 connections to our local Socks5Server, which carries them to the
 * PC over NetMux. The PC opens the real sockets — through its own VPN, if it runs one.
 *
 * When the PC cannot be reached the interface is torn down (the phone is back on its direct
 * internet, nothing is silently blackholed) and we keep trying in the background.
 */
public class PcVpnService extends VpnService {
    public static final String ACTION_START = "ru.pcremote.net.START", ACTION_STOP = "ru.pcremote.net.STOP";
    static final String CHANNEL = "vpn";
    static final int NOTIF_ID = 1;
    static final String TUN_ADDR = "10.8.0.2", TUN_DNS = "10.8.0.1";

    public static volatile PcVpnService instance;
    public static volatile String state = "off";      // off | connecting | on | waiting
    public static volatile String detail = "";
    public static volatile long blockedAds = 0, dnsQueries = 0, dnsCached = 0;

    private ParcelFileDescriptor tun;
    private NetMux mux;
    private Socks5Server socks;
    private Thread worker, updater;
    private volatile boolean wanted;
    private SharedPreferences prefs;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("pcnet", MODE_PRIVATE);
        PairShare.sync(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Интернет через ПК", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) { wanted = false; prefs.edit().putBoolean("wanted", false).apply(); teardown("off", ""); stopSelf(); return START_NOT_STICKY; }
        startForeground(NOTIF_ID, notification("Интернет через ПК", "подключаюсь…"));
        wanted = true; prefs.edit().putBoolean("wanted", true).apply();
        if (worker == null || !worker.isAlive()) { worker = new Thread(this::loop, "pcnet"); worker.setDaemon(true); worker.start(); }
        if (updater == null || !updater.isAlive()) {   // our own updates while the tunnel runs, every 15 minutes
            updater = new Thread(() -> {
                try { Thread.sleep(90_000); } catch (InterruptedException e) { return; }
                // «Мой ПК» updates us on the PC's push; this is the fallback, rare on mobile data (a TLS connection each)
                while (true) {
                    NetInstaller.check(this, "timer");
                    boolean metered = true;
                    try { metered = ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).isActiveNetworkMetered(); } catch (Exception ignored) {}
                    try { Thread.sleep(metered ? 3 * 3600_000L : 15 * 60_000); } catch (InterruptedException e) { return; }
                }
            }, "netupdate");
            updater.setDaemon(true); updater.start();
        }
        return START_STICKY;
    }

    @Override public void onRevoke() { wanted = false; prefs.edit().putBoolean("wanted", false).apply(); teardown("off", "другое VPN-приложение заняло интерфейс"); stopSelf(); }

    @Override public void onDestroy() { wanted = false; teardown("off", ""); instance = null; super.onDestroy(); }

    // ------------------------------------------------------------------
    private void loop() {
        int fails = 0;
        while (wanted) {
            setState("connecting", fails == 0 ? "" : "попытка " + (fails + 1));
            try {
                bringUp();
                fails = 0;
                setState("on", (dnsOnly(this) ? "только DNS, " : "весь трафик, ") + "ПК " + prefs.getString("hostport", ""));
                long lastStats = 0;
                while (wanted && mux.isUp()) {
                    Thread.sleep(1000);
                    if (System.currentTimeMillis() - lastStats > 5000) { lastStats = System.currentTimeMillis(); mux.requestStats(); }
                }
                if (!wanted) break;
                teardownLink();
                setState("waiting", "связь с ПК пропала, интернет напрямую");
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                fails++;
                teardownLink();
                setState("waiting", "ПК недоступен: " + brief(e) + " · интернет напрямую");
            }
            // back off: 3 s, 6 s, … up to a minute; the phone keeps its direct internet meanwhile
            long wait = Math.min(60_000, 3000L << Math.min(fails, 4));
            try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
        }
        teardown("off", "");
    }

    private void bringUp() throws Exception {
        String host = prefs.getString("host", ""), pin = prefs.getString("pin", ""), secret = prefs.getString("secret", ""), lan = prefs.getString("lan", "");
        int port = prefs.getInt("port", 8443);
        NetMux m = new NetMux(host, port, pin, secret, lan, onWifi(this), new NetMux.Listener() {
            public void onDown(String reason) { }
            public void onText(String json) { onPcText(json); }
        });
        m.connect();
        mux = m;
        socks = new Socks5Server(m);
        socks.start();

        boolean dnsOnly = dnsOnly(this);
        Builder b = new Builder();
        b.setSession(dnsOnly ? "DNS через ПК" : "Интернет через ПК").setMtu(8500).addAddress(TUN_ADDR, 24).addDnsServer(TUN_DNS);
        // «только DNS»: the interface carries nothing but the resolver's address — ads are cut on the PC,
        // the data itself goes straight out, no double hop
        if (dnsOnly) b.addRoute(TUN_DNS, 32); else b.addRoute("0.0.0.0", 0);
        // our own link to the PC must not loop back into the tunnel; «Мой ПК» talks to the PC directly too
        for (String pkg : excludedApps(this)) {
            try { b.addDisallowedApplication(pkg); } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
        ParcelFileDescriptor fd = b.establish();
        if (fd == null) throw new IllegalStateException("нет разрешения на VPN");
        tun = fd;
        File conf = new File(getFilesDir(), "hev.yml");
        String yml = "tunnel:\n  mtu: 8500\n  ipv4: " + TUN_ADDR + "\n"
                + "socks5:\n  port: " + socks.port() + "\n  address: 127.0.0.1\n  udp: 'udp'\n"
                + "misc:\n  task-stack-size: 20480\n  log-level: warn\n";
        try (FileOutputStream o = new FileOutputStream(conf)) { o.write(yml.getBytes(StandardCharsets.UTF_8)); }
        if (!TProxyService.TProxyStartService(conf.getAbsolutePath(), fd.detachFd())) throw new IllegalStateException("не удалось запустить туннель");
    }

    private void teardownLink() {
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
        if (tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
        if (socks != null) { socks.stop(); socks = null; }
        if (mux != null) { mux.close(); mux = null; }
    }

    private void teardown(String st, String why) { teardownLink(); setState(st, why); }

    /** A pairing answer to the PC over the tunnel's link (NetPairApprove). */
    boolean answerPair(String json) { NetMux m = mux; return m != null && m.sendText(json); }

    private void onPcText(String json) {
        if (json.contains("\"pair_request\"") && json.length() < 600) {
            try {
                org.json.JSONObject ev = new org.json.JSONObject(json);
                NetPairApprove.ask(this, ev.optString("id"), ev.optString("model"), ev.optString("ip"));
            } catch (Exception ignored) {}
            return;
        }
        if (json.contains("\"pair_done\"") && json.length() < 300) {
            try { NetPairApprove.done(this, new org.json.JSONObject(json).optString("id")); } catch (Exception ignored) {}
            return;
        }
        if (json.contains("\"net_stats\"")) {
            try {
                blockedAds = Long.parseLong(ru.pcremote.Pairing.jsonNumber(json, "dns_blocked"));
                dnsQueries = Long.parseLong(ru.pcremote.Pairing.jsonNumber(json, "dns_queries"));
                dnsCached = Long.parseLong(ru.pcremote.Pairing.jsonNumber(json, "dns_cached"));
            } catch (Exception ignored) {}
            NetTile.refresh(this);
        }
    }

    private void setState(String st, String d) {
        state = st; detail = d;
        String text = "on".equals(st) ? "включён · " + d : "connecting".equals(st) ? "подключаюсь… " + d : "waiting".equals(st) ? d : "выключен";
        if (!"off".equals(st)) getSystemService(NotificationManager.class).notify(NOTIF_ID, notification("Интернет через ПК", text));
        NetTile.refresh(this);
        MainActivity.refresh();
    }

    private Notification notification(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PcVpnService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile).setContentTitle(title).setContentText(text)
                .setContentIntent(pi).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Выключить", stop).build()).build();
    }

    private static String brief(Exception e) { String m = e.getMessage(); return m == null ? e.getClass().getSimpleName() : m.replaceAll("^.*Exception: ", ""); }

    // ---- helpers for the activity / tile ----
    public static boolean isOn() { return "on".equals(state); }
    public static boolean isActive() { return instance != null && !"off".equals(state); }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, PcVpnService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }
    public static void stop(Context ctx) { ctx.startService(new Intent(ctx, PcVpnService.class).setAction(ACTION_STOP)); }

    public static boolean dnsOnly(Context ctx) { return ctx.getSharedPreferences("pcnet", Context.MODE_PRIVATE).getBoolean("dns_only", false); }

    /** Apply a changed setting to a running tunnel: rebuild the interface. */
    public static void restartIfActive(Context ctx) {
        if (!isActive()) return;
        stop(ctx);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> start(ctx), 800);
    }

    public static Set<String> excludedApps(Context ctx) {
        Set<String> s = new HashSet<>(ctx.getSharedPreferences("pcnet", Context.MODE_PRIVATE).getStringSet("direct", new HashSet<>()));
        s.add(ctx.getPackageName()); s.add("ru.pcremote");
        return s;
    }

    static boolean onWifi(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) { return false; }
    }
}
