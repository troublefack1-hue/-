package ru.pcremote.net;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.pcremote.Pairing;
import ru.pcremote.Updater;

/**
 * «Интернет через ПК»: pair once with the code from the PC Remote window (the same code as
 * «Мой ПК» uses), then one big button — like any VPN app — and a quick-settings tile.
 */
public class MainActivity extends Activity {
    public static final String ACTION_TOGGLE = "ru.pcremote.net.TOGGLE";
    private static final int BG = 0xFF0F1117, PANEL = 0xFF181B24, TEXT = 0xFFEEF0F5, MUTED = 0xFF8E94A6, ACCENT = 0xFF2E9E6B, BAD = 0xFFEF5350;
    private static final int REQ_VPN = 3;
    private static volatile MainActivity live;
    private SharedPreferences prefs;
    private TextView status, stats;
    private LinearLayout modeRow, updRow;
    private Button big;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pcnet", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        if (prefs.contains("secret")) showMain(); else showSetup(null);
        if (ACTION_TOGGLE.equals(getIntent().getAction()) && prefs.contains("secret")) toggle();
        checkUpdate();
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (ACTION_TOGGLE.equals(i.getAction()) && prefs.contains("secret")) toggle(); }
    @Override protected void onResume() { super.onResume(); live = this; render(); }
    @Override protected void onPause() { super.onPause(); live = null; }

    static void refresh() { MainActivity a = live; if (a != null) a.ui.post(a::render); }

    // ------------------------------------------------------------ setup ---
    private void showSetup(String error) {
        LinearLayout root = column(); root.setGravity(Gravity.CENTER); int p = dp(24); root.setPadding(p, p, p, p);
        LinearLayout card = column(); card.setBackgroundColor(PANEL); card.setPadding(dp(22), dp(28), dp(22), dp(22));
        TextView title = text("Интернет через ПК", 26, TEXT); title.setTypeface(null, Typeface.BOLD); title.setGravity(Gravity.CENTER); card.addView(title);
        TextView sub = text("Телефон выходит в интернет через домашний ПК — и через его VPN, если он там включён. Реклама режется на ПК.\n\nВведите адрес и код из окна PC Remote (те же, что для «Мой ПК»).", 14, MUTED);
        sub.setPadding(0, dp(6), 0, dp(18)); card.addView(sub);
        card.addView(text("Адрес ПК", 13, MUTED));
        final EditText host = input(prefs.getString("hostport", ""), "IP:порт, например 93.100.1.2:8443", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.addView(host);
        card.addView(text("Код подключения", 13, MUTED));
        final EditText code = input("", "код из окна PC Remote", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        card.addView(code);
        final TextView err = text(error == null ? "" : error, 14, BAD); err.setPadding(0, dp(6), 0, dp(6)); card.addView(err);
        final Button btn = button("Привязать"); card.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        btn.setOnClickListener(v -> {
            String hp = host.getText().toString().trim().replaceAll("^[a-zA-Z]+://", "").replaceAll("/.*$", "");
            String c = code.getText().toString().toUpperCase().replaceAll("[^0-9A-Z]", "");
            String[] parts = hp.split(":");
            if (hp.isEmpty() || parts.length > 2 || !(c.length() == 6 || c.length() == 8)) { err.setText("Нужен адрес ПК и код из окна PC Remote"); return; }
            final String h = parts[0]; final int port;
            try { port = parts.length == 2 ? Integer.parseInt(parts[1]) : 8443; } catch (NumberFormatException e) { err.setText("Порт должен быть числом"); return; }
            btn.setEnabled(false); err.setTextColor(MUTED); err.setText("Подключаюсь…");
            new Thread(() -> {
                try {
                    Pairing.Result r = Pairing.pair(h, port, c, "", "", PcVpnService.onWifi(this));
                    prefs.edit().putString("hostport", h + ":" + port).putString("host", h).putInt("port", port)
                            .putString("secret", r.secret).putString("pin", r.fingerprint).putString("lan", r.lan).apply();
                    runOnUiThread(this::showMain);
                } catch (Exception e) {
                    runOnUiThread(() -> { btn.setEnabled(true); err.setTextColor(BAD); err.setText(e.getMessage() == null ? e.toString() : e.getMessage()); });
                }
            }).start();
        });
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(sv);
    }

    // ------------------------------------------------------------- main ---
    private void showMain() {
        LinearLayout root = column(); root.setBackgroundColor(BG); int p = dp(24); root.setPadding(p, dp(40), p, p); root.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text("Интернет через ПК", 24, TEXT); title.setTypeface(null, Typeface.BOLD); root.addView(title);
        TextView where = text(prefs.getString("hostport", ""), 13, MUTED); where.setPadding(0, dp(4), 0, dp(36)); root.addView(where);

        big = new Button(this); big.setAllCaps(false); big.setTextSize(20); big.setTypeface(null, Typeface.BOLD); big.setTextColor(Color.WHITE);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(PANEL); circle.setStroke(dp(3), 0xFF2A2F3D);
        big.setBackground(circle);
        root.addView(big, new LinearLayout.LayoutParams(dp(180), dp(180)));
        big.setOnClickListener(v -> toggle());

        status = text("", 16, TEXT); status.setGravity(Gravity.CENTER); status.setPadding(0, dp(24), 0, dp(4)); root.addView(status);
        stats = text("", 13, MUTED); stats.setGravity(Gravity.CENTER); stats.setPadding(0, 0, 0, dp(28)); root.addView(stats);

        modeRow = row(" ", " ", v -> { prefs.edit().putBoolean("dns_only", !PcVpnService.dnsOnly(this)).apply(); renderMode(); PcVpnService.restartIfActive(this); });
        root.addView(modeRow); renderMode();
        root.addView(row("Приложения напрямую", "эти приложения минуют ПК", v -> pickApps()));
        root.addView(row("Плитка в шторке", "«Через ПК» среди быстрых настроек: ✎ Изменить → перетащите", null));
        String ver = "?"; try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        updRow = row("Проверить обновления", "версия " + ver + " · обновления выходят вместе с PC Remote", v -> checkUpdate(true));
        root.addView(updRow);
        root.addView(row("Отвязать от ПК", "", v -> { PcVpnService.stop(this); prefs.edit().clear().apply(); showSetup(null); }));
        TextView sig = text("Создано Николаем Коноваловым для вас, с любовью.", 12, MUTED); sig.setGravity(Gravity.CENTER); sig.setPadding(0, dp(16), 0, 0); root.addView(sig);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(BG);
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(sv);
        render();
    }

    private void renderMode() {
        if (modeRow == null) return;
        boolean dnsOnly = PcVpnService.dnsOnly(this);
        ((TextView) modeRow.getChildAt(0)).setText(dnsOnly ? "Режим: только DNS через ПК" : "Режим: весь интернет через ПК");
        ((TextView) modeRow.getChildAt(1)).setText(dnsOnly ? "реклама режется на ПК, данные идут напрямую — быстрее; нажмите, чтобы пускать весь трафик через ПК и его VPN"
                : "весь трафик и VPN ПК; нажмите, чтобы оставить только DNS (быстрее, без двойного пути)");
    }

    private void render() {
        if (big == null) return;
        String st = PcVpnService.state;
        boolean active = PcVpnService.isActive();
        GradientDrawable d = (GradientDrawable) big.getBackground();
        d.setColor("on".equals(st) ? ACCENT : "connecting".equals(st) ? 0xFF2A5A44 : "waiting".equals(st) ? 0xFF5A3A1E : PANEL);
        big.setText("on".equals(st) ? "ВКЛ" : "connecting".equals(st) ? "…" : "waiting".equals(st) ? "ЖДУ ПК" : "ВЫКЛ");
        status.setText("on".equals(st) ? "Подключено к ПК" : "connecting".equals(st) ? "Подключаюсь к ПК…" : "waiting".equals(st) ? "ПК недоступен — интернет напрямую, пробую снова" : "Выключено — интернет напрямую");
        String det = PcVpnService.detail;
        stats.setText(("on".equals(st) ? "DNS-запросов " + PcVpnService.dnsQueries + " · из кэша ПК " + PcVpnService.dnsCached + " · заблокировано рекламы " + PcVpnService.blockedAds + "\n" : "") + (det == null ? "" : det));
        if (!active) ui.removeCallbacksAndMessages(null); else ui.postDelayed(this::render, 3000);
    }

    private void toggle() {
        if (PcVpnService.isActive()) { PcVpnService.stop(this); render(); return; }
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, REQ_VPN); else PcVpnService.start(this);
        render();
    }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req == REQ_VPN) {
            if (code == RESULT_OK) PcVpnService.start(this);
            else Toast.makeText(this, "Без разрешения на VPN интернет через ПК не заработает", Toast.LENGTH_LONG).show();
            render();
        }
    }

    /** Installed apps with internet access; checked ones bypass the PC. */
    private void pickApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo a : pm.getInstalledApplications(0)) {
            if (a.packageName.equals(getPackageName()) || a.packageName.equals("ru.pcremote")) continue;
            if (pm.checkPermission("android.permission.INTERNET", a.packageName) != PackageManager.PERMISSION_GRANTED) continue;
            if ((a.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && pm.getLaunchIntentForPackage(a.packageName) == null) continue;
            apps.add(a);
        }
        Collections.sort(apps, (x, y) -> String.valueOf(pm.getApplicationLabel(x)).compareToIgnoreCase(String.valueOf(pm.getApplicationLabel(y))));
        final Set<String> direct = new HashSet<>(prefs.getStringSet("direct", new HashSet<>()));
        String[] names = new String[apps.size()]; boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) { names[i] = String.valueOf(pm.getApplicationLabel(apps.get(i))); checked[i] = direct.contains(apps.get(i).packageName); }
        new AlertDialog.Builder(this).setTitle("Напрямую, минуя ПК")
                .setMultiChoiceItems(names, checked, (dlg, i, on) -> { if (on) direct.add(apps.get(i).packageName); else direct.remove(apps.get(i).packageName); })
                .setPositiveButton("Готово", (dlg, w) -> {
                    prefs.edit().putStringSet("direct", direct).apply();
                    if (PcVpnService.isActive()) { PcVpnService.stop(this); ui.postDelayed(() -> PcVpnService.start(this), 800); }   // the interface is rebuilt with the new list
                })
                .setNegativeButton("Отмена", null).show();
    }

    // ----------------------------------------------------------- update ---
    private void checkUpdate() { checkUpdate(false); }

    /** At every launch, silently (one small request to GitHub); from the row, with a word back either way. */
    private void checkUpdate(boolean manual) {
        if (manual && updRow != null) ((TextView) updRow.getChildAt(1)).setText("проверяю…");
        Updater.pc = prefs.contains("secret") ? new Updater.Pc(prefs.getString("host", ""), prefs.getInt("port", 8443), prefs.getString("pin", ""), prefs.getString("secret", ""), prefs.getString("lan", "")) : null;
        new Thread(() -> {
            try {
                String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                Updater.Info found = null; boolean fromPc = false;
                if (Updater.pc != null) { try { found = Updater.checkPc(cur, "pcremote-net.apk"); fromPc = found != null; } catch (Exception ignored) {} }
                if (found == null) found = Updater.check(cur, "pcremote-net.apk");
                final Updater.Info info = found;
                if (info == null) {
                    if (manual) runOnUiThread(() -> { Toast.makeText(this, "Это последняя версия (" + cur + ")", Toast.LENGTH_SHORT).show(); if (updRow != null) ((TextView) updRow.getChildAt(1)).setText("версия " + cur + " · это последняя"); });
                    return;
                }
                if (manual) runOnUiThread(() -> { if (updRow != null) ((TextView) updRow.getChildAt(1)).setText("скачиваю " + info.version + "…"); });
                java.io.File apk = fromPc ? Updater.downloadPc("pcremote-net.apk", getCacheDir(), info.sha256) : Updater.download(info.url, getCacheDir(), info.sha256);
                final String src = fromPc ? " (с ПК)" : " (с GitHub)";
                runOnUiThread(() -> {
                    Toast.makeText(this, "Обновление " + info.version + src + " — установите", Toast.LENGTH_LONG).show();
                    if (updRow != null) ((TextView) updRow.getChildAt(1)).setText("версия " + cur + " → " + info.version + ": установите");
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://" + ApkProvider.AUTHORITY + "/update.apk"),
                            "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> { Toast.makeText(this, "Не удалось проверить: " + e.getMessage(), Toast.LENGTH_LONG).show(); if (updRow != null) ((TextView) updRow.getChildAt(1)).setText("не удалось проверить, попробуйте позже"); });
            }
        }).start();
    }

    // --------------------------------------------------------------- ui ---
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setBackgroundColor(ACCENT); b.setAllCaps(false); b.setTypeface(null, Typeface.BOLD); return b; }
    private EditText input(String value, String hint, int type) {
        EditText e = new EditText(this); e.setText(value); e.setHint(hint); e.setInputType(type);
        e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setBackgroundColor(BG); e.setPadding(dp(12), dp(12), dp(12), dp(12)); e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(14)); e.setLayoutParams(lp); return e;
    }
    private LinearLayout row(String title, String sub, android.view.View.OnClickListener onClick) {
        LinearLayout r = column(); r.setBackgroundColor(PANEL); r.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = text(title, 16, TEXT); r.addView(t);
        if (!sub.isEmpty()) { TextView s = text(sub, 12, MUTED); r.addView(s); }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0, 0, 0, dp(10)); r.setLayoutParams(lp);
        if (onClick != null) r.setOnClickListener(onClick);
        return r;
    }
}
