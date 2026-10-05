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
    private static final int REQ_CAST = 7;
    public static volatile boolean visible = false;
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
        if (prefs.contains("secret")) { RemoteService.ensureRunning(this); startRemote(); } else showSetup(null);
        checkUpdate();
    }

    // ----------------------------------------------------------- update ---
    /** Once every 6 h: newer release on GitHub -> download -> system "Install" dialog. */
    private void checkUpdate() {
        long last = prefs.getLong("upd_check", 0);
        if (System.currentTimeMillis() - last < 6 * 3600_000L) return;
        prefs.edit().putLong("upd_check", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                Updater.Info info = Updater.check(cur);
                if (info == null) return;
                java.io.File apk = Updater.download(info.url, getCacheDir(), info.sha256);
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

    @Override protected void onResume() { super.onResume(); visible = true; }
    @Override protected void onPause() { super.onPause(); visible = false; }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
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
        final EditText code = input("", "6 цифр", InputType.TYPE_CLASS_NUMBER);
        card.addView(code);

        final TextView err = text(error == null ? "" : error, 14, 0xFFEF5350); err.setPadding(0, dp(6), 0, dp(6));
        card.addView(err);

        final Button btn = new Button(this);
        btn.setText("Привязать"); btn.setTextColor(Color.WHITE); btn.setBackgroundColor(ACCENT); btn.setAllCaps(false);
        btn.setTypeface(null, Typeface.BOLD);
        card.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        btn.setOnClickListener(v -> {
            String hp = host.getText().toString().trim(); String c = code.getText().toString().trim();
            String[] parts = hp.split(":");
            if (parts.length != 2 || c.length() != 6) { err.setText("Нужен адрес вида IP:порт и код из 6 цифр"); return; }
            final String h = parts[0]; final int port;
            try { port = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { err.setText("Порт должен быть числом"); return; }
            btn.setEnabled(false); err.setText("Подключаюсь…"); err.setTextColor(MUTED);
            new Thread(() -> {
                try {
                    Pairing.Result r = Pairing.pair(h, port, c);
                    prefs.edit().putString("hostport", hp).putString("host", h).putInt("port", port)
                            .putString("secret", r.secret).putString("pin", r.fingerprint).putString("ntfy", r.ntfy).putString("wake", r.wake).apply();
                    runOnUiThread(this::startRemote);
                } catch (Exception e) {
                    runOnUiThread(() -> { btn.setEnabled(true); err.setTextColor(0xFFEF5350);
                        err.setText(e.getMessage() == null ? e.toString() : e.getMessage()); });
                }
            }).start();
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
            tunnel = new Tunnel(prefs.getString("host", ""), prefs.getInt("port", 8443), prefs.getString("pin", ""));
            tunnel.start();
        } catch (Exception e) { showSetup("Не удалось запустить туннель: " + e); return; }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setBackgroundColor(BG);
        web.addJavascriptInterface(new Bridge(), "PcRemoteApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return false; }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e) {
                if (r.isForMainFrame()) {
                    String why = "pin-mismatch".equals(tunnel.lastError)
                            ? "Сертификат ПК изменился. Привяжите телефон заново."
                            : "ПК недоступен: " + e.getDescription() + ". Проверьте, что ПК включён и порт проброшен.";
                    v.postDelayed(() -> { if (web != null) web.reload(); }, 5000);
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
