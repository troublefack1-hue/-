package ru.pcremote.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.InputType;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.TranslateAnimation;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.pcremote.Pairing;
import ru.pcremote.Paths;
import ru.pcremote.Updater;

/**
 * «Проводник»: a file manager that does one thing well and asks nothing in return. No ads, no
 * purchases, no analytics, no network except our own update check and (if you pair it) your own PC.
 *
 * One or two panes. A pane shows a place (Loc): a local folder, the inside of a zip, a folder on
 * the PC. Drag selected items onto the other pane (or onto a folder in it) to copy or move.
 */
public class MainActivity extends Activity {
    static final int BG = 0xFF0F1117, PANEL = 0xFF181B24, PANEL2 = 0xFF222633, TEXT = 0xFFEEF0F5, MUTED = 0xFF8E94A6, ACCENT = 0xFFE0A030, BAD = 0xFFEF5350, OK = 0xFF38D070, BORDER = 0xFF2A2F3D;
    private static final int REQ_PERM = 1, REQ_VIEW = 2, REQ_EDIT = 3;

    private SharedPreferences prefs;
    private Thumbs thumbs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService sizer = Executors.newSingleThreadExecutor();
    private final Map<String, Long> folderSizes = new HashMap<>();   // path:mtime -> bytes

    private boolean grid, hidden, desc, twoPanes, showSizes;
    private Fs.Sort sort = Fs.Sort.NAME;
    private PcClient pc;

    // clipboard
    private final ArrayList<Fs.Entry> clip = new ArrayList<>();
    private Loc clipFrom; private boolean clipCut;

    // views
    private LinearLayout root, actionBar, pasteBar, panesBox, crumbs;
    private TextView title, subtitle, pasteText;
    private ImageButton viewBtn, panesBtn;
    private View fab;
    private final Pane[] panes = new Pane[2];
    private Pane active;

