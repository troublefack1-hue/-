package ru.pcremote;

import android.app.Activity;
import android.content.SharedPreferences;
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
    private SharedPreferences prefs;
    private WebView web;
    private Tunnel tunnel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pcremote", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (prefs.contains("secret")) startRemote(); else showSetup(null);
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
                            .putString("secret", r.secret).putString("pin", r.fingerprint).apply();
                    runOnUiThread(this::startRemote);
                } catch (Exception e) {
                    runOnUiThread(() -> { btn.setEnabled(true); err.setTextColor(0xFFEF5350);
                        err.setText(e.getMessage() == null ? e.toString() : e.getMessage()); });
                }
            }).start();
        });

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
        setContentView(web);
        web.loadUrl("http://127.0.0.1:" + tunnel.localPort() + "/#" + prefs.getString("secret", ""));
    }

    private void showRetry(String msg) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG); root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView t = text(msg, 16, TEXT); t.setGravity(Gravity.CENTER); root.addView(t);
        TextView h = text("Повторяю через 5 секунд…", 14, MUTED); h.setGravity(Gravity.CENTER); h.setPadding(0, dp(10), 0, dp(20)); root.addView(h);
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

    /** Called from the page: window.PcRemoteApp.repair() */
    private class Bridge {
        @JavascriptInterface public void repair() {
            prefs.edit().remove("secret").apply();
            runOnUiThread(() -> showSetup(null));
        }
    }

    @Override public void onBackPressed() {
        if (web != null) moveTaskToBack(true); else super.onBackPressed();
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
