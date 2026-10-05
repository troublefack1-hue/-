package ru.pcremote.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.TranslateAnimation;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ru.pcremote.Updater;

/**
 * «Проводник»: a file manager that does one thing well and asks nothing in return.
 * No ads, no purchases, no analytics, no network except the update check against our own releases.
 */
public class MainActivity extends Activity {
    static final int BG = 0xFF0F1117, PANEL = 0xFF181B24, PANEL2 = 0xFF222633, TEXT = 0xFFEEF0F5, MUTED = 0xFF8E94A6, ACCENT = 0xFFE0A030, BAD = 0xFFEF5350, OK = 0xFF38D070;
    private static final int REQ_PERM = 1, REQ_VIEW = 2;

    private SharedPreferences prefs;
    private Thumbs thumbs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // navigation state
    private File dir;                       // null = the roots screen
    private boolean trashMode, searchMode;
    private final ArrayList<File> history = new ArrayList<>();
    private List<Fs.Entry> items = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean grid, hidden, desc;
    private Fs.Sort sort = Fs.Sort.NAME;

    // clipboard
    private final ArrayList<File> clip = new ArrayList<>();
    private boolean clipCut;

    // views
    private LinearLayout root, actionBar, pasteBar;
    private TextView title, subtitle, pasteText;
    private LinearLayout crumbs;
    private AbsListView listView;
    private ImageButton viewBtn;
    private View fab, empty;
    private TextView emptyText;
    private final Adapter adapter = new Adapter();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("files", MODE_PRIVATE);
        grid = prefs.getBoolean("grid", false); hidden = prefs.getBoolean("hidden", false); desc = prefs.getBoolean("desc", false);
        try { sort = Fs.Sort.valueOf(prefs.getString("sort", "NAME")); } catch (Exception ignored) {}
        thumbs = new Thumbs(this, dp(96));
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        buildUi();
        if (!hasStorage()) { showPermission(); return; }
        File start = startFolder(getIntent());
        if (start != null) open(start); else showRoots();
        checkUpdate();
    }

    private File startFolder(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null && "file".equals(i.getData().getScheme())) {
            File f = new File(i.getData().getPath()); return f.isDirectory() ? f : f.getParentFile();
        }
        String last = prefs.getString("last", "");
        return !last.isEmpty() && new File(last).isDirectory() ? new File(last) : null;
    }

    @Override protected void onResume() { super.onResume(); if (hasStorage() && dir != null && !searchMode) refresh(); }

    // ---------------------------------------------------------- permission ---
    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void showPermission() {
        LinearLayout box = column(); box.setGravity(Gravity.CENTER); int p = dp(28); box.setPadding(p, p, p, p); box.setBackgroundColor(BG);
        TextView t = text("Проводнику нужен доступ к файлам", 22, TEXT); t.setTypeface(null, Typeface.BOLD); t.setGravity(Gravity.CENTER); box.addView(t);
        TextView s = text("Иначе показывать нечего. Доступ используется только для того, что вы делаете сами: никакой рекламы, аналитики и отправки данных.", 15, MUTED);
        s.setGravity(Gravity.CENTER); s.setPadding(0, dp(12), 0, dp(24)); box.addView(s);
        Button btn = button("Разрешить"); box.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        btn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 30) startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())), REQ_PERM);
            else requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        });
        setContentView(box);
    }

    @Override protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req == REQ_PERM) { if (hasStorage()) { setContentView(root); showRoots(); } else showPermission(); }
        if (req == REQ_VIEW && code == RESULT_OK && dir != null) refresh();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERM) { if (hasStorage()) { setContentView(root); showRoots(); } else showPermission(); }
    }

    // ---------------------------------------------------------------- ui ---
    private void buildUi() {
        root = column(); root.setBackgroundColor(BG);
        // top bar
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(PANEL); bar.setPadding(dp(4), dp(6), dp(4), dp(6));
        bar.addView(icon(R.drawable.ic_back, v -> onBackPressed()));
        LinearLayout titles = column(); titles.setPadding(dp(6), 0, dp(6), 0);
        title = text("Проводник", 18, TEXT); title.setTypeface(null, Typeface.BOLD); title.setSingleLine(true);
        subtitle = text("", 12, MUTED); subtitle.setSingleLine(true); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.START);
        titles.addView(title); titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(icon(R.drawable.ic_search, v -> askSearch()));
        viewBtn = icon(grid ? R.drawable.ic_list : R.drawable.ic_grid, v -> { grid = !grid; prefs.edit().putBoolean("grid", grid).apply(); viewBtn.setImageResource(grid ? R.drawable.ic_list : R.drawable.ic_grid); rebuildList(); animateList(); });
        bar.addView(viewBtn);
        bar.addView(icon(R.drawable.ic_more, this::menu));
        root.addView(bar);
        // breadcrumbs
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.setBackgroundColor(PANEL);
        crumbs = new LinearLayout(this); crumbs.setOrientation(LinearLayout.HORIZONTAL); crumbs.setPadding(dp(8), 0, dp(8), dp(6));
        hs.addView(crumbs); root.addView(hs);
        // list container
        FrameLayout stage = new FrameLayout(this); root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout body = column(); stage.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rebuildList(body);
        // empty state
        LinearLayout em = column(); em.setGravity(Gravity.CENTER); em.setVisibility(View.GONE);
        ImageView ei = new ImageView(this); ei.setImageResource(R.drawable.ic_empty); ei.setColorFilter(0x553A3F4D); em.addView(ei, new LinearLayout.LayoutParams(dp(96), dp(96)));
        emptyText = text("Пусто", 15, MUTED); emptyText.setGravity(Gravity.CENTER); emptyText.setPadding(dp(32), dp(8), dp(32), 0); em.addView(emptyText);
        empty = em; stage.addView(em, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // floating "+" : new folder / new text file
        ImageButton plus = new ImageButton(this); plus.setImageResource(R.drawable.ic_add); plus.setColorFilter(Color.BLACK);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(ACCENT);
        plus.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), circle, null)); plus.setElevation(dp(6));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END); fp.setMargins(0, 0, dp(18), dp(18));
        plus.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v); m.getMenu().add("Новая папка"); m.getMenu().add("Новый текстовый файл");
            m.setOnMenuItemClickListener(mi -> { if (String.valueOf(mi.getTitle()).startsWith("Новая папка")) ask("Имя папки", "Новая папка", n -> { try { Fs.mkdir(dir, n); refresh(); } catch (Exception e) { toast(e.getMessage()); } });
                else ask("Имя файла", "Заметка.txt", n -> { try { File f = new File(dir, n); if (n.isEmpty() || n.contains("/") || f.exists()) throw new java.io.IOException("недопустимое имя или файл уже есть"); if (!f.createNewFile()) throw new java.io.IOException("не удалось создать"); refresh(); } catch (Exception e) { toast(e.getMessage()); } }); return true; });
            m.show(); });
        fab = plus; stage.addView(plus, fp); plus.setVisibility(View.GONE);
        // paste bar
        pasteBar = new LinearLayout(this); pasteBar.setOrientation(LinearLayout.HORIZONTAL); pasteBar.setGravity(Gravity.CENTER_VERTICAL);
        pasteBar.setBackgroundColor(PANEL2); pasteBar.setPadding(dp(14), dp(8), dp(8), dp(8)); pasteBar.setVisibility(View.GONE);
        pasteText = text("", 14, TEXT); pasteBar.addView(pasteText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button paste = button("Вставить сюда"); paste.setOnClickListener(v -> paste()); pasteBar.addView(paste);
        pasteBar.addView(icon(R.drawable.ic_close, v -> { clip.clear(); updateBars(); }));
        root.addView(pasteBar);
        // selection action bar
        actionBar = new LinearLayout(this); actionBar.setOrientation(LinearLayout.HORIZONTAL); actionBar.setBackgroundColor(PANEL2); actionBar.setVisibility(View.GONE);
        int[] icons = {R.drawable.ic_copy, R.drawable.ic_cut, R.drawable.ic_delete, R.drawable.ic_share, R.drawable.ic_dots};
        String[] labels = {"Копировать", "Вырезать", "Удалить", "Поделиться", "Ещё"};
        for (int k = 0; k < icons.length; k++) {
            LinearLayout cell = column(); cell.setGravity(Gravity.CENTER); cell.setPadding(0, dp(8), 0, dp(8));
            cell.setBackground(ripple(Color.TRANSPARENT));
            ImageView ic = new ImageView(this); ic.setImageResource(icons[k]); ic.setColorFilter(TEXT); cell.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView lb = text(labels[k], 11, MUTED); lb.setPadding(0, dp(2), 0, 0); cell.addView(lb);
            final String action = labels[k];
            cell.setOnClickListener(v -> onAction(action, v));
            actionBar.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(actionBar);
        setContentView(root);
    }

    private void rebuildList() { LinearLayout body = (LinearLayout) listView.getParent(); rebuildList(body); adapter.notifyDataSetChanged(); }

    /** Rows glide in one after another when a folder opens: cheap, and it tells the eye "new place". */
    private void animateList() {
        AnimationSet set = new AnimationSet(true);
        AlphaAnimation a = new AlphaAnimation(0f, 1f); a.setDuration(180);
        TranslateAnimation t = new TranslateAnimation(0, 0, dp(18), 0); t.setDuration(220); t.setInterpolator(new DecelerateInterpolator());
        set.addAnimation(a); set.addAnimation(t);
        LayoutAnimationController c = new LayoutAnimationController(set, grid ? 0.03f : 0.05f);
        listView.setLayoutAnimation(c); listView.startLayoutAnimation();
    }

    private RippleDrawable ripple(int base) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(base); bg.setCornerRadius(dp(12));
        return new RippleDrawable(ColorStateList.valueOf(0x33E0A030), bg, null);
    }

    private void rebuildList(LinearLayout body) {
        body.removeAllViews();
        if (grid) { GridView g = new GridView(this); g.setNumColumns(GridView.AUTO_FIT); g.setColumnWidth(dp(110)); g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); g.setVerticalSpacing(dp(6)); listView = g; }
        else { ListView l = new ListView(this); l.setDivider(null); listView = l; }
        listView.setBackgroundColor(BG); listView.setAdapter(adapter); listView.setPadding(dp(6), dp(4), dp(6), dp(80)); listView.setClipToPadding(false);
        listView.setSelector(android.R.color.transparent);
        listView.setOnItemClickListener((p, v, pos, id) -> onTap(items.get(pos)));
        listView.setOnItemLongClickListener((p, v, pos, id) -> { toggle(items.get(pos)); return true; });
        body.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // -------------------------------------------------------------- roots ---
    static final class Root { final String name, hint; final File file; final int icon; float used = -1; Root(String n, String h, File f, int i) { name = n; hint = h; file = f; icon = i; } }

    private List<Root> roots() {
        List<Root> r = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        Root main = new Root("Внутренняя память", usage(ext), ext, R.drawable.ic_sd); main.used = usedFraction(ext); r.add(main);
        for (File f : getExternalFilesDirs(null)) {
            if (f == null) continue;
            String p = f.getAbsolutePath(); int i = p.indexOf("/Android/");
            if (i > 0 && !p.startsWith(ext.getAbsolutePath())) { File card = new File(p.substring(0, i)); Root sd = new Root("SD-карта", usage(card), card, R.drawable.ic_sd); sd.used = usedFraction(card); r.add(sd); }
        }
        r.add(new Root("Загрузки", "", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), R.drawable.ic_folder));
        r.add(new Root("Камера", "", new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"), R.drawable.ic_image));
        r.add(new Root("Документы", "", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), R.drawable.ic_doc));
        r.add(new Root("Корзина", "удалённое можно вернуть", Fs.trashDir(ext), R.drawable.ic_trash));
        return r;
    }

    private float usedFraction(File f) { try { StatFs s = new StatFs(f.getAbsolutePath()); return 1f - (float) s.getAvailableBytes() / Math.max(1, s.getTotalBytes()); } catch (Exception e) { return -1; } }

    private String usage(File f) {
        try { StatFs s = new StatFs(f.getAbsolutePath()); long total = s.getTotalBytes(), free = s.getAvailableBytes(); return "свободно " + Fs.size(free) + " из " + Fs.size(total); }
        catch (Exception e) { return ""; }
    }

    private void showRoots() {
        dir = null; trashMode = false; searchMode = false; selected.clear(); history.clear();
        title.setText("Проводник"); subtitle.setText("без рекламы, без слежки, ваши файлы — ваши");
        crumbs.removeAllViews();
        items = new ArrayList<>();
        for (Root r : roots()) items.add(new RootEntry(r));
        adapter.notifyDataSetChanged(); updateBars(); animateList();
        empty.setVisibility(View.GONE); fab.setVisibility(View.GONE);
    }

    /** A root shown in the same list, with its own icon and hint. */
    static final class RootEntry extends Fs.Entry { final Root r; RootEntry(Root r) { super(r.file); this.r = r; } }

    // ---------------------------------------------------------- navigation ---
    private void open(File d) {
        if (dir != null && !searchMode) history.add(dir);
        dir = d; searchMode = false; selected.clear();
        trashMode = d.getAbsolutePath().equals(Fs.trashDir(Environment.getExternalStorageDirectory()).getAbsolutePath());
        prefs.edit().putString("last", d.getAbsolutePath()).apply();
        refresh();
    }

    private void refresh() {
        if (dir == null) { showRoots(); return; }
        List<Fs.Entry> l = Fs.list(dir, hidden, sort, desc);
        if (trashMode) { List<Fs.Entry> t = new ArrayList<>(); for (Fs.Entry e : l) if (!e.name.endsWith(".origin")) t.add(e); l = t; }
        items = l;
        title.setText(trashMode ? "Корзина" : dir.getName().isEmpty() ? "/" : dir.getName());
        subtitle.setText(items.size() + " " + plural(items.size(), "объект", "объекта", "объектов") + " · " + dir.getAbsolutePath());
        buildCrumbs(); adapter.notifyDataSetChanged(); updateBars(); animateList();
        listView.setSelection(0);
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        emptyText.setText(trashMode ? "Корзина пуста" : hidden ? "Папка пуста" : "Папка пуста\n(скрытые файлы выключены в меню)");
        fab.setVisibility(trashMode ? View.GONE : View.VISIBLE);
    }

    private void buildCrumbs() {
        crumbs.removeAllViews();
        File ext = Environment.getExternalStorageDirectory();
        List<File> chain = new ArrayList<>();
        for (File f = dir; f != null; f = f.getParentFile()) { chain.add(0, f); if (f.equals(ext)) break; }
        TextView home = crumb("Начало", null); crumbs.addView(home);
        for (File f : chain) {
            String n = f.equals(ext) ? "Память" : f.getName().isEmpty() ? "/" : f.getName();
            crumbs.addView(text(" › ", 14, MUTED));
            crumbs.addView(crumb(n, f));
        }
    }

    private TextView crumb(String label, File target) {
        TextView t = text(label, 14, target != null && target.equals(dir) ? TEXT : ACCENT); t.setPadding(dp(4), dp(4), dp(4), dp(4));
        t.setOnClickListener(v -> { if (target == null) showRoots(); else if (!target.equals(dir)) open(target); });
        return t;
    }

    @Override public void onBackPressed() {
        if (!selected.isEmpty()) { selected.clear(); adapter.notifyDataSetChanged(); updateBars(); return; }
        if (searchMode) { searchMode = false; refresh(); return; }
        if (dir == null) { super.onBackPressed(); return; }
        if (!history.isEmpty()) { File prev = history.remove(history.size() - 1); dir = prev; selected.clear(); trashMode = false; refresh(); return; }
        File ext = Environment.getExternalStorageDirectory();
        if (dir.equals(ext) || dir.getParentFile() == null || trashMode) { showRoots(); return; }
        dir = dir.getParentFile(); refresh();
    }

    // ---------------------------------------------------------------- taps ---
    private void onTap(Fs.Entry e) {
        if (!selected.isEmpty()) { toggle(e); return; }
        if (e instanceof RootEntry) { File f = e.file; if (!f.exists()) f.mkdirs(); open(f); return; }
        if (e.dir) { open(e.file); return; }
        if (e.kind == Fs.Kind.IMAGE) {
            List<File> imgs = new ArrayList<>(); int at = 0;
            for (Fs.Entry x : items) { if (x.kind == Fs.Kind.IMAGE) { if (x.file.equals(e.file)) at = imgs.size(); imgs.add(x.file); } }
            if (imgs.size() > 3000) { imgs = new ArrayList<>(); imgs.add(e.file); at = 0; }
            String[] a = new String[imgs.size()]; for (int i = 0; i < a.length; i++) a[i] = imgs.get(i).getAbsolutePath();
            startActivityForResult(new Intent(this, ViewerActivity.class).putExtra(ViewerActivity.EXTRA_FILES, a).putExtra(ViewerActivity.EXTRA_INDEX, at), REQ_VIEW);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            return;
        }
        openFile(e.file);
    }

    private void toggle(Fs.Entry e) {
        if (e instanceof RootEntry) return;
        String k = e.file.getAbsolutePath();
        if (!selected.remove(k)) selected.add(k);
        adapter.notifyDataSetChanged(); updateBars();
    }

    private void openFile(File f) {
        String mime = Fs.mime(f.getName());
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.uriFor(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); }
        catch (Exception e) {
            try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.uriFor(f), "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть с помощью")); }
            catch (Exception e2) { toast("Нечем открыть этот файл"); }
        }
    }

    private void updateBars() {
        boolean showActions = !selected.isEmpty();
        if (showActions && actionBar.getVisibility() != View.VISIBLE) { actionBar.setVisibility(View.VISIBLE); actionBar.setTranslationY(dp(60)); actionBar.setAlpha(0f); actionBar.animate().translationY(0).alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start(); }
        else if (!showActions && actionBar.getVisibility() == View.VISIBLE) { actionBar.animate().translationY(dp(60)).alpha(0f).setDuration(150).withEndAction(() -> actionBar.setVisibility(View.GONE)).start(); }
        if (fab != null) fab.animate().scaleX(showActions || dir == null || trashMode ? 0f : 1f).scaleY(showActions || dir == null || trashMode ? 0f : 1f).setDuration(150).start();
        boolean canPaste = !clip.isEmpty() && dir != null && !trashMode && selected.isEmpty();
        pasteBar.setVisibility(canPaste ? View.VISIBLE : View.GONE);
        if (canPaste) pasteText.setText((clipCut ? "Переместить " : "Скопировать ") + clip.size() + " " + plural(clip.size(), "объект", "объекта", "объектов") + " в «" + title.getText() + "»");
        if (!selected.isEmpty()) title.setText("Выбрано: " + selected.size()); else if (dir != null && !searchMode) title.setText(trashMode ? "Корзина" : dir.getName().isEmpty() ? "/" : dir.getName());
    }

    private List<File> selectedFiles() { List<File> l = new ArrayList<>(); for (String p : selected) l.add(new File(p)); return l; }

    // ------------------------------------------------------------- actions ---
    private void onAction(String a, View anchor) {
        List<File> sel = selectedFiles();
        if (sel.isEmpty()) return;
        switch (a) {
            case "Копировать": clip.clear(); clip.addAll(sel); clipCut = false; selected.clear(); adapter.notifyDataSetChanged(); updateBars(); toast("Скопировано, откройте папку и нажмите «Вставить сюда»"); break;
            case "Вырезать": clip.clear(); clip.addAll(sel); clipCut = true; selected.clear(); adapter.notifyDataSetChanged(); updateBars(); toast("Вырезано, откройте папку и нажмите «Вставить сюда»"); break;
            case "Удалить": if (trashMode) confirm("Удалить навсегда " + sel.size() + " " + plural(sel.size(), "объект", "объекта", "объектов") + "?", () -> run("Удаляю", (p) -> { for (File f : sel) Fs.purge(f); }));
                            else run("Удаляю в корзину", (p) -> { File rt = Environment.getExternalStorageDirectory(); for (File f : sel) Fs.toTrash(f, rt); }); break;
            case "Поделиться": share(sel); break;
            case "Ещё": {
                PopupMenu m = new PopupMenu(this, anchor);
                if (trashMode) m.getMenu().add("Восстановить");
                else { if (sel.size() == 1) m.getMenu().add("Переименовать"); m.getMenu().add("Упаковать в zip"); if (sel.size() == 1 && Fs.ext(sel.get(0).getName()).equals("zip")) m.getMenu().add("Распаковать"); if (sel.size() == 1) m.getMenu().add("Открыть с помощью…"); }
                m.getMenu().add("Выбрать все"); m.getMenu().add("Свойства");
                m.setOnMenuItemClickListener(mi -> { onMore(String.valueOf(mi.getTitle()), sel); return true; });
                m.show(); break;
            }
        }
    }

    private void onMore(String what, List<File> sel) {
        switch (what) {
            case "Восстановить": run("Восстанавливаю", (p) -> { for (File f : sel) Fs.restore(f); }); break;
            case "Переименовать": ask("Новое имя", sel.get(0).getName(), n -> { try { Fs.rename(sel.get(0), n); selected.clear(); refresh(); } catch (Exception e) { toast(e.getMessage()); } }); break;
            case "Упаковать в zip": ask("Имя архива", sel.size() == 1 ? sel.get(0).getName().replaceAll("\\.[^.]+$", "") : dir.getName(), n -> run("Упаковываю", (p) -> Fs.zip(sel, dir, n, p))); break;
            case "Распаковать": run("Распаковываю", (p) -> Fs.unzip(sel.get(0), dir, p)); break;
            case "Открыть с помощью…": startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.uriFor(sel.get(0)), "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть с помощью")); break;
            case "Выбрать все": for (Fs.Entry e : items) selected.add(e.file.getAbsolutePath()); adapter.notifyDataSetChanged(); updateBars(); break;
            case "Свойства": properties(sel); break;
        }
    }

    private void paste() {
        final List<File> src = new ArrayList<>(clip); final boolean cut = clipCut; final File target = dir;
        for (File f : src) if (f.isDirectory() && target.getAbsolutePath().startsWith(f.getAbsolutePath() + File.separator)) { toast("Нельзя вставить папку саму в себя"); return; }
        clip.clear();
        run(cut ? "Перемещаю" : "Копирую", (p) -> { for (File f : src) { if (cut) Fs.move(f, target, p); else Fs.copy(f, target, p); } });
    }

    private void share(List<File> sel) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : sel) if (f.isFile()) uris.add(FileProvider.uriFor(f));
        if (uris.isEmpty()) { toast("Папки так не отправить: упакуйте в zip"); return; }
        Intent i = uris.size() == 1 ? new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0)).setType(Fs.mime(sel.get(0).getName()))
                : new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Поделиться"));
    }

    private void properties(List<File> sel) {
        final AlertDialog d = new AlertDialog.Builder(this).setTitle(sel.size() == 1 ? sel.get(0).getName() : sel.size() + " объектов").setMessage("Считаю…").setPositiveButton("Закрыть", null).show();
        new Thread(() -> {
            long size = 0; int files = 0, dirs = 0;
            for (File f : sel) { size += Fs.treeSize(f); if (f.isDirectory()) dirs++; else files++; }
            StringBuilder sb = new StringBuilder();
            if (sel.size() == 1) { File f = sel.get(0); sb.append("Путь: ").append(f.getAbsolutePath()).append("\n"); if (f.isDirectory()) sb.append("Внутри: ").append(Fs.count(f)).append(" объектов\n"); else sb.append("Тип: ").append(Fs.mime(f.getName())).append("\n"); sb.append("Изменён: ").append(Fs.date(f.lastModified())).append("\n"); }
            else sb.append("Файлов: ").append(files).append(", папок: ").append(dirs).append("\n");
            sb.append("Размер: ").append(Fs.size(size)).append(" (").append(String.format(java.util.Locale.ROOT, "%,d", size)).append(" байт)");
            final String msg = sb.toString();
            ui.post(() -> d.setMessage(msg));
        }).start();
    }

    interface Op { void run(Fs.Progress p) throws Exception; }

    /** Runs a file operation on a thread with a cancellable progress dialog; refreshes afterwards. */
    private void run(String what, Op op) {
        LinearLayout box = column(); int p = dp(20); box.setPadding(p, p, p, p);
        TextView cur = text("", 13, MUTED); box.addView(cur);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000); box.addView(pb);
        final boolean[] cancel = {false};
        AlertDialog d = new AlertDialog.Builder(this).setTitle(what + "…").setView(box).setCancelable(false).setNegativeButton("Отмена", (dlg, w) -> cancel[0] = true).show();
        new Thread(() -> {
            String err = null;
            try {
                op.run((name, done, total) -> { ui.post(() -> { cur.setText(name); pb.setProgress(total > 0 ? (int) (done * 1000 / total) : 0); }); return !cancel[0]; });
            } catch (Fs.Cancelled c) { err = "Отменено";
            } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String fe = err;
            ui.post(() -> { try { d.dismiss(); } catch (Exception ignored) {} selected.clear(); if (fe != null) toast(fe); refresh(); });
        }).start();
    }

    // ---------------------------------------------------------------- menu ---
    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (dir != null && !trashMode) { m.getMenu().add("Новая папка"); m.getMenu().add("Выбрать все"); }
        if (trashMode) m.getMenu().add("Очистить корзину");
        m.getMenu().add("Сортировка: " + sortName());
        m.getMenu().add((hidden ? "Скрыть" : "Показать") + " скрытые");
        if (dir != null) m.getMenu().add("Свойства папки");
        m.getMenu().add("Проверить обновления");
        m.getMenu().add("О программе");
        m.setOnMenuItemClickListener(mi -> {
            String t = String.valueOf(mi.getTitle());
            if (t.equals("Новая папка")) ask("Имя папки", "Новая папка", n -> { try { Fs.mkdir(dir, n); refresh(); } catch (Exception e) { toast(e.getMessage()); } });
            else if (t.equals("Выбрать все")) onMore("Выбрать все", new ArrayList<>());
            else if (t.equals("Очистить корзину")) confirm("Очистить корзину? Это навсегда.", () -> run("Очищаю", (p) -> { for (Fs.Entry e : items) Fs.purge(e.file); }));
            else if (t.startsWith("Сортировка")) askSort();
            else if (t.endsWith("скрытые")) { hidden = !hidden; prefs.edit().putBoolean("hidden", hidden).apply(); refresh(); }
            else if (t.equals("Свойства папки")) { List<File> l = new ArrayList<>(); l.add(dir); properties(l); }
            else if (t.equals("Проверить обновления")) checkUpdate(true);
            else if (t.equals("О программе")) new AlertDialog.Builder(this).setTitle("Проводник").setMessage("Файловый менеджер без рекламы, покупок и слежки. Ничего не отправляет никуда, кроме проверки обновлений на GitHub.\n\nИсходники: github.com/troublefack1-hue/-").setPositiveButton("Ок", null).show();
            return true;
        });
        m.show();
    }

    private String sortName() { return (sort == Fs.Sort.NAME ? "имя" : sort == Fs.Sort.DATE ? "дата" : sort == Fs.Sort.SIZE ? "размер" : "тип") + (desc ? " ↓" : " ↑"); }

    private void askSort() {
        String[] opts = {"Имя ↑", "Имя ↓", "Дата (новые первые)", "Дата (старые первые)", "Размер (большие первые)", "Размер (малые первые)", "Тип ↑", "Тип ↓"};
        new AlertDialog.Builder(this).setTitle("Сортировка").setItems(opts, (d, i) -> {
            sort = Fs.Sort.values()[i / 2]; desc = i % 2 == 1;
            prefs.edit().putString("sort", sort.name()).putBoolean("desc", desc).apply(); refresh();
        }).show();
    }

    private void askSearch() {
        File from = dir == null ? Environment.getExternalStorageDirectory() : dir;
        ask("Искать в «" + (dir == null ? "Память" : from.getName()) + "»", "", q -> {
            if (q.trim().isEmpty()) return;
            searchMode = true; selected.clear(); items = new ArrayList<>(); adapter.notifyDataSetChanged();
            title.setText("Поиск: " + q); subtitle.setText("ищу…"); crumbs.removeAllViews(); updateBars(); empty.setVisibility(View.GONE); fab.setVisibility(View.GONE);
            final List<Fs.Entry> found = new ArrayList<>();
            new Thread(() -> {
                Fs.search(from, q.trim(), hidden, e -> { synchronized (found) { found.add(e); } if (found.size() % 20 == 0) ui.post(() -> publish(found)); return searchMode && found.size() < 2000; });
                ui.post(() -> { publish(found); subtitle.setText(found.size() + " найдено в " + from.getAbsolutePath()); empty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE); emptyText.setText("Ничего не найдено"); });
            }).start();
        });
    }

    private void publish(List<Fs.Entry> found) { if (!searchMode) return; synchronized (found) { items = new ArrayList<>(found); } Fs.sort(items, sort, desc); adapter.notifyDataSetChanged(); }

    // -------------------------------------------------------------- update ---
    private void checkUpdate() { checkUpdate(false); }

    private void checkUpdate(boolean manual) {
        new Thread(() -> {
            try {
                String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                Updater.Info info = Updater.check(cur, "pcremote-files.apk");
                if (info == null) { if (manual) ui.post(() -> toast("Это последняя версия (" + cur + ")")); return; }
                File apk = Updater.download(info.url, getCacheDir(), info.sha256);
                ui.post(() -> {
                    toast("Обновление " + info.version + " — установите");
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.uriFor(apk), "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                });
            } catch (Exception e) { if (manual) ui.post(() -> toast("Не удалось проверить: " + e.getMessage())); }
        }).start();
    }

    // ------------------------------------------------------------- adapter ---
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return grid ? 1 : 0; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            Fs.Entry e = items.get(pos);
            Row r = cv == null ? new Row(grid) : (Row) cv.getTag();
            r.bind(e);
            return r.view;
        }
    }

    private final class Row {
        final LinearLayout view; final ImageView icon; final TextView name, meta, check; ProgressBar usage;
        Row(boolean asGrid) {
            view = new LinearLayout(MainActivity.this); view.setTag(this);
            view.setOrientation(asGrid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); view.setGravity(asGrid ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
            view.setPadding(dp(8), dp(asGrid ? 8 : 7), dp(8), dp(asGrid ? 8 : 7));
            view.setBackground(ripple(Color.TRANSPARENT));
            icon = new ImageView(MainActivity.this); icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int s = dp(asGrid ? 84 : 44);
            view.addView(icon, new LinearLayout.LayoutParams(s, s));
            LinearLayout texts = column(); texts.setPadding(dp(asGrid ? 0 : 12), dp(asGrid ? 6 : 0), 0, 0); texts.setGravity(asGrid ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            name = text("", asGrid ? 12 : 15, TEXT); name.setSingleLine(!asGrid); name.setMaxLines(asGrid ? 2 : 1); name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); if (asGrid) name.setGravity(Gravity.CENTER);
            meta = text("", 12, MUTED); meta.setSingleLine(true);
            texts.addView(name); if (!asGrid) texts.addView(meta);
            usage = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal); usage.setMax(100); usage.setVisibility(View.GONE);
            usage.setProgressTintList(ColorStateList.valueOf(ACCENT)); usage.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2A2F3D));
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)); up.setMargins(0, dp(8), 0, 0);
            if (!asGrid) texts.addView(usage, up); else usage.setVisibility(View.GONE);
            view.addView(texts, asGrid ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            check = text("✓", 13, Color.BLACK); check.setGravity(Gravity.CENTER); check.setTypeface(null, Typeface.BOLD);
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(ACCENT); check.setBackground(dot);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(22), dp(22)); cp.setMargins(dp(8), 0, dp(4), 0);
            if (!asGrid) view.addView(check, cp);
        }

        void bind(Fs.Entry e) {
            boolean sel = selected.contains(e.file.getAbsolutePath());
            boolean isRoot = e instanceof RootEntry;
            view.setBackground(ripple(sel ? PANEL2 : isRoot ? PANEL : Color.TRANSPARENT));
            if (isRoot) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0, dp(3), 0, dp(3)); view.setLayoutParams(lp); view.setPadding(dp(12), dp(14), dp(12), dp(14)); }
            else { view.setPadding(dp(8), dp(grid ? 8 : 7), dp(8), dp(grid ? 8 : 7)); if (view.getLayoutParams() instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) view.getLayoutParams()).setMargins(0, 0, 0, 0); }
            check.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            if (sel) { check.setScaleX(0.6f); check.setScaleY(0.6f); check.animate().scaleX(1f).scaleY(1f).setDuration(140).start(); }
            icon.setAlpha(sel ? 0.75f : 1f);
            name.setText(e.name);
            if (isRoot) {
                Root r = ((RootEntry) e).r; name.setText(r.name); icon.setTag(null); icon.setImageResource(r.icon); icon.setColorFilter(r.icon == R.drawable.ic_sd ? ACCENT : 0);
                if (r.used >= 0) { meta.setText(r.hint); usage.setVisibility(View.VISIBLE); usage.setProgress(Math.round(r.used * 100)); }
                else { meta.setText(r.hint); usage.setVisibility(View.GONE); }
                return;
            }
            icon.setColorFilter(0); usage.setVisibility(View.GONE);
            if (searchMode) meta.setText(e.file.getParent());
            else if (trashMode) { String o = Fs.trashOrigin(e.file); meta.setText(o == null ? "" : "из " + new File(o).getParent()); }
            else meta.setText(e.dir ? Fs.count(e.file) + " " + plural(Fs.count(e.file), "объект", "объекта", "объектов") : Fs.size(e.size) + " · " + Fs.date(e.mtime));
            thumbs.load(icon, e.file, e.kind, iconFor(e.kind));
        }
    }

    static int iconFor(Fs.Kind k) {
        switch (k) {
            case FOLDER: return R.drawable.ic_folder; case IMAGE: return R.drawable.ic_image; case VIDEO: return R.drawable.ic_video; case AUDIO: return R.drawable.ic_audio;
            case APK: return R.drawable.ic_apk; case ARCHIVE: return R.drawable.ic_zip; case DOC: case TEXT: return R.drawable.ic_doc; default: return R.drawable.ic_file;
        }
    }

    // ------------------------------------------------------------- helpers ---
    static String plural(int n, String one, String few, String many) { int m = n % 100, u = n % 10; return (m >= 11 && m <= 14) ? many : u == 1 ? one : (u >= 2 && u <= 4) ? few : many; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setTextColor(Color.BLACK); b.setBackgroundColor(ACCENT); b.setAllCaps(false); b.setTypeface(null, Typeface.BOLD); return b; }
    private ImageButton icon(int res, View.OnClickListener l) {
        ImageButton b = new ImageButton(this); b.setImageResource(res); b.setColorFilter(TEXT); b.setBackground(ripple(Color.TRANSPARENT));
        b.setPadding(dp(10), dp(10), dp(10), dp(10)); b.setOnClickListener(l); return b;
    }
    private Button iconButton(String s) { Button b = new Button(this); b.setText(s); b.setTextSize(20); b.setTextColor(TEXT); b.setBackgroundColor(Color.TRANSPARENT); b.setMinWidth(dp(44)); b.setMinimumWidth(dp(44)); b.setPadding(dp(8), 0, dp(8), 0); return b; }
    private void confirm(String q, Runnable yes) { new AlertDialog.Builder(this).setMessage(q).setPositiveButton("Да", (d, w) -> yes.run()).setNegativeButton("Нет", null).show(); }
    interface Answer { void on(String s); }
    private void ask(String titleText, String value, Answer a) {
        EditText e = new EditText(this); e.setText(value); e.setInputType(InputType.TYPE_CLASS_TEXT); e.setSingleLine(true); e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(titleText).setView(e).setPositiveButton("Ок", (d, w) -> a.on(e.getText().toString().trim())).setNegativeButton("Отмена", null).show();
    }
}