    // ==================================================================== lifecycle ===
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("files", MODE_PRIVATE);
        grid = prefs.getBoolean("grid", false); hidden = prefs.getBoolean("hidden", false); desc = prefs.getBoolean("desc", false);
        twoPanes = prefs.getBoolean("panes", false); showSizes = prefs.getBoolean("sizes", true);
        try { sort = Fs.Sort.valueOf(prefs.getString("sort", "NAME")); } catch (Exception ignored) {}
        thumbs = new Thumbs(this, dp(96));
        Ops.trashRoot = Environment.getExternalStorageDirectory();
        ZipLoc.cacheDir = new File(getCacheDir(), "zip"); PcLoc.cacheDir = new File(getCacheDir(), "pc");
        Paths.pcAddrs = prefs.getString("addrs", "");
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        buildUi();
        if (!hasStorage()) { showPermission(); return; }
        File start = startFolder(getIntent());
        if (start != null) active.open(new LocalLoc(start)); else active.showRoots();
        if (twoPanes) panes[1].open(new LocalLoc(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)));
        checkUpdate(false);
    }

    private File startFolder(Intent i) {
        if (i != null && Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null && "file".equals(i.getData().getScheme())) {
            File f = new File(i.getData().getPath()); return f.isDirectory() ? f : f.getParentFile();
        }
        String last = prefs.getString("last", "");
        return !last.isEmpty() && new File(last).isDirectory() ? new File(last) : null;
    }

    @Override protected void onResume() { super.onResume(); if (hasStorage() && active != null && active.loc != null && !active.searchMode) active.refresh(false); }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); layoutPanes(); }

    // ================================================================== permission ===
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
        if (req == REQ_PERM) { if (hasStorage()) { setContentView(root); active.showRoots(); } else showPermission(); }
        if ((req == REQ_VIEW || req == REQ_EDIT) && code == RESULT_OK) for (Pane p : panes) if (p.loc != null) p.refresh(false);
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_PERM) { if (hasStorage()) { setContentView(root); active.showRoots(); } else showPermission(); }
    }

    // ========================================================================= ui ===
    private void buildUi() {
        root = column(); root.setBackgroundColor(BG);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(PANEL); bar.setPadding(dp(4), dp(6), dp(4), dp(6));
        bar.addView(icon(R.drawable.ic_back, v -> onBackPressed()));
        LinearLayout titles = column(); titles.setPadding(dp(6), 0, dp(6), 0);
        title = text("Проводник", 18, TEXT); title.setTypeface(null, Typeface.BOLD); title.setSingleLine(true);
        subtitle = text("", 12, MUTED); subtitle.setSingleLine(true); subtitle.setEllipsize(android.text.TextUtils.TruncateAt.START);
        titles.addView(title); titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(icon(R.drawable.ic_search, v -> askSearch()));
        panesBtn = icon(R.drawable.ic_copy, v -> { twoPanes = !twoPanes; prefs.edit().putBoolean("panes", twoPanes).apply(); layoutPanes(); if (twoPanes && panes[1].loc == null && !panes[1].rootsMode) panes[1].showRoots(); });
        panesBtn.setImageAlpha(twoPanes ? 255 : 140); bar.addView(panesBtn);
        viewBtn = icon(grid ? R.drawable.ic_list : R.drawable.ic_grid, v -> { grid = !grid; prefs.edit().putBoolean("grid", grid).apply(); viewBtn.setImageResource(grid ? R.drawable.ic_list : R.drawable.ic_grid); for (Pane p : panes) { p.rebuildList(); p.animateList(); } });
        bar.addView(viewBtn);
        bar.addView(icon(R.drawable.ic_more, this::menu));
        root.addView(bar);
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.setBackgroundColor(PANEL);
        crumbs = new LinearLayout(this); crumbs.setOrientation(LinearLayout.HORIZONTAL); crumbs.setPadding(dp(8), 0, dp(8), dp(6));
        hs.addView(crumbs); root.addView(hs);

        FrameLayout stage = new FrameLayout(this); root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        panesBox = new LinearLayout(this); stage.addView(panesBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < 2; i++) panes[i] = new Pane(i);
        active = panes[0];
        layoutPanes();
        // floating "+"
        ImageButton plus = new ImageButton(this); plus.setImageResource(R.drawable.ic_add); plus.setColorFilter(Color.BLACK);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(ACCENT);
        plus.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), circle, null)); plus.setElevation(dp(6));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END); fp.setMargins(0, 0, dp(18), dp(18));
        plus.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v); m.getMenu().add("Новая папка"); if (active.loc instanceof LocalLoc) m.getMenu().add("Новый текстовый файл");
            m.setOnMenuItemClickListener(mi -> { if (String.valueOf(mi.getTitle()).startsWith("Новая папка")) ask("Имя папки", "Новая папка", n -> active.run("Создаю", p -> Ops.mkdir(active.loc, n)));
                else ask("Имя файла", "Заметка.txt", n -> { try { File f = new File(((LocalLoc) active.loc).dir, n); if (n.isEmpty() || n.contains("/") || f.exists()) throw new IOException("недопустимое имя или файл уже есть"); if (!f.createNewFile()) throw new IOException("не удалось создать"); active.refresh(false); edit(f); } catch (Exception e) { toast(e.getMessage()); } }); return true; });
            m.show(); });
        fab = plus; stage.addView(plus, fp); plus.setVisibility(View.GONE);

        pasteBar = new LinearLayout(this); pasteBar.setOrientation(LinearLayout.HORIZONTAL); pasteBar.setGravity(Gravity.CENTER_VERTICAL);
        pasteBar.setBackgroundColor(PANEL2); pasteBar.setPadding(dp(14), dp(8), dp(8), dp(8)); pasteBar.setVisibility(View.GONE);
        pasteText = text("", 14, TEXT); pasteBar.addView(pasteText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button paste = button("Вставить сюда"); paste.setOnClickListener(v -> paste()); pasteBar.addView(paste);
        pasteBar.addView(icon(R.drawable.ic_close, v -> { clip.clear(); updateBars(); }));
        root.addView(pasteBar);

        actionBar = new LinearLayout(this); actionBar.setOrientation(LinearLayout.HORIZONTAL); actionBar.setBackgroundColor(PANEL2); actionBar.setVisibility(View.GONE);
        int[] icons = {R.drawable.ic_copy, R.drawable.ic_cut, R.drawable.ic_delete, R.drawable.ic_share, R.drawable.ic_dots};
        String[] labels = {"Копировать", "Вырезать", "Удалить", "Поделиться", "Ещё"};
        for (int k = 0; k < icons.length; k++) {
            LinearLayout cell = column(); cell.setGravity(Gravity.CENTER); cell.setPadding(0, dp(8), 0, dp(8)); cell.setBackground(ripple(Color.TRANSPARENT));
            ImageView ic = new ImageView(this); ic.setImageResource(icons[k]); ic.setColorFilter(TEXT); cell.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView lb = text(labels[k], 11, MUTED); lb.setPadding(0, dp(2), 0, 0); cell.addView(lb);
            final String action = labels[k]; cell.setOnClickListener(v -> onAction(action, v));
            actionBar.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        root.addView(actionBar);
        setContentView(root);
    }

    private void layoutPanes() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        panesBox.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        panesBox.removeAllViews();
        for (int i = 0; i < (twoPanes ? 2 : 1); i++) {
            LinearLayout.LayoutParams lp = landscape ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            if (i == 1) { if (landscape) lp.setMargins(dp(2), 0, 0, 0); else lp.setMargins(0, dp(2), 0, 0); }
            if (panes[i].view.getParent() != null) ((ViewGroup) panes[i].view.getParent()).removeView(panes[i].view);
            panesBox.addView(panes[i].view, lp);
        }
        if (!twoPanes && active == panes[1]) setActive(panes[0]);
        if (panesBtn != null) panesBtn.setImageAlpha(twoPanes ? 255 : 140);
        for (Pane p : panes) p.paintActive();
    }

    private void setActive(Pane p) { active = p; for (Pane x : panes) x.paintActive(); active.syncHeader(); updateBars(); }

    // ======================================================================= pane ===
    final class Pane {
        final int index;
        final LinearLayout view, body;
        AbsListView listView;
        final Adapter adapter = new Adapter(this);
        final View empty; final TextView emptyText;
        Loc loc; boolean rootsMode = true, searchMode;
        final ArrayList<Loc> history = new ArrayList<>();
        List<Fs.Entry> items = new ArrayList<>();
        final Set<String> selected = new LinkedHashSet<>();

        Pane(int index) {
            this.index = index;
            view = column(); view.setBackgroundColor(BG);
            body = column(); view.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            rebuildList();
            LinearLayout em = column(); em.setGravity(Gravity.CENTER); em.setVisibility(View.GONE);
            ImageView ei = new ImageView(MainActivity.this); ei.setImageResource(R.drawable.ic_empty); ei.setColorFilter(0x553A3F4D); em.addView(ei, new LinearLayout.LayoutParams(dp(96), dp(96)));
            emptyText = text("Пусто", 15, MUTED); emptyText.setGravity(Gravity.CENTER); emptyText.setPadding(dp(32), dp(8), dp(32), 0); em.addView(emptyText);
            empty = em;
            FrameLayout fl = (FrameLayout) listView.getParent();
            fl.addView(em, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void rebuildList() {
            body.removeAllViews();
            FrameLayout fl = new FrameLayout(MainActivity.this);
            if (grid) { GridView g = new GridView(MainActivity.this); g.setNumColumns(GridView.AUTO_FIT); g.setColumnWidth(dp(110)); g.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); g.setVerticalSpacing(dp(6)); listView = g; }
            else { ListView l = new ListView(MainActivity.this); l.setDivider(null); listView = l; }
            listView.setBackgroundColor(BG); listView.setAdapter(adapter); listView.setPadding(dp(6), dp(4), dp(6), dp(80)); listView.setClipToPadding(false);
            listView.setSelector(android.R.color.transparent);
            listView.setOnItemClickListener((p, v, pos, id) -> { setActive(this); onTap(items.get(pos)); });
            listView.setOnItemLongClickListener((p, v, pos, id) -> { setActive(this); Fs.Entry e = items.get(pos); if (selected.contains(e.file.getAbsolutePath()) && !(e instanceof RootEntry)) startDrag(v); else toggle(e); return true; });
            final android.view.GestureDetector swipe = new android.view.GestureDetector(MainActivity.this, new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                    if (e1 == null || e2 == null) return false;
                    float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                    // a quick swipe to the left: back to the previous place (the same as the Back key)
                    if (dx < -dp(90) && Math.abs(dy) < Math.abs(dx) * 0.6f && Math.abs(vx) > 600) { setActive(Pane.this); if (back()) { listView.animate().translationX(-dp(24)).alpha(0.6f).setDuration(80).withEndAction(() -> { listView.setTranslationX(dp(24)); listView.animate().translationX(0).alpha(1f).setDuration(160).start(); }).start(); } return true; }
                    return false;
                }
            });
            listView.setOnTouchListener((v, ev) -> { if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN && active != this) setActive(this); swipe.onTouchEvent(ev); return false; });
            listView.setOnDragListener(this::onDrag);
            fl.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (empty != null) { if (empty.getParent() != null) ((ViewGroup) empty.getParent()).removeView(empty); fl.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); }
            body.addView(fl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void paintActive() {
            GradientDrawable d = new GradientDrawable(); d.setColor(BG);
            if (twoPanes) d.setStroke(dp(1), active == this ? ACCENT : BORDER);
            view.setBackground(d);
        }

        void animateList() {
            AnimationSet set = new AnimationSet(true);
            AlphaAnimation a = new AlphaAnimation(0f, 1f); a.setDuration(180);
            TranslateAnimation t = new TranslateAnimation(0, 0, dp(18), 0); t.setDuration(220); t.setInterpolator(new DecelerateInterpolator());
            set.addAnimation(a); set.addAnimation(t);
            listView.setLayoutAnimation(new LayoutAnimationController(set, grid ? 0.03f : 0.05f)); listView.startLayoutAnimation();
        }

        // ---- navigation ----
        void showRoots() {
            loc = null; rootsMode = true; searchMode = false; selected.clear(); history.clear();
            items = new ArrayList<>();
            for (Root r : roots()) items.add(new RootEntry(r));
            adapter.notifyDataSetChanged(); animateList(); empty.setVisibility(View.GONE);
            if (active == this) { syncHeader(); updateBars(); }
        }

        void open(Loc l) { open(l, true); }
        void open(Loc l, boolean remember) {
            if (loc != null && remember && !searchMode) history.add(loc);
            loc = l; rootsMode = false; searchMode = false; selected.clear();
            if (l instanceof LocalLoc) prefs.edit().putString("last", ((LocalLoc) l).dir.getAbsolutePath()).apply();
            refresh(true);
        }

        void refresh(boolean animate) {
            if (loc == null) { showRoots(); return; }
            final Loc l = loc;
            if (l instanceof LocalLoc) { apply(l, ((LocalLoc) l).list(hidden, sort, desc), null, animate); return; }
            if (active == this) subtitle.setText("загружаю…");
            new Thread(() -> {
                List<Fs.Entry> got = null; String err = null;
                try { got = l.list(hidden, sort, desc); } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final List<Fs.Entry> fg = got; final String fe = err;
                ui.post(() -> { if (loc == l) apply(l, fg, fe, animate); });
            }).start();
        }

        private void apply(Loc l, List<Fs.Entry> got, String err, boolean animate) {
            if (err != null) { toast(err); got = new ArrayList<>(); }
            boolean trash = isTrash(l);
            if (trash) { List<Fs.Entry> t = new ArrayList<>(); for (Fs.Entry e : got) if (!e.name.endsWith(".origin")) t.add(e); got = t; }
            items = got; adapter.notifyDataSetChanged(); if (animate) animateList(); listView.setSelection(0);
            empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            emptyText.setText(err != null ? err : trash ? "Корзина пуста" : hidden ? "Папка пуста" : "Папка пуста\n(скрытые файлы выключены в меню)");
            if (active == this) { syncHeader(); updateBars(); }
        }

        boolean isTrash(Loc l) { return l instanceof LocalLoc && ((LocalLoc) l).dir.getAbsolutePath().equals(Fs.trashDir(Environment.getExternalStorageDirectory()).getAbsolutePath()); }
        boolean trashMode() { return loc != null && isTrash(loc); }

        void syncHeader() {
            if (rootsMode || loc == null) { title.setText("Проводник"); subtitle.setText("без рекламы, без слежки, ваши файлы — ваши"); crumbs.removeAllViews(); crumbs.addView(text("Создано Николаем Коноваловым для вас, с любовью.", 12, MUTED)); return; }
            if (searchMode) return;
            title.setText(trashMode() ? "Корзина" : loc.title());
            subtitle.setText(items.size() + " " + plural(items.size(), "объект", "объекта", "объектов") + " · " + loc.path());
            buildCrumbs();
        }

        void buildCrumbs() {
            crumbs.removeAllViews();
            List<Loc> chain = new ArrayList<>();
            File ext = Environment.getExternalStorageDirectory();
            for (Loc l = loc; l != null; l = l.parent()) { chain.add(0, l); if (l instanceof LocalLoc && ((LocalLoc) l).dir.equals(ext)) break; if (chain.size() > 24) break; }
            crumbs.addView(crumb("Начало", null));
            for (Loc l : chain) {
                String n = l instanceof LocalLoc && ((LocalLoc) l).dir.equals(ext) ? "Память" : l.title();
                crumbs.addView(text(" › ", 14, MUTED)); crumbs.addView(crumb(n, l));
            }
        }

        TextView crumb(String label, Loc target) {
            TextView t = text(label, 14, target != null && target.id().equals(loc.id()) ? TEXT : ACCENT); t.setPadding(dp(4), dp(4), dp(4), dp(4));
            t.setOnClickListener(v -> { if (target == null) showRoots(); else if (!target.id().equals(loc.id())) open(target); });
            return t;
        }

        boolean back() {
            if (!selected.isEmpty()) { selected.clear(); adapter.notifyDataSetChanged(); updateBars(); return true; }
            if (searchMode) { searchMode = false; refresh(true); return true; }
            if (loc == null) return false;
            if (!history.isEmpty()) { Loc prev = history.remove(history.size() - 1); loc = prev; selected.clear(); refresh(true); return true; }
            Loc p = loc.parent();
            if (p == null || trashMode() || (loc instanceof LocalLoc && ((LocalLoc) loc).dir.equals(Environment.getExternalStorageDirectory()))) { showRoots(); return true; }
            loc = p; refresh(true); return true;
        }

        // ---- taps ----
        void onTap(Fs.Entry e) {
            if (!selected.isEmpty()) { toggle(e); return; }
            if (e instanceof RootEntry) { ((RootEntry) e).r.open.run(); return; }
            if (e.dir || (loc instanceof LocalLoc && !e.dir && Fs.ext(e.name).equals("zip"))) { open(loc.child(e)); return; }
            if (e.local()) { remember(e.file); openLocal(e.file); return; }
            run("Открываю", p -> { File f = loc.materialize(e, p); ui.post(() -> openLocal(f)); });
        }

        void toggle(Fs.Entry e) {
            if (e instanceof RootEntry) return;
            String k = e.file.getAbsolutePath();
            if (!selected.remove(k)) selected.add(k);
            adapter.notifyDataSetChanged(); updateBars();
        }

        List<Fs.Entry> selectedEntries() { List<Fs.Entry> l = new ArrayList<>(); for (Fs.Entry e : items) if (selected.contains(e.file.getAbsolutePath())) l.add(e); return l; }

        // ---- drag & drop between panes ----
        void startDrag(View v) {
            List<Fs.Entry> sel = selectedEntries(); if (sel.isEmpty()) return;
            ClipData data = ClipData.newPlainText("files", String.valueOf(index));
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
            if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(data, shadow, this, 0); else v.startDrag(data, shadow, this, 0);
            toast("Перетащите на другую панель или папку");
        }

        boolean onDrag(View v, DragEvent ev) {
            switch (ev.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: return ev.getLocalState() instanceof Pane;
                case DragEvent.ACTION_DRAG_ENTERED: view.setAlpha(0.85f); return true;
                case DragEvent.ACTION_DRAG_EXITED: case DragEvent.ACTION_DRAG_ENDED: view.setAlpha(1f); return true;
                case DragEvent.ACTION_DROP: {
                    view.setAlpha(1f);
                    Pane from = (Pane) ev.getLocalState();
                    Loc target = loc;
                    int pos = listView.pointToPosition((int) ev.getX(), (int) ev.getY());
                    if (pos >= 0 && pos < items.size()) { Fs.Entry over = items.get(pos); if (over.dir && !(over instanceof RootEntry)) target = loc.child(over); else if (over instanceof RootEntry && ((RootEntry) over).r.file != null) target = new LocalLoc(((RootEntry) over).r.file); }
                    if (target == null) { toast("Сюда нельзя: откройте папку"); return true; }
                    final Loc dst = target; final List<Fs.Entry> sel = from.selectedEntries(); final Loc src = from.loc;
                    if (src != null && src.id().equals(dst.id())) { toast("Это та же папка"); return true; }
                    new AlertDialog.Builder(MainActivity.this).setTitle(sel.size() + " " + plural(sel.size(), "объект", "объекта", "объектов") + " → " + dst.title())
                            .setPositiveButton("Копировать", (d, w) -> transfer(src, sel, dst, false, from))
                            .setNegativeButton("Переместить", (d, w) -> transfer(src, sel, dst, true, from)).setNeutralButton("Отмена", null).show();
                    return true;
                }
                default: return true;
            }
        }

        // ---- operations with a progress dialog ----
        void run(String what, Op op) {
            LinearLayout box = column(); int p = dp(20); box.setPadding(p, p, p, p);
            TextView cur = text("", 13, MUTED); box.addView(cur);
            ProgressBar pb = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(1000); pb.setProgressTintList(ColorStateList.valueOf(ACCENT)); box.addView(pb);
            final boolean[] cancel = {false};
            AlertDialog d = new AlertDialog.Builder(MainActivity.this).setTitle(what + "…").setView(box).setCancelable(false).setNegativeButton("Отмена", (dlg, w) -> cancel[0] = true).show();
            new Thread(() -> {
                String err = null;
                try { op.run((name, done, total) -> { ui.post(() -> { cur.setText(name); pb.setProgress(total > 0 ? (int) Math.min(1000, done * 1000 / total) : 0); }); return !cancel[0]; }); }
                catch (Fs.Cancelled c) { err = "Отменено"; }
                catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final String fe = err;
                ui.post(() -> { try { d.dismiss(); } catch (Exception ignored) {} for (Pane x : panes) { x.selected.clear(); if (x.loc != null && !x.searchMode) x.refresh(false); } if (fe != null) toast(fe); updateBars(); });
            }).start();
        }
    }

    private void transfer(Loc src, List<Fs.Entry> sel, Loc dst, boolean move, Pane from) {
        for (Fs.Entry e : sel) if (e.dir && e.local() && dst instanceof LocalLoc && ((LocalLoc) dst).dir.getAbsolutePath().startsWith(e.file.getAbsolutePath() + File.separator)) { toast("Нельзя вставить папку саму в себя"); return; }
        from.run(move ? "Перемещаю" : "Копирую", p -> Ops.copy(src, sel, dst, move, p));
    }

    // ====================================================================== roots ===
    static final class Root { final String name, hint; final File file; final int icon; float used = -1; Runnable open; Root(String n, String h, File f, int i) { name = n; hint = h; file = f; icon = i; } }
    static final class RootEntry extends Fs.Entry { final Root r; RootEntry(Root r) { super(r.file != null ? r.file : new File("/" + r.name)); this.r = r; } }

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
        Root pcRoot = new Root("ПК", prefs.contains("secret") ? "папки компьютера через PC Remote · " + prefs.getString("hostport", "") : "подключить компьютер с PC Remote", null, R.drawable.ic_sd);
        pcRoot.open = this::openPc; r.add(pcRoot);
        r.add(new Root("Корзина", "удалённое можно вернуть", Fs.trashDir(ext), R.drawable.ic_trash));
        String ver = "?"; try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        Root upd = new Root("Обновить приложение", "версия " + ver + " · нажмите: проверит и установит новую", null, R.drawable.ic_rotate); upd.open = () -> checkUpdate(true); r.add(upd);
        for (String p : favorites()) { File f = new File(p); Root fav = new Root(f.getName(), "избранное · " + f.getParent(), f, R.drawable.ic_folder); r.add(fav); }
        for (String p : recents()) { File f = new File(p); if (!f.exists()) continue; Root rc = new Root(f.getName(), "недавнее · " + Fs.date(f.lastModified()), f, iconFor(Fs.kindOf(f.getName()))); rc.open = () -> { remember(f); openLocal(f); }; r.add(rc); }
        for (Root x : r) if (x.open == null) { final File f = x.file; x.open = () -> { if (!f.exists()) f.mkdirs(); active.open(new LocalLoc(f)); }; }
        return r;
    }

    private List<String> favorites() { return new ArrayList<>(prefs.getStringSet("fav", new LinkedHashSet<>())); }
    private List<String> recents() { String s = prefs.getString("recent", ""); return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n"))); }
    private void remember(File f) {
        List<String> r = recents(); r.remove(f.getAbsolutePath()); r.add(0, f.getAbsolutePath()); while (r.size() > 12) r.remove(r.size() - 1);
        prefs.edit().putString("recent", String.join("\n", r)).apply();
    }
    private void toggleFavorite(File f) {
        Set<String> s = new LinkedHashSet<>(prefs.getStringSet("fav", new LinkedHashSet<>()));
        if (!s.remove(f.getAbsolutePath())) { s.add(f.getAbsolutePath()); toast("В избранном: " + f.getName()); } else toast("Убрано из избранного");
        prefs.edit().putStringSet("fav", s).apply();
        for (Pane p : panes) if (p.rootsMode) p.showRoots();
    }

    private float usedFraction(File f) { try { StatFs s = new StatFs(f.getAbsolutePath()); return 1f - (float) s.getAvailableBytes() / Math.max(1, s.getTotalBytes()); } catch (Exception e) { return -1; } }
    private String usage(File f) { try { StatFs s = new StatFs(f.getAbsolutePath()); return "свободно " + Fs.size(s.getAvailableBytes()) + " из " + Fs.size(s.getTotalBytes()); } catch (Exception e) { return ""; } }

    // ---- the PC root: pair once with the code from the PC Remote window ----
    private void openPc() {
        if (!prefs.contains("secret")) { pairPc(); return; }
        if (pc == null) pc = new PcClient(prefs.getString("host", ""), prefs.getInt("port", 8443), prefs.getString("pin", ""), prefs.getString("secret", ""), prefs.getString("lan", ""), onWifi());
        active.open(new PcLoc(pc, ""));
    }

    private void pairPc() {
        LinearLayout box = column(); int p = dp(20); box.setPadding(p, p, p, 0);
        box.addView(text("Адрес и код из окна PC Remote на компьютере (те же, что для «Мой ПК»). Все диски ПК станут папками здесь.", 13, MUTED));
        EditText host = new EditText(this); host.setHint("IP:порт, например 93.100.1.2:8443"); host.setText(prefs.getString("hostport", "")); host.setSingleLine(true); box.addView(host);
        EditText code = new EditText(this); code.setHint("код подключения"); code.setSingleLine(true); code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); box.addView(code);
        new AlertDialog.Builder(this).setTitle("Подключить ПК").setView(box).setPositiveButton("Привязать", (d, w) -> {
            String hp = host.getText().toString().trim().replaceAll("^[a-zA-Z]+://", "").replaceAll("/.*$", ""), c = code.getText().toString().toUpperCase().replaceAll("[^0-9A-Z]", "");
            String[] parts = hp.split(":"); if (hp.isEmpty() || !(c.length() == 6 || c.length() == 8)) { toast("Нужен адрес и код"); return; }
            final String h = parts[0]; final int port; try { port = parts.length == 2 ? Integer.parseInt(parts[1]) : 8443; } catch (NumberFormatException e) { toast("Порт должен быть числом"); return; }
            active.run("Привязываю", pr -> {
                Pairing.Result r = Pairing.pair(h, port, c, "", "", onWifi());
                prefs.edit().putString("hostport", h + ":" + port).putString("host", h).putInt("port", port).putString("secret", r.secret).putString("pin", r.fingerprint).putString("lan", r.lan).apply();
                pc = null; ui.post(() -> { for (Pane x : panes) if (x.rootsMode) x.showRoots(); openPc(); });
            });
        }).setNegativeButton("Отмена", null).show();
    }

    private boolean onWifi() {
        try { android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE); android.net.NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork()); return c != null && (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)); } catch (Exception e) { return false; }
    }

    // ================================================================== open files ===
    private void openLocal(File f) {
        Fs.Kind k = Fs.kindOf(f.getName());
        if (k == Fs.Kind.IMAGE) {
            List<File> imgs = new ArrayList<>(); int at = 0;
            for (Fs.Entry x : active.items) if (x.kind == Fs.Kind.IMAGE && x.local()) { if (x.file.equals(f)) at = imgs.size(); imgs.add(x.file); }
            if (imgs.isEmpty() || imgs.size() > 3000) { imgs = new ArrayList<>(); imgs.add(f); at = 0; }
            String[] a = new String[imgs.size()]; for (int i = 0; i < a.length; i++) a[i] = imgs.get(i).getAbsolutePath();
            startActivityForResult(new Intent(this, ViewerActivity.class).putExtra(ViewerActivity.EXTRA_FILES, a).putExtra(ViewerActivity.EXTRA_INDEX, at), REQ_VIEW);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); return;
        }
        if (k == Fs.Kind.TEXT && f.length() <= 2L * 1024 * 1024) { edit(f); return; }
        openWith(f, Fs.mime(f.getName()));
    }

    private void edit(File f) { startActivityForResult(new Intent(this, EditorActivity.class).putExtra(EditorActivity.EXTRA_PATH, f.getAbsolutePath()), REQ_EDIT); }

    private void openWith(File f, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.uriFor(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); }
        catch (Exception e) { try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.uriFor(f), "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть с помощью")); } catch (Exception e2) { toast("Нечем открыть этот файл"); } }
    }

    // =================================================================== actions ===
    private void updateBars() {
        boolean showActions = !active.selected.isEmpty();
        if (showActions && actionBar.getVisibility() != View.VISIBLE) { actionBar.setVisibility(View.VISIBLE); actionBar.setTranslationY(dp(60)); actionBar.setAlpha(0f); actionBar.animate().translationY(0).alpha(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start(); }
        else if (!showActions && actionBar.getVisibility() == View.VISIBLE) { actionBar.animate().translationY(dp(60)).alpha(0f).setDuration(150).withEndAction(() -> actionBar.setVisibility(View.GONE)).start(); }
        boolean canPaste = !clip.isEmpty() && active.loc != null && active.loc.writable() && !active.trashMode() && !showActions;
        pasteBar.setVisibility(canPaste ? View.VISIBLE : View.GONE);
        if (canPaste) pasteText.setText((clipCut ? "Переместить " : "Скопировать ") + clip.size() + " " + plural(clip.size(), "объект", "объекта", "объектов") + " в «" + active.loc.title() + "»");
        boolean fabOn = !showActions && active.loc != null && active.loc.writable() && !active.trashMode() && !active.searchMode;
        if (fab.getVisibility() != View.VISIBLE && fabOn) { fab.setVisibility(View.VISIBLE); fab.setScaleX(0f); fab.setScaleY(0f); }
        fab.animate().scaleX(fabOn ? 1f : 0f).scaleY(fabOn ? 1f : 0f).setDuration(150).withEndAction(() -> { if (!fabOn) fab.setVisibility(View.GONE); }).start();
        if (showActions) title.setText("Выбрано: " + active.selected.size()); else if (!active.rootsMode && !active.searchMode && active.loc != null) title.setText(active.trashMode() ? "Корзина" : active.loc.title());
    }

    private void onAction(String a, View anchor) {
        final Pane pane = active; final List<Fs.Entry> sel = pane.selectedEntries(); final Loc loc = pane.loc;
        if (sel.isEmpty() || loc == null) return;
        switch (a) {
            case "Копировать": clip.clear(); clip.addAll(sel); clipFrom = loc; clipCut = false; pane.selected.clear(); pane.adapter.notifyDataSetChanged(); updateBars(); toast("Скопировано: откройте папку и нажмите «Вставить сюда», или перетащите на другую панель"); break;
            case "Вырезать": if (!loc.writable()) { toast("Отсюда нельзя вырезать, только копировать"); return; } clip.clear(); clip.addAll(sel); clipFrom = loc; clipCut = true; pane.selected.clear(); pane.adapter.notifyDataSetChanged(); updateBars(); toast("Вырезано: откройте папку и нажмите «Вставить сюда»"); break;
            case "Удалить":
                if (pane.trashMode()) confirm("Удалить навсегда " + sel.size() + " " + plural(sel.size(), "объект", "объекта", "объектов") + "?", () -> pane.run("Удаляю", p -> { for (Fs.Entry e : sel) Fs.purge(e.file); }));
                else if (loc instanceof PcLoc) confirm("Удалить на ПК " + sel.size() + " " + plural(sel.size(), "объект", "объекта", "объектов") + "? (попадёт в корзину Windows)", () -> pane.run("Удаляю на ПК", p -> Ops.delete(loc, sel, p)));
                else pane.run("Удаляю в корзину", p -> Ops.delete(loc, sel, p));
                break;
            case "Поделиться": share(pane, sel); break;
            case "Ещё": {
                PopupMenu m = new PopupMenu(this, anchor);
                if (pane.trashMode()) m.getMenu().add("Восстановить");
                else {
                    if (sel.size() == 1 && loc.writable()) m.getMenu().add("Переименовать");
                    if (sel.size() > 1 && loc instanceof LocalLoc) m.getMenu().add("Переименовать по шаблону…");
                    if (loc instanceof LocalLoc) m.getMenu().add("Упаковать в zip");
                    if (sel.size() == 1 && !sel.get(0).dir && Fs.ext(sel.get(0).name).equals("zip") && loc instanceof LocalLoc) m.getMenu().add("Распаковать");
                    if (loc instanceof ZipLoc) m.getMenu().add("Распаковать выбранное…");
                    if (sel.size() == 1 && sel.get(0).dir && loc instanceof LocalLoc) m.getMenu().add(favorites().contains(sel.get(0).file.getAbsolutePath()) ? "Убрать из избранного" : "В избранное");
                    if (sel.size() == 1 && !sel.get(0).dir) m.getMenu().add("Открыть с помощью…");
                    if (sel.size() == 1 && !sel.get(0).dir && sel.get(0).local()) m.getMenu().add("Открыть как текст");
                    if (twoPanes && other(pane).loc != null && other(pane).loc.writable()) { m.getMenu().add("Копировать в другую панель"); m.getMenu().add("Переместить в другую панель"); }
                }
                m.getMenu().add("Выбрать все"); m.getMenu().add("Свойства");
                m.setOnMenuItemClickListener(mi -> { onMore(String.valueOf(mi.getTitle()), pane, sel); return true; });
                m.show(); break;
            }
        }
    }

    private Pane other(Pane p) { return p == panes[0] ? panes[1] : panes[0]; }

    private void onMore(String what, Pane pane, List<Fs.Entry> sel) {
        Loc loc = pane.loc;
        switch (what) {
            case "Восстановить": pane.run("Восстанавливаю", p -> { for (Fs.Entry e : sel) Fs.restore(e.file); }); break;
            case "Переименовать": ask("Новое имя", sel.get(0).name, n -> pane.run("Переименовываю", p -> Ops.rename(loc, sel.get(0), n))); break;
            case "Переименовать по шаблону…": batchRename(pane, sel); break;
            case "Упаковать в zip": ask("Имя архива", sel.size() == 1 ? sel.get(0).name.replaceAll("\\.[^.]+$", "") : loc.title(), n -> { List<File> fs = new ArrayList<>(); for (Fs.Entry e : sel) fs.add(e.file); pane.run("Упаковываю", p -> Fs.zip(fs, ((LocalLoc) loc).dir, n, p)); }); break;
            case "Распаковать": pane.run("Распаковываю", p -> Fs.unzip(sel.get(0).file, ((LocalLoc) loc).dir, p)); break;
            case "Распаковать выбранное…": { Pane o = twoPanes ? other(pane) : null; Loc dst = o != null && o.loc instanceof LocalLoc ? o.loc : new LocalLoc(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)); final Loc fd = dst; pane.run("Распаковываю в " + dst.title(), p -> Ops.copy(loc, sel, fd, false, p)); break; }
            case "В избранное": case "Убрать из избранного": toggleFavorite(sel.get(0).file); pane.selected.clear(); pane.adapter.notifyDataSetChanged(); updateBars(); break;
            case "Открыть с помощью…": pane.run("Открываю", p -> { File f = loc.materialize(sel.get(0), p); ui.post(() -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.uriFor(f), "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть с помощью"))); }); break;
            case "Открыть как текст": edit(sel.get(0).file); break;
            case "Копировать в другую панель": transfer(loc, sel, other(pane).loc, false, pane); break;
            case "Переместить в другую панель": transfer(loc, sel, other(pane).loc, true, pane); break;
            case "Выбрать все": for (Fs.Entry e : pane.items) if (!(e instanceof RootEntry)) pane.selected.add(e.file.getAbsolutePath()); pane.adapter.notifyDataSetChanged(); updateBars(); break;
            case "Свойства": properties(sel); break;
        }
    }

    private void paste() {
        final List<Fs.Entry> src = new ArrayList<>(clip); final boolean cut = clipCut; final Loc from = clipFrom, target = active.loc;
        clip.clear();
        transfer(from, src, target, cut, active);
    }

    private void share(Pane pane, List<Fs.Entry> sel) {
        pane.run("Готовлю", p -> {
            ArrayList<Uri> uris = new ArrayList<>(); String mime = "*/*";
            for (Fs.Entry e : sel) if (!e.dir) { File f = pane.loc.materialize(e, p); uris.add(FileProvider.uriFor(f)); mime = Fs.mime(e.name); }
            if (uris.isEmpty()) throw new IOException("Папки так не отправить: упакуйте в zip");
            final Intent i = uris.size() == 1 ? new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0)).setType(mime) : new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ui.post(() -> startActivity(Intent.createChooser(i, "Поделиться")));
        });
    }

    private void properties(List<Fs.Entry> sel) {
        final AlertDialog d = new AlertDialog.Builder(this).setTitle(sel.size() == 1 ? sel.get(0).name : sel.size() + " объектов").setMessage("Считаю…").setPositiveButton("Закрыть", null).show();
        new Thread(() -> {
            long size = 0; int files = 0, dirs = 0;
            for (Fs.Entry e : sel) { size += e.local() ? Fs.treeSize(e.file) : e.size; if (e.dir) dirs++; else files++; }
            StringBuilder sb = new StringBuilder();
            if (sel.size() == 1) { Fs.Entry e = sel.get(0); sb.append("Путь: ").append(e.local() ? e.file.getAbsolutePath() : e.ref).append("\n"); if (e.dir && e.local()) sb.append("Внутри: ").append(Fs.count(e.file)).append(" объектов\n"); else if (!e.dir) sb.append("Тип: ").append(Fs.mime(e.name)).append("\n"); sb.append("Изменён: ").append(Fs.date(e.mtime)).append("\n"); if (e.local() && !e.dir && e.size < 256L * 1024 * 1024) sb.append("SHA-256: ").append(sha256(e.file)).append("\n"); }
            else sb.append("Файлов: ").append(files).append(", папок: ").append(dirs).append("\n");
            sb.append("Размер: ").append(Fs.size(size)).append(" (").append(String.format(java.util.Locale.ROOT, "%,d", size)).append(" байт)");
            final String msg = sb.toString(); ui.post(() -> d.setMessage(msg));
        }).start();
    }

    static String sha256(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) { java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256"); byte[] b = new byte[65536]; int r; while ((r = in.read(b)) > 0) md.update(b, 0, r); StringBuilder s = new StringBuilder(); for (byte x : md.digest()) s.append(String.format("%02x", x)); return s.toString(); }
        catch (Exception e) { return "—"; }
    }

    private void batchRename(Pane pane, List<Fs.Entry> sel) {
        final List<File> files = new ArrayList<>(); for (Fs.Entry e : sel) files.add(e.file);
        LinearLayout box = column(); int p = dp(20); box.setPadding(p, p, p, 0);
        box.addView(text("Шаблон: {name} — старое имя, {n} / {n3} — номер, {date} — дата файла, {ext} — расширение (добавится само).", 12, MUTED));
        EditText pattern = new EditText(this); pattern.setHint("например: Отпуск {n3}"); pattern.setSingleLine(true); box.addView(pattern);
        EditText find = new EditText(this); find.setHint("найти (необязательно)"); find.setSingleLine(true); box.addView(find);
        EditText repl = new EditText(this); repl.setHint("заменить на"); repl.setSingleLine(true); box.addView(repl);
        TextView preview = text("", 12, MUTED); preview.setPadding(0, dp(8), 0, 0); box.addView(preview);
        Runnable upd = () -> { List<String[]> plan = Fs.renamePlan(files, pattern.getText().toString(), find.getText().toString(), repl.getText().toString(), 1); StringBuilder s = new StringBuilder(); for (int i = 0; i < Math.min(3, plan.size()); i++) s.append(new File(plan.get(i)[0]).getName()).append(" → ").append(plan.get(i)[1]).append("\n"); if (plan.size() > 3) s.append("… и ещё ").append(plan.size() - 3); preview.setText(s.toString()); };
        android.text.TextWatcher w = new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int b, int c) {} public void onTextChanged(CharSequence s, int a, int b, int c) {} public void afterTextChanged(android.text.Editable s) { upd.run(); } };
        pattern.addTextChangedListener(w); find.addTextChangedListener(w); repl.addTextChangedListener(w); upd.run();
        new AlertDialog.Builder(this).setTitle("Переименовать " + files.size()).setView(box).setPositiveButton("Переименовать", (d, ww) -> pane.run("Переименовываю", pr -> Fs.renameApply(Fs.renamePlan(files, pattern.getText().toString(), find.getText().toString(), repl.getText().toString(), 1)))).setNegativeButton("Отмена", null).show();
    }

    // ====================================================================== menu ===
    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        Loc loc = active.loc;
        if (loc != null && loc.writable() && !active.trashMode()) { m.getMenu().add("Новая папка"); m.getMenu().add("Выбрать все"); }
        if (loc instanceof LocalLoc && !active.trashMode()) m.getMenu().add(favorites().contains(((LocalLoc) loc).dir.getAbsolutePath()) ? "Убрать папку из избранного" : "Папку в избранное");
        if (active.trashMode()) m.getMenu().add("Очистить корзину");
        m.getMenu().add("Сортировка: " + sortName());
        m.getMenu().add((hidden ? "Скрыть" : "Показать") + " скрытые");
        m.getMenu().add((showSizes ? "Не считать" : "Считать") + " размеры папок");
        m.getMenu().add((twoPanes ? "Одна панель" : "Две панели"));
        if (loc != null) m.getMenu().add("Свойства папки");
        if (prefs.contains("secret")) m.getMenu().add("Отвязать ПК");
        m.getMenu().add("Проверить обновления"); m.getMenu().add("О программе");
        m.setOnMenuItemClickListener(mi -> {
            String t = String.valueOf(mi.getTitle());
            if (t.equals("Новая папка")) ask("Имя папки", "Новая папка", n -> active.run("Создаю", p -> Ops.mkdir(active.loc, n)));
            else if (t.equals("Выбрать все")) onMore("Выбрать все", active, new ArrayList<>());
            else if (t.endsWith("избранное") || t.endsWith("избранного")) toggleFavorite(((LocalLoc) active.loc).dir);
            else if (t.equals("Очистить корзину")) confirm("Очистить корзину? Это навсегда.", () -> active.run("Очищаю", p -> { for (Fs.Entry e : active.items) Fs.purge(e.file); }));
            else if (t.startsWith("Сортировка")) askSort();
            else if (t.endsWith("скрытые")) { hidden = !hidden; prefs.edit().putBoolean("hidden", hidden).apply(); for (Pane p : panes) if (p.loc != null) p.refresh(false); }
            else if (t.endsWith("размеры папок")) { showSizes = !showSizes; prefs.edit().putBoolean("sizes", showSizes).apply(); for (Pane p : panes) p.adapter.notifyDataSetChanged(); }
            else if (t.equals("Две панели") || t.equals("Одна панель")) panesBtn.performClick();
            else if (t.equals("Свойства папки")) { List<Fs.Entry> l = new ArrayList<>(); l.add(active.loc instanceof LocalLoc ? new Fs.Entry(((LocalLoc) active.loc).dir) : new Fs.Entry(active.loc.title(), true, 0, 0, active.loc.path())); properties(l); }
            else if (t.equals("Отвязать ПК")) { prefs.edit().remove("secret").remove("pin").remove("host").remove("port").remove("hostport").remove("lan").apply(); pc = null; for (Pane p : panes) if (p.loc instanceof PcLoc || p.rootsMode) p.showRoots(); toast("ПК отвязан"); }
            else if (t.equals("Проверить обновления")) checkUpdate(true);
            else if (t.equals("О программе")) new AlertDialog.Builder(this).setTitle("Проводник").setMessage("Файловый менеджер без рекламы, покупок и слежки. В сеть ходит только за обновлениями на GitHub и, если вы его подключили, к вашему же ПК.\n\nИсходники: github.com/troublefack1-hue/-\n\nСоздано Николаем Коноваловым для вас, с любовью.").setPositiveButton("Ок", null).show();
            return true;
        });
        m.show();
    }

    private String sortName() { return (sort == Fs.Sort.NAME ? "имя" : sort == Fs.Sort.DATE ? "дата" : sort == Fs.Sort.SIZE ? "размер" : "тип") + (desc ? " ↓" : " ↑"); }

    private void askSort() {
        String[] opts = {"Имя ↑", "Имя ↓", "Дата (новые первые)", "Дата (старые первые)", "Размер (большие первые)", "Размер (малые первые)", "Тип ↑", "Тип ↓"};
        new AlertDialog.Builder(this).setTitle("Сортировка").setItems(opts, (d, i) -> { sort = Fs.Sort.values()[i / 2]; desc = i % 2 == 1; prefs.edit().putString("sort", sort.name()).putBoolean("desc", desc).apply(); for (Pane p : panes) if (p.loc != null) p.refresh(false); }).show();
    }

    // ==================================================================== search ===
    private void askSearch() {
        final Pane pane = active;
        final File from = pane.loc instanceof LocalLoc ? ((LocalLoc) pane.loc).dir : Environment.getExternalStorageDirectory();
        if (pane.loc != null && !(pane.loc instanceof LocalLoc)) { toast("Поиск работает в папках телефона"); return; }
        LinearLayout box = column(); int p = dp(20); box.setPadding(p, p, p, 0);
        EditText q = new EditText(this); q.setHint("имя или маска: отчёт*, *.pdf"); q.setSingleLine(true); box.addView(q);
        LinearLayout kinds = new LinearLayout(this); kinds.setOrientation(LinearLayout.HORIZONTAL);
        final String[] kn = {"Фото", "Видео", "Музыка", "Документы"}; final Fs.Kind[] kk = {Fs.Kind.IMAGE, Fs.Kind.VIDEO, Fs.Kind.AUDIO, Fs.Kind.DOC};
        final CheckBox[] kc = new CheckBox[kn.length];
        for (int i = 0; i < kn.length; i++) { kc[i] = new CheckBox(this); kc[i].setText(kn[i]); kc[i].setTextSize(12); kinds.addView(kc[i]); }
        HorizontalScrollView ks = new HorizontalScrollView(this); ks.addView(kinds); box.addView(ks);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        final android.widget.Spinner size = new android.widget.Spinner(this); size.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"любой размер", "> 1 МБ", "> 10 МБ", "> 100 МБ", "< 100 КБ"})); row.addView(size, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final android.widget.Spinner date = new android.widget.Spinner(this); date.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"за всё время", "за день", "за неделю", "за месяц", "за год"})); row.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);
        final CheckBox content = new CheckBox(this); content.setText("искать в содержимом текстовых файлов"); content.setTextSize(12); box.addView(content);
        new AlertDialog.Builder(this).setTitle("Искать в «" + (pane.loc == null ? "Память" : from.getName()) + "»").setView(box).setPositiveButton("Найти", (d, w) -> {
            Fs.Query query = new Fs.Query(); query.text = q.getText().toString().trim();
            java.util.EnumSet<Fs.Kind> set = java.util.EnumSet.noneOf(Fs.Kind.class); for (int i = 0; i < kc.length; i++) if (kc[i].isChecked()) set.add(kk[i]); if (!set.isEmpty()) { query.kinds = set; query.folders = false; }
            long[] mins = {-1, 1L << 20, 10L << 20, 100L << 20, -1}; long[] maxs = {-1, -1, -1, -1, 100L << 10};
            query.minSize = mins[size.getSelectedItemPosition()]; query.maxSize = maxs[size.getSelectedItemPosition()];
            long[] ago = {-1, 86400_000L, 7 * 86400_000L, 30 * 86400_000L, 365 * 86400_000L}; int di = date.getSelectedItemPosition(); query.since = ago[di] < 0 ? -1 : System.currentTimeMillis() - ago[di];
            if (content.isChecked()) query.content = query.text.isEmpty() ? "" : query.text.replace("*", "").replace("?", "");
            if (content.isChecked()) { query.text = ""; if (query.content.isEmpty()) { toast("Для поиска по содержимому нужен текст"); return; } }
            if (query.text.isEmpty() && query.kinds == null && query.minSize < 0 && query.maxSize < 0 && query.since < 0 && query.content.isEmpty()) return;
            runSearch(pane, from, query, q.getText().toString().trim());
        }).setNegativeButton("Отмена", null).show();
    }

    private void runSearch(Pane pane, File from, Fs.Query query, String shown) {
        pane.searchMode = true; pane.rootsMode = false; pane.selected.clear(); pane.items = new ArrayList<>(); pane.adapter.notifyDataSetChanged();
        if (pane.loc == null) pane.loc = new LocalLoc(from);
        title.setText("Поиск" + (shown.isEmpty() ? "" : ": " + shown)); subtitle.setText("ищу…"); crumbs.removeAllViews(); updateBars(); pane.empty.setVisibility(View.GONE);
        final List<Fs.Entry> found = new ArrayList<>();
        new Thread(() -> {
            Fs.search(from, query, hidden, e -> { synchronized (found) { found.add(e); } if (found.size() % 20 == 0) ui.post(() -> publish(pane, found)); return pane.searchMode && found.size() < 2000; });
            ui.post(() -> { publish(pane, found); if (pane.searchMode) { subtitle.setText(found.size() + " найдено в " + from.getAbsolutePath()); pane.empty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE); pane.emptyText.setText("Ничего не найдено"); } });
        }).start();
    }

    private void publish(Pane pane, List<Fs.Entry> found) { if (!pane.searchMode) return; synchronized (found) { pane.items = new ArrayList<>(found); } Fs.sort(pane.items, sort, desc); pane.adapter.notifyDataSetChanged(); }

    @Override public void onBackPressed() { if (!active.back()) super.onBackPressed(); }

    // ==================================================================== update ===
    private void checkUpdate(boolean manual) {
        if (manual) toast("Проверяю обновления…");
        new Thread(() -> {
            try {
                String cur = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                Updater.Info info = Updater.check(cur, "pcremote-files.apk");
                if (info == null) { if (manual) ui.post(() -> toast("Это последняя версия (" + cur + ")")); return; }
                if (manual) ui.post(() -> toast("Скачиваю " + info.version + "…"));
                File apk = Updater.download(info.url, getCacheDir(), info.sha256);
                ui.post(() -> { toast("Обновление " + info.version + " — установите"); startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.uriFor(apk), "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK)); });
            } catch (Exception e) { if (manual) ui.post(() -> toast("Не удалось проверить: " + e.getMessage())); }
        }).start();
    }

    // =================================================================== adapter ===
    private final class Adapter extends BaseAdapter {
        final Pane pane;
        Adapter(Pane p) { pane = p; }
        @Override public int getCount() { return pane.items.size(); }
        @Override public Object getItem(int i) { return pane.items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return grid ? 1 : 0; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            Fs.Entry e = pane.items.get(pos);
            Row r = cv == null ? new Row(grid) : (Row) cv.getTag();
            r.bind(pane, e); return r.view;
        }
    }

    private final class Row {
        final LinearLayout view; final ImageView icon; final TextView name, meta, check; ProgressBar usage;
        Row(boolean asGrid) {
            view = new LinearLayout(MainActivity.this); view.setTag(this);
            view.setOrientation(asGrid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); view.setGravity(asGrid ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
            view.setPadding(dp(8), dp(asGrid ? 8 : 7), dp(8), dp(asGrid ? 8 : 7)); view.setBackground(ripple(Color.TRANSPARENT));
            icon = new ImageView(MainActivity.this); icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int s = dp(asGrid ? 84 : 44); view.addView(icon, new LinearLayout.LayoutParams(s, s));
            LinearLayout texts = column(); texts.setPadding(dp(asGrid ? 0 : 12), dp(asGrid ? 6 : 0), 0, 0); texts.setGravity(asGrid ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            name = text("", asGrid ? 12 : 15, TEXT); name.setSingleLine(!asGrid); name.setMaxLines(asGrid ? 2 : 1); name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); if (asGrid) name.setGravity(Gravity.CENTER);
            meta = text("", 12, MUTED); meta.setSingleLine(true);
            texts.addView(name); if (!asGrid) texts.addView(meta);
            usage = new ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal); usage.setMax(100); usage.setVisibility(View.GONE);
            usage.setProgressTintList(ColorStateList.valueOf(ACCENT)); usage.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)); up.setMargins(0, dp(8), 0, 0);
            if (!asGrid) texts.addView(usage, up);
            view.addView(texts, asGrid ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            check = text("✓", 13, Color.BLACK); check.setGravity(Gravity.CENTER); check.setTypeface(null, Typeface.BOLD);
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(ACCENT); check.setBackground(dot);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(22), dp(22)); cp.setMargins(dp(8), 0, dp(4), 0);
            if (!asGrid) view.addView(check, cp);
        }

        void bind(Pane pane, Fs.Entry e) {
            boolean sel = pane.selected.contains(e.file.getAbsolutePath()), isRoot = e instanceof RootEntry;
            view.setBackground(ripple(sel ? PANEL2 : isRoot ? PANEL : Color.TRANSPARENT));
            if (isRoot) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0, dp(3), 0, dp(3)); view.setLayoutParams(lp); view.setPadding(dp(12), dp(14), dp(12), dp(14)); }
            else { view.setPadding(dp(8), dp(grid ? 8 : 7), dp(8), dp(grid ? 8 : 7)); if (view.getLayoutParams() instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) view.getLayoutParams()).setMargins(0, 0, 0, 0); }
            check.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
            if (sel) { check.setScaleX(0.6f); check.setScaleY(0.6f); check.animate().scaleX(1f).scaleY(1f).setDuration(140).start(); }
            icon.setAlpha(sel ? 0.75f : 1f); name.setText(e.name);
            if (isRoot) {
                Root r = ((RootEntry) e).r; name.setText(r.name); icon.setTag(null); icon.setImageResource(r.icon); icon.setColorFilter(r.icon == R.drawable.ic_sd ? ACCENT : 0); icon.setClickable(false); icon.setOnClickListener(null);
                meta.setText(r.hint); usage.setVisibility(r.used >= 0 ? View.VISIBLE : View.GONE); if (r.used >= 0) usage.setProgress(Math.round(r.used * 100));
                return;
            }
            icon.setColorFilter(0); usage.setVisibility(View.GONE);
            if (!grid) { icon.setClickable(true); icon.setBackground(ripple(Color.TRANSPARENT)); icon.setOnClickListener(v -> { setActive(pane); pane.toggle(e); }); } else { icon.setClickable(false); icon.setOnClickListener(null); }
            if (pane.searchMode) meta.setText(e.file.getParent());
            else if (pane.trashMode()) { String o = Fs.trashOrigin(e.file); meta.setText(o == null ? "" : "из " + new File(o).getParent()); }
            else if (e.dir) meta.setText(e.local() ? folderMeta(pane, e) : "папка");
            else meta.setText(Fs.size(e.size) + (e.mtime > 0 ? " · " + Fs.date(e.mtime) : ""));
            if (e.local()) thumbs.load(icon, e.file, e.kind, iconFor(e.kind)); else { icon.setTag(null); icon.setImageResource(iconFor(e.kind)); }
        }

        /** "12 объектов · 340 МБ": the count at once, the size from a background walk, cached by path+mtime. */
        String folderMeta(Pane pane, Fs.Entry e) {
            int n = Fs.count(e.file); String base = n + " " + plural(n, "объект", "объекта", "объектов");
            if (!showSizes) return base;
            String key = e.file.getAbsolutePath() + ":" + e.mtime;
            Long sz = folderSizes.get(key);
            if (sz != null) return base + " · " + Fs.size(sz);
            if (folderSizes.size() > 5000) folderSizes.clear();
            folderSizes.put(key, -1L);
            sizer.execute(() -> { long s = Fs.treeSize(e.file); ui.post(() -> { folderSizes.put(key, s); pane.adapter.notifyDataSetChanged(); }); });
            return base;
        }
    }

    static int iconFor(Fs.Kind k) {
        switch (k) {
            case FOLDER: return R.drawable.ic_folder; case IMAGE: return R.drawable.ic_image; case VIDEO: return R.drawable.ic_video; case AUDIO: return R.drawable.ic_audio;
            case APK: return R.drawable.ic_apk; case ARCHIVE: return R.drawable.ic_zip; case DOC: case TEXT: return R.drawable.ic_doc; default: return R.drawable.ic_file;
        }
    }

    // =================================================================== helpers ===
    static String plural(int n, String one, String few, String many) { int m = n % 100, u = n % 10; return (m >= 11 && m <= 14) ? many : u == 1 ? one : (u >= 2 && u <= 4) ? few : many; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setTextColor(Color.BLACK); b.setBackgroundColor(ACCENT); b.setAllCaps(false); b.setTypeface(null, Typeface.BOLD); return b; }
    private ImageButton icon(int res, View.OnClickListener l) { ImageButton b = new ImageButton(this); b.setImageResource(res); b.setColorFilter(TEXT); b.setBackground(ripple(Color.TRANSPARENT)); b.setPadding(dp(10), dp(10), dp(10), dp(10)); b.setOnClickListener(l); return b; }
    private RippleDrawable ripple(int base) { GradientDrawable bg = new GradientDrawable(); bg.setColor(base); bg.setCornerRadius(dp(12)); return new RippleDrawable(ColorStateList.valueOf(0x33E0A030), bg, null); }
    private void confirm(String q, Runnable yes) { new AlertDialog.Builder(this).setMessage(q).setPositiveButton("Да", (d, w) -> yes.run()).setNegativeButton("Нет", null).show(); }
    interface Op { void run(Fs.Progress p) throws Exception; }
    interface Answer { void on(String s); }
    private void ask(String titleText, String value, Answer a) {
        EditText e = new EditText(this); e.setText(value); e.setInputType(InputType.TYPE_CLASS_TEXT); e.setSingleLine(true); e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(titleText).setView(e).setPositiveButton("Ок", (d, w) -> a.on(e.getText().toString().trim())).setNegativeButton("Отмена", null).show();
    }
}
