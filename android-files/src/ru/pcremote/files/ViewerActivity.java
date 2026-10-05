package ru.pcremote.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen photo viewer: swipe between the folder's pictures, pinch to zoom, double tap,
 * tap to show/hide the bar (name, "3 из 40", share, rotate, info, delete). EXIF orientation honoured.
 */
public class ViewerActivity extends Activity {
    public static final String EXTRA_FILES = "files", EXTRA_INDEX = "index";
    private final ArrayList<File> files = new ArrayList<>();
    private int index;
    private Pager pager;
    private LinearLayout bar;
    private TextView name, counter;
    private boolean barShown = true, changed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 4)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; } };

    public static void open(Context ctx, List<File> imgs, int at) {
        String[] a = new String[imgs.size()]; for (int i = 0; i < a.length; i++) a[i] = imgs.get(i).getAbsolutePath();
        ctx.startActivity(new Intent(ctx, ViewerActivity.class).putExtra(EXTRA_FILES, a).putExtra(EXTRA_INDEX, at));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0x66000000); getWindow().setNavigationBarColor(0x66000000);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        String[] a = getIntent().getStringArrayExtra(EXTRA_FILES);
        if (a != null) for (String p : a) files.add(new File(p));
        index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), files.size() - 1));
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        pager = new Pager(this); root.addView(pager, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // top bar
        bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x99000000); bar.setPadding(dp(4), dp(28), dp(4), dp(6));
        bar.addView(icon(R.drawable.ic_back, v -> finish()));
        LinearLayout t = new LinearLayout(this); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(6), 0, dp(6), 0);
        name = text("", 15, Color.WHITE); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        counter = text("", 12, 0xFFBBBBBB);
        t.addView(name); t.addView(counter);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(icon(R.drawable.ic_share, v -> share()));
        bar.addView(icon(R.drawable.ic_rotate, v -> pager.rotate()));
        bar.addView(icon(R.drawable.ic_info, v -> info()));
        bar.addView(icon(R.drawable.ic_delete, v -> delete()));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        setContentView(root);
        if (files.isEmpty()) { finish(); return; }
        pager.show(index, false);
        hideBarLater();
    }

    private void toggleBar() {
        barShown = !barShown;
        bar.animate().alpha(barShown ? 1f : 0f).translationY(barShown ? 0 : -bar.getHeight()).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        if (barShown) hideBarLater();
    }
    private void hideBarLater() { ui.removeCallbacksAndMessages(null); ui.postDelayed(() -> { if (barShown) toggleBar(); }, 2500); }

    private void updateTitle() {
        File f = files.get(index);
        name.setText(f.getName()); counter.setText((index + 1) + " из " + files.size() + " · " + Fs.size(f.length()) + " · " + Fs.date(f.lastModified()));
    }

    private void share() {
        File f = files.get(index);
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, FileProvider.uriFor(f)).setType(Fs.mime(f.getName())).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Поделиться"));
    }

    private void info() {
        File f = files.get(index);
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        String when = "";
        try { ExifInterface ex = new ExifInterface(f.getAbsolutePath()); String d = ex.getAttribute(ExifInterface.TAG_DATETIME); String model = ex.getAttribute(ExifInterface.TAG_MODEL); if (d != null) when = "\nСнято: " + d; if (model != null) when += "\nКамера: " + model; } catch (Exception ignored) {}
        new AlertDialog.Builder(this).setTitle(f.getName()).setMessage("Путь: " + f.getAbsolutePath() + "\nРазмер: " + Fs.size(f.length()) + (o.outWidth > 0 ? "\nПиксели: " + o.outWidth + "×" + o.outHeight : "") + "\nИзменён: " + Fs.date(f.lastModified()) + when).setPositiveButton("Ок", null).show();
    }

    private void delete() {
        File f = files.get(index);
        new AlertDialog.Builder(this).setMessage("Удалить «" + f.getName() + "» в корзину?").setPositiveButton("Удалить", (d, w) -> {
            try { Fs.toTrash(f, Environment.getExternalStorageDirectory()); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show(); return; }
            changed = true; files.remove(index);
            if (files.isEmpty()) { finish(); return; }
            index = Math.min(index, files.size() - 1); pager.show(index, false);
        }).setNegativeButton("Отмена", null).show();
    }

    @Override public void finish() { if (changed) setResult(RESULT_OK); super.finish(); }

    // ---- decoding -------------------------------------------------------
    private void load(File f, ZoomImageView iv) {
        String key = f.getAbsolutePath() + ":" + f.lastModified();
        iv.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) { iv.setImageDrawable(new BitmapDrawable(getResources(), b)); return; }
        iv.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bm = decode(f);
            if (bm == null) return;
            cache.put(key, bm);
            ui.post(() -> { if (key.equals(iv.getTag())) { iv.setImageDrawable(new BitmapDrawable(getResources(), bm)); iv.setAlpha(0f); iv.animate().alpha(1f).setDuration(160).start(); } });
        });
    }

    private Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0) return null;
            int maxSide = Math.max(2048, Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) * 2);
            int s = 1; while (o.outWidth / s > maxSide || o.outHeight / s > maxSide) s *= 2;
            o = new BitmapFactory.Options(); o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b == null) return null;
            int rot = 0;
            try {
                int or = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {}
            if (rot != 0) { Matrix m = new Matrix(); m.postRotate(rot); b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); }
            return b;
        } catch (Throwable e) { return null; }
    }

    // ---- pager ------------------------------------------------------------
    /** Three zoomable views side by side; horizontal drags page, unless the current picture is zoomed and not at its edge. */
    private final class Pager extends FrameLayout {
        final ZoomImageView[] views = new ZoomImageView[3];
        float downX, downY, offset; boolean dragging, maybeDrag; int rotation;
        Pager(Context c) {
            super(c);
            for (int i = 0; i < 3; i++) { views[i] = new ZoomImageView(c); views[i].setOnTap(ViewerActivity.this::toggleBar); addView(views[i], new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); }
        }
        ZoomImageView cur() { return views[1]; }
        void show(int i, boolean animate) {
            index = i; updateTitle(); rotation = 0;
            for (int k = 0; k < 3; k++) { int j = i + k - 1; views[k].setTranslationX((k - 1) * getWidth()); views[k].setRotation(0); if (j >= 0 && j < files.size()) { views[k].setVisibility(VISIBLE); load(files.get(j), views[k]); } else { views[k].setVisibility(INVISIBLE); views[k].setImageDrawable(null); } }
        }
        void rotate() { rotation = (rotation + 90) % 360; cur().animate().rotation(rotation).setDuration(200).start(); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); for (int k = 0; k < 3; k++) views[k].setTranslationX((k - 1) * w); }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: downX = e.getX(); downY = e.getY(); maybeDrag = e.getPointerCount() == 1; dragging = false; break;
                case MotionEvent.ACTION_POINTER_DOWN: maybeDrag = false; break;
                case MotionEvent.ACTION_MOVE:
                    if (!maybeDrag || dragging) break;
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    if (Math.abs(dx) > dp(12) && Math.abs(dx) > Math.abs(dy) * 1.5f && cur().atEdge(dx)) { dragging = true; return true; }
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!dragging) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    offset = e.getX() - downX;
                    if ((offset > 0 && index == 0) || (offset < 0 && index == files.size() - 1)) offset *= 0.3f;   // rubber band at the ends
                    for (int k = 0; k < 3; k++) views[k].setTranslationX((k - 1) * getWidth() + offset);
                    return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    int dir = Math.abs(offset) > getWidth() / 4f ? (offset < 0 ? 1 : -1) : 0;
                    if ((dir == 1 && index == files.size() - 1) || (dir == -1 && index == 0)) dir = 0;
                    settle(dir); dragging = false; return true;
            }
            return true;
        }

        void settle(int dir) {
            final float from = offset, to = dir == 0 ? 0 : -dir * getWidth();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f); a.setDuration(220); a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(an -> { float t = (float) an.getAnimatedValue(); float o = from + (to - from) * t; for (int k = 0; k < 3; k++) views[k].setTranslationX((k - 1) * getWidth() + o); });
            a.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator an) {
                if (dir != 0) { ZoomImageView[] n = new ZoomImageView[3]; for (int k = 0; k < 3; k++) n[k] = views[(k + dir + 3) % 3]; System.arraycopy(n, 0, views, 0, 3); views[(dir == 1 ? 2 : 0)].reset(); show(index + dir, false); }
                else for (int k = 0; k < 3; k++) views[k].setTranslationX((k - 1) * getWidth());
                offset = 0; } });
            a.start();
        }
    }

    // ---- helpers -------------------------------------------------------------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int sp, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t; }
    private ImageButton icon(int res, View.OnClickListener l) {
        ImageButton b = new ImageButton(this); b.setImageResource(res); b.setBackground(null); b.setColorFilter(Color.WHITE); b.setPadding(dp(10), dp(10), dp(10), dp(10)); b.setOnClickListener(l); return b;
    }
}
