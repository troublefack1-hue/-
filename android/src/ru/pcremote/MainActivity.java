package ru.pcremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.widget.Toast;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Phone app. First run: "IP:port" + 6-digit code from the PC window -> pairs,
 * stores secret + certificate fingerprint. After that: opens the PC's page in
 * a WebView through the local pinned tunnel. No settings, no certificates to
 * install, nothing to type again.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0F1117, PANEL = 0xFF181B24, TEXT = 0xFFEEF0F5, MUTED = 0xFF8E94A6, ACCENT = 0xFF4F8CFF;
    private static final int REQ_CAST = 7, REQ_FILE = 9;
    private android.webkit.ValueCallback<android.net.Uri[]> fileCallback;
    public static volatile boolean visible = false;
    private static volatile MainActivity live;

    /** The service found a shorter road to the PC: make the page reconnect so it uses it too. */
    static void reconnectWeb() {
        MainActivity a = live;
        if (a != null) a.runOnUiThread(() -> { if (a.web != null) a.web.evaluateJavascript("window.pcrReconnect && window.pcrReconnect()", null); });
    }
    private SharedPreferences prefs;
    private WebView web;
    private Tunnel tunnel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pcremote", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        // Android 14: full-screen notifications ("Найти телефон" over the lock screen) need an explicit grant
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                if (!nm.canUseFullScreenIntent() && !prefs.getBoolean("fsi_asked", false)) {
                    prefs.edit().putBoolean("fsi_asked", true).apply();
                    Toast.makeText(this, "Разрешите «Полноэкранные уведомления», чтобы «Найти телефон» работал на заблокированном экране", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + getPackageName())));
                }
            } catch (Exception ignored) {}
        }
        syncUpdaterPc();
        if (!handlePairLink(getIntent())) {
            if (prefs.contains("secret")) { RemoteService.ensureRunning(this); startRemote(); } else showSetup(null);
        }
        checkUpdate();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handlePairLink(i);
    }

    /** pcremote://pair?host=IP:port&code=…&fp=…&lan=… from a scanned QR: pair without typing anything. */
    private boolean handlePairLink(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return false;
        Uri u = i.getData();
        if (!"pcremote".equals(u.getScheme()) || !"pair".equals(u.getHost())) return false;
        i.setData(null);   // consumed: a rotation must not pair again
        String hp = u.getQueryParameter("host"), code = u.getQueryParameter("code"), fp = u.getQueryParameter("fp"), lan = u.getQueryParameter("lan");
        if (hp == null || code == null) { showSetup("В QR нет адреса или кода"); return true; }
        String[] parts = hp.split(":");
        int port = 8443;
        try { if (parts.length == 2) port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { showSetup("В QR неверный порт"); return true; }
        showSetup(null);
        pairWith(parts[0], port, code.toUpperCase().replaceAll("[^0-9A-Z]", ""), fp == null ? "" : fp.toLowerCase().replace(":", ""), lan == null ? "" : lan, null, null);
        return true;
    }

    /** Pair in the background; on success the remote screen opens, on failure the setup card shows the error. */
    private void pairWith(final String h, final int port, final String c, final String fpExpected, final String lan, final Button btn, final TextView err) {
        final String hostport = h + ":" + port;
        if (btn != null) btn.setEnabled(false);
        if (err != null) { err.setText("Подключаюсь…"); err.setTextColor(MUTED); }
        else Toast.makeText(this, "Привязываю к " + hostport + "…", Toast.LENGTH_SHORT).show();
        final boolean wifi = RemoteService.onWifi(this);
        new Thread(() -> {
            try {
                Pairing.Result r = Pairing.pair(h, port, c, fpExpected, lan, wifi);
                prefs.edit().putString("hostport", hostport).putString("host", h).putInt("port", port)
                        .putString("secret", r.secret).putString("pin", r.fingerprint).putString("ntfy", r.ntfy).putString("wake", r.wake)
                        .putString("lan", r.lan.isEmpty() ? lan : r.lan).apply();
                runOnUiThread(() -> { RemoteService.ensureRunning(this); startRemote(); });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    if (err != null) { btn.setEnabled(true); err.setTextColor(0xFFEF5350); err.setText(msg); }
                    else showSetup(msg);
                });
            }
        }).start();
    }

    /** Tell the shared Updater which PC we are paired with: updates come from it, with its own signing key. */
    private void syncUpdaterPc() {
        Updater.pc = prefs.contains("secret") ? new Updater.Pc(prefs.getString("host", ""), prefs.getInt("port", 8443), prefs.getString("pin", ""), prefs.getString("secret", ""), prefs.getString("lan", "")) : null;
    }

    /** From the page: install or update another of our apps straight from the PC (same key, no GitHub). */
    void installFromPc(String asset, boolean quiet) {
        syncUpdaterPc();
        if (Updater.pc == null) { Toast.makeText(this, "Сначала привяжите ПК", Toast.LENGTH_SHORT).show(); return; }
        if (!quiet) Toast.makeText(this, "Запрашиваю " + asset + " у ПК…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Updater.Info info = Updater.checkPc("0", asset);
                if (info == null) throw new java.io.IOException("на ПК ещё нет этого приложения: откройте окно PC Remote, оно скачает");
                java.io.File apk = Updater.downloadPc(asset, getCacheDir(), info.sha256);
                runOnUiThread(() -> {
                    Toast.makeText(this, asset + " " + info.version + " с ПК — установите", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://" + ApkProvider.AUTHORITY + "/update.apk"), "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                });
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Не удалось: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        }).start();
    }

    // ----------------------------------------------------------- update ---
    /** At every launch: newer release on GitHub -> download -> system "Install" dialog. */
    private void checkUpdate() {
        new Thread(() -> {
            try {
                String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                syncUpdaterPc();
                Updater.Info found = null; boolean fromPc = false;
                if (Updater.pc != null) { try { found = Updater.checkPc(cur, Updater.ASSET); fromPc = found != null; } catch (Exception ignored) {} }
                if (found == null) found = Updater.check(cur);
                final Updater.Info info = found;
                if (info == null) return;
                java.io.File apk = fromPc ? Updater.downloadPc(Updater.ASSET, getCacheDir(), info.sha256) : Updater.download(info.url, getCacheDir(), info.sha256);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Обновление " + info.version + " — установите", Toast.LENGTH_LONG).show();
                    Intent i = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.parse("content://" + ApkProvider.AUTHORITY + "/update.apk"),
                                    "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ------------------------------------------------------------- cast ---
    /** Android asks the user once per session before the screen can be captured. */
    private void requestCast() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAST);
    }

    @Override protected void onResume() {
        super.onResume(); visible = true; live = this;
        Paths.pcAddrs = prefs.getString("addrs", "");
        if (web != null) web.evaluateJavascript("window.pcrVisible && window.pcrVisible(true)", null);
    }
    @Override protected void onPause() {
        super.onPause(); visible = false;
        // the page cannot see this on its own: it drops to the idle profile (no video) while we are in the background
        if (web != null) web.evaluateJavascript("window.pcrVisible && window.pcrVisible(false)", null);
    }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req == REQ_FILE) {
            android.net.Uri[] uris = null;
            if (code == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    uris = new android.net.Uri[data.getClipData().getItemCount()];
                    for (int k = 0; k < uris.length; k++) uris[k] = data.getClipData().getItemAt(k).getUri();
                } else if (data.getData() != null) uris = new android.net.Uri[]{data.getData()};
            }
            if (fileCallback != null) { fileCallback.onReceiveValue(uris); fileCallback = null; }
            return;
        }
        if (req == REQ_CAST && code == RESULT_OK && data != null) {
            Intent i = new Intent(this, RemoteService.class).setAction(RemoteService.ACTION_CAST)
                    .putExtra("code", code).putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            moveTaskToBack(true);  // the user goes on with the phone; the PC shows it
        }
    }

    // ------------------------------------------------------------ setup ---
    private void showSetup(String error) {
        stopRemote();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setGravity(Gravity.CENTER);
        int p = dp(24);
        root.setPadding(p, p, p, p);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(PANEL);
        card.setPadding(dp(22), dp(28), dp(22), dp(22));

        TextView title = text("Мой ПК", 26, TEXT); title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER);
        card.addView(title);
        TextView sub = text("Введите данные с экрана программы на ПК", 14, MUTED); sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(18));
        card.addView(sub);

        card.addView(text("Адрес ПК", 13, MUTED));
        final EditText host = input(prefs.getString("hostport", ""), "IP:порт, например 93.100.1.2:8443", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.addView(host);
        card.addView(text("Код привязки", 13, MUTED));
        final EditText code = input("", "код из окна PC Remote (или наведите камеру на QR)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        card.addView(code);

        final TextView err = text(error == null ? "" : error, 14, 0xFFEF5350); err.setPadding(0, dp(6), 0, dp(6));
        card.addView(err);

        final Button btn = new Button(this);
        btn.setText("Привязать"); btn.setTextColor(Color.WHITE); btn.setBackgroundColor(ACCENT); btn.setAllCaps(false);
        btn.setTypeface(null, Typeface.BOLD);
        card.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        btn.setOnClickListener(v -> {
            // be forgiving: "https://1.2.3.4:8443/", "1.2.3.4" (default port), code typed as "113 879"
            String hp = host.getText().toString().trim().replaceAll("^[a-zA-Z]+://", "").replaceAll("/.*$", "");
            String c = code.getText().toString().toUpperCase().replaceAll("[^0-9A-Z]", "");
            String[] parts = hp.split(":");
            if (hp.isEmpty() || parts.length > 2 || !(c.length() == 6 || c.length() == 8)) { err.setText("Нужен адрес ПК (IP или IP:порт) и код из окна PC Remote"); return; }
            final String h = parts[0]; final int port;
            try { port = parts.length == 2 ? Integer.parseInt(parts[1]) : 8443; } catch (NumberFormatException e) { err.setText("Порт должен быть числом"); return; }
            pairWith(h, port, c, "", "", btn, err);
        });

        // entrance: card rises and fades in
        card.setAlpha(0f); card.setTranslationY(dp(30));
        card.animate().alpha(1f).translationY(0).setDuration(450).setStartDelay(80)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(sv);
    }

    // ----------------------------------------------------------- remote ---
    private void startRemote() {
        try {
            tunnel = new Tunnel(prefs.getString("host", ""), prefs.getInt("port", 8443), prefs.getString("pin", ""),
                    prefs.getString("lan", ""), RemoteService.onWifi(this));
            tunnel.start();
        } catch (Exception e) { showSetup("Не удалось запустить туннель: " + e); return; }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);   // the page needs only the tunnel
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setBackgroundColor(BG);
        web.addJavascriptInterface(new Bridge(), "PcRemoteApp");
        web.setWebChromeClient(new WebChromeClient() {
            // <input type="file"> does nothing in a WebView unless the app opens the picker itself
            @Override public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<android.net.Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = params.createIntent();
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) {
                    String why = "pin-mismatch".equals(tunnel.lastError)
                            ? "Сертификат ПК изменился. Привяжите телефон заново."
                            : "ПК недоступен: " + e.getDescription() + ". Проверьте, что ПК включён и порт проброшен.";
                    v.postDelayed(() -> {
                        if (web == null) return;
                        // the background service may have learned a new PC address meanwhile
                        if (tunnel != null && !tunnel.host().equals(prefs.getString("host", ""))) { stopRemote(); startRemote(); }
                        else web.reload();
                    }, 5000);
                    runOnUiThread(() -> showRetry(why));
                }
            }
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web.setAlpha(0f);
        setContentView(web);
        web.animate().alpha(1f).setDuration(350).start();  // the page shows its own splash
        web.loadUrl("http://127.0.0.1:" + tunnel.localPort() + "/#" + prefs.getString("secret", ""));
    }

    private void showRetry(String msg) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView t = text(msg, 16, TEXT); t.setGravity(Gravity.CENTER); root.addView(t);
        TextView h = text("Повторяю через 5 секунд…", 14, MUTED); h.setGravity(Gravity.CENTER); h.setPadding(0, dp(10), 0, dp(20)); root.addView(h);
        if (!prefs.getString("wake", "").isEmpty()) {
            Button wake = new Button(this); wake.setText("Включить ПК"); wake.setAllCaps(false);
            wake.setBackgroundColor(ACCENT); wake.setTextColor(0xFFFFFFFF); wake.setTypeface(null, Typeface.BOLD);
            wake.setOnClickListener(v -> { RemoteService.wake(this); wake.setText("Сигнал отправлен, жду ПК…"); wake.setEnabled(false); });
            root.addView(wake, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
            TextView sp = text("", 6, MUTED); root.addView(sp);
        }
        Button again = new Button(this); again.setText("Привязать заново"); again.setAllCaps(false);
        again.setOnClickListener(v -> { prefs.edit().remove("secret").apply(); showSetup(null); });
        root.addView(again);
        setContentView(root);
        web.postDelayed(() -> { if (web != null && web.getParent() == null) setContentView(web); }, 4900);
    }

    private void stopRemote() {
        if (tunnel != null) { tunnel.stop(); tunnel = null; }
        if (web != null) { web.destroy(); web = null; }
    }

    /** Called from the page: window.PcRemoteApp.* */
    private class Bridge {
        /** The diagnostics page asks: connect to the PC by each listed port, measure TLS handshake,
         *  time to first byte and download speed of 256 KB from /api/ping. Result -> window.pcrPorts(json). */
        @JavascriptInterface public void probePorts(String portsJson) {
            new Thread(() -> {
                StringBuilder out = new StringBuilder("[");
                int current = prefs.getInt("port", 8443);
                for (String tok : portsJson.replaceAll("[\\[\\]\\s]", "").split(",")) {
                    int port; try { port = Integer.parseInt(tok); } catch (NumberFormatException e) { continue; }
                    if (out.length() > 1) out.append(',');
                    out.append(probeOne(port, current));
                }
                final String json = out.append(']').toString();
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.pcrPorts && window.pcrPorts(" + json.replace("\\", "\\\\").replace("'", "\\'") + ")", null); });
            }).start();
        }

        private String probeOne(int port, int current) {
            String host = prefs.getString("host", ""), pin = prefs.getString("pin", "");
            long t0 = System.nanoTime();
            try (javax.net.ssl.SSLSocket s = Pinned.connect(host, port, pin, null, 6000)) {
                long tConn = System.nanoTime();
                s.setSoTimeout(10000);
                java.io.OutputStream o = s.getOutputStream();
                o.write(("GET /api/ping?n=262144 HTTP/1.1\r\nHost: " + host + "\r\nAuthorization: Bearer " + prefs.getString("secret", "") + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                o.flush();
                java.io.InputStream in = s.getInputStream();
                byte[] buf = new byte[65536]; long total = 0, tFirst = 0; int r;
                while ((r = in.read(buf)) > 0) { if (tFirst == 0) tFirst = System.nanoTime(); total += r; }
                long tEnd = System.nanoTime();
                double secs = Math.max(1e-3, (tEnd - tFirst) / 1e9);
                return "{\"port\":" + port + ",\"ok\":true,\"current\":" + (port == current) + ",\"connect_ms\":" + (tConn - t0) / 1_000_000
                        + ",\"ttfb_ms\":" + (tFirst - tConn) / 1_000_000 + ",\"kbs\":" + Math.round(total / 1024.0 / secs) + "}";
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage()).replace("\"", "'").replaceAll("[\\r\\n]", " ");
                return "{\"port\":" + port + ",\"ok\":false,\"current\":" + (port == current) + ",\"error\":\"" + m.substring(0, Math.min(60, m.length())) + "\"}";
            }
        }

        /** Switch the tunnel to another of the PC's ports (chosen from the probe). */
        @JavascriptInterface public void usePort(int port) {
            if (port <= 0 || port > 65535) return;
            prefs.edit().putInt("port", port).putString("hostport", prefs.getString("host", "") + ":" + port).apply();
            runOnUiThread(() -> { stopRemote(); startRemote(); });
        }

        /** Which road the tunnel took last ("direct 192.168.42.129", "lan …", "public …"), for diagnostics. */
        @JavascriptInterface public String path() { return Paths.lastPath; }

        /** Settings → «Проверить обновления приложения»: check now, say what happened, install if newer. */
        @JavascriptInterface public void checkUpdate() {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Проверяю обновления…", Toast.LENGTH_SHORT).show());
            new Thread(() -> {
                try {
                    String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    syncUpdaterPc();
                    Updater.Info found = null; boolean fromPc = false;
                    if (Updater.pc != null) { try { found = Updater.checkPc(cur, Updater.ASSET); fromPc = found != null; } catch (Exception ignored) {} }
                    if (found == null) found = Updater.check(cur);
                    final Updater.Info info = found;
                    if (info == null) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Это последняя версия (" + cur + ")", Toast.LENGTH_SHORT).show()); return; }
                    final String src = fromPc ? " с ПК" : " с GitHub";
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Скачиваю " + info.version + src + "…", Toast.LENGTH_SHORT).show());
                    if (fromPc) Updater.downloadPc(Updater.ASSET, getCacheDir(), info.sha256); else Updater.download(info.url, getCacheDir(), info.sha256);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Обновление " + info.version + " — установите", Toast.LENGTH_LONG).show();
                        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://" + ApkProvider.AUTHORITY + "/update.apk"), "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                    });
                } catch (Exception e) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Не удалось проверить: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
            }).start();
        }

        @JavascriptInterface public void installFromPc(String asset) { runOnUiThread(() -> MainActivity.this.installFromPc(asset, false)); }

        @JavascriptInterface public void repair() {
            prefs.edit().remove("secret").apply();
            runOnUiThread(() -> showSetup(null));
        }
        @JavascriptInterface public void startCast() { runOnUiThread(MainActivity.this::requestCast); }
        @JavascriptInterface public void stopCast() {
            startService(new Intent(MainActivity.this, RemoteService.class).setAction(RemoteService.ACTION_CAST_STOP));
        }
        @JavascriptInterface public boolean isCasting() { return RemoteService.casting; }
        /** "Включить ПК" straight from the phone (the PC's relay is down while it sleeps). */
        /** Stream a file from the PC into the phone's Downloads/PC Remote, with a progress notification. */
        @JavascriptInterface public void download(String url, String name, long size) { Downloader.start(MainActivity.this, url, name, size); }
        /** A screenshot or a photo already shown on the page: write the bytes into Pictures/PC Remote. */
        @JavascriptInterface public void saveImage(String name, String b64, String mime) { Downloader.saveBytes(MainActivity.this, name, android.util.Base64.decode(b64, android.util.Base64.DEFAULT), mime); }
        /** "Весь экран": hide status and navigation bars (swipe from an edge brings them back). */
        @JavascriptInterface public void fullscreen(boolean on) {
            runOnUiThread(() -> {
                android.view.View dv = getWindow().getDecorView();
                if (Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsetsController c = getWindow().getInsetsController();
                    if (c == null) return;
                    if (on) { c.hide(android.view.WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
                    else c.show(android.view.WindowInsets.Type.systemBars());
                } else {
                    dv.setSystemUiVisibility(on ? (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE) : 0);
                }
            });
        }
        @JavascriptInterface public boolean wakePc() { if (prefs.getString("wake", "").isEmpty()) return false; RemoteService.wake(MainActivity.this); return true; }
        /** Phone files for the PC (Claude): "All files access" on Android 11+, storage permission before. */
        @JavascriptInterface public boolean filesGranted() {
            if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
            return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @JavascriptInterface public boolean filesEnabled() { return prefs.getBoolean("pfs", true) && filesGranted(); }
        @JavascriptInterface public void setFiles(boolean on) {
            prefs.edit().putBoolean("pfs", on).apply();
            if (on && !filesGranted()) runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + getPackageName()));
                    try { startActivity(i); } catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                } else {
                    requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"}, 2);
                }
            });
            // the service re-announces the flag on its next connect; poke it now
            startService(new Intent(MainActivity.this, RemoteService.class).setAction(RemoteService.ACTION_START));
        }
        @JavascriptInterface public boolean isPhoneMuted() { return RemoteService.phoneMuted; }
        @JavascriptInterface public void setPhoneMute(boolean on) {
            startService(new Intent(MainActivity.this, RemoteService.class).setAction(RemoteService.ACTION_MUTE).putExtra("on", on));
        }
    }

    @Override public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        // let the page close its own panel first; only from the main screen does Back minimize
        web.evaluateJavascript("(window.pcrBack && window.pcrBack()) ? '1' : '0'", v -> { if (!"\"1\"".equals(v)) moveTaskToBack(true); });
    }

    @Override protected void onDestroy() { stopRemote(); super.onDestroy(); }

    // --------------------------------------------------------------- ui ---
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }

    private EditText input(String value, String hint, int type) {
        EditText e = new EditText(this);
        e.setText(value); e.setHint(hint); e.setInputType(type);
        e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setBackgroundColor(BG);
        e.setPadding(dp(12), dp(12), dp(12), dp(12)); e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(14)); e.setLayoutParams(lp);
        return e;
    }
}
