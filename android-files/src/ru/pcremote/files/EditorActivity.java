package ru.pcremote.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** A plain text editor: open, edit, find, save (and "save as"), wrap on/off, line count. Files up to 2 MB. */
public class EditorActivity extends Activity {
    public static final String EXTRA_PATH = "path";
    static final int BG = 0xFF0F1117, PANEL = 0xFF181B24, TEXT = 0xFFEEF0F5, MUTED = 0xFF8E94A6, ACCENT = 0xFFE0A030;
    private File file; private EditText edit; private TextView title, info; private boolean dirty, wrap = true; private HorizontalScrollView hscroll; private String lastFind = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        file = new File(getIntent().getStringExtra(EXTRA_PATH));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setBackgroundColor(PANEL); bar.setPadding(dp(4), dp(6), dp(4), dp(6));
        bar.addView(icon(R.drawable.ic_back, v -> onBackPressed()));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(6), 0, dp(6), 0);
        title = text(file.getName(), 16, TEXT); title.setTypeface(null, Typeface.BOLD); title.setSingleLine(true);
        info = text("", 12, MUTED); t.addView(title); t.addView(info);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(icon(R.drawable.ic_search, v -> find()));
        bar.addView(icon(R.drawable.ic_list, v -> { wrap = !wrap; setContentView(build(root)); }));
        ImageButton save = icon(R.drawable.ic_check, v -> save(file)); bar.addView(save);
        bar.addView(icon(R.drawable.ic_more, v -> {
            android.widget.PopupMenu m = new android.widget.PopupMenu(this, v); m.getMenu().add("Сохранить как…"); m.getMenu().add("Перейти к строке…"); m.getMenu().add("Поделиться");
            m.setOnMenuItemClickListener(mi -> { String s = String.valueOf(mi.getTitle());
                if (s.startsWith("Сохранить как")) ask("Имя файла", file.getName(), n -> save(new File(file.getParentFile(), n)));
                else if (s.startsWith("Перейти")) ask("Номер строки", "", n -> gotoLine(n));
                else startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, FileProvider.uriFor(file)).setType("text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться"));
                return true; });
            m.show(); }));
        root.addView(bar);
        edit = new EditText(this); edit.setBackgroundColor(BG); edit.setTextColor(TEXT); edit.setTextSize(14); edit.setTypeface(Typeface.MONOSPACE); edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setPadding(dp(12), dp(10), dp(12), dp(120)); edit.setHint("Пустой файл"); edit.setHintTextColor(MUTED);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a1, int a2, int a3) {} public void onTextChanged(CharSequence s, int a1, int a2, int a3) {} public void afterTextChanged(Editable s) { dirty = true; updateInfo(); } });
        try {
            if (file.length() > 2L * 1024 * 1024) { Toast.makeText(this, "Файл больше 2 МБ: откройте другим приложением", Toast.LENGTH_LONG).show(); finish(); return; }
            edit.setText(file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "");
        } catch (Exception e) { Toast.makeText(this, "Не удалось открыть: " + e.getMessage(), Toast.LENGTH_LONG).show(); finish(); return; }
        dirty = false;
        setContentView(build(root)); updateInfo();
    }

    private View build(LinearLayout root) {
        if (root.getChildCount() > 1) root.removeViewAt(1);
        edit.setHorizontallyScrolling(!wrap);
        View body;
        if (wrap) { ScrollView sv = new ScrollView(this); sv.addView(edit, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); body = sv; }
        else { ScrollView sv = new ScrollView(this); hscroll = new HorizontalScrollView(this); hscroll.addView(edit, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)); sv.addView(hscroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); body = sv; }
        if (edit.getParent() != null && !(edit.getParent() == body || edit.getParent() == hscroll)) ((ViewGroup) edit.getParent()).removeView(edit);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void updateInfo() {
        String s = edit.getText().toString(); int lines = s.isEmpty() ? 0 : s.split("\n", -1).length;
        info.setText(lines + " " + MainActivity.plural(lines, "строка", "строки", "строк") + " · " + Fs.size(s.getBytes(StandardCharsets.UTF_8).length) + (dirty ? " · изменён" : "") + (wrap ? "" : " · без переноса"));
    }

    private void save(File to) {
        try (FileOutputStream o = new FileOutputStream(to)) { o.write(edit.getText().toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { Toast.makeText(this, "Не сохранилось: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        file = to; title.setText(file.getName()); dirty = false; updateInfo(); setResult(RESULT_OK);
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
    }

    private void find() {
        ask("Найти", lastFind, q -> {
            if (q.isEmpty()) return; lastFind = q;
            String s = edit.getText().toString().toLowerCase(); int from = edit.getSelectionEnd();
            int i = s.indexOf(q.toLowerCase(), from); if (i < 0) i = s.indexOf(q.toLowerCase());
            if (i < 0) { Toast.makeText(this, "Не найдено", Toast.LENGTH_SHORT).show(); return; }
            edit.requestFocus(); edit.setSelection(i, i + q.length());
        });
    }

    private void gotoLine(String n) {
        try { int line = Integer.parseInt(n.trim()); String s = edit.getText().toString(); int pos = 0; for (int i = 1; i < line && pos >= 0; i++) pos = s.indexOf('\n', pos) + 1; if (pos < 0) pos = s.length(); edit.requestFocus(); edit.setSelection(Math.min(pos, s.length())); } catch (Exception ignored) {}
    }

    @Override public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setMessage("Сохранить изменения в «" + file.getName() + "»?").setPositiveButton("Сохранить", (d, w) -> { save(file); finish(); }).setNegativeButton("Не сохранять", (d, w) -> finish()).setNeutralButton("Отмена", null).show();
    }

    interface Answer { void on(String s); }
    private void ask(String t, String value, Answer a) {
        EditText e = new EditText(this); e.setText(value); e.setSingleLine(true); e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(t).setView(e).setPositiveButton("Ок", (d, w) -> a.on(e.getText().toString().trim())).setNegativeButton("Отмена", null).show();
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private ImageButton icon(int res, View.OnClickListener l) { ImageButton b = new ImageButton(this); b.setImageResource(res); b.setColorFilter(TEXT); b.setBackground(null); b.setPadding(dp(10), dp(10), dp(10), dp(10)); b.setOnClickListener(l); return b; }
}
