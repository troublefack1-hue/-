package ru.pcremote.files;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small previews for photos, videos and APK icons, decoded off the UI thread and cached in memory. */
public final class Thumbs {
    private final Context ctx;
    private final int px;
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    public Thumbs(Context ctx, int px) {
        this.ctx = ctx; this.px = px;
        int kb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(kb) { @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; } };
    }

    /** Sets a cached thumb at once, else the fallback icon and loads in the background; the tag guards against recycled rows. */
    public void load(ImageView iv, File f, Fs.Kind kind, int fallbackRes) {
        String key = f.getAbsolutePath() + ":" + f.lastModified();
        iv.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageResource(fallbackRes);
        if (kind != Fs.Kind.IMAGE && kind != Fs.Kind.VIDEO && kind != Fs.Kind.APK) return;
        pool.execute(() -> {
            Bitmap bm = null;
            try { bm = decode(f, kind); } catch (Throwable ignored) {}
            if (bm == null) return;
            bm = round(bm, px / 8f);
            cache.put(key, bm);
            final Bitmap done = bm;
            ui.post(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(done); });
        });
    }

    /** Rounded corners baked into the bitmap: cheaper than clipping every row on every frame. */
    static Bitmap round(Bitmap src, float radius) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        android.graphics.RectF r = new android.graphics.RectF(0, 0, src.getWidth(), src.getHeight());
        c.drawRoundRect(r, radius, radius, p);
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, 0, 0, p);
        if (out != src) src.recycle();
        return out;
    }

    private Bitmap decode(File f, Fs.Kind kind) {
        if (kind == Fs.Kind.VIDEO) {
            Bitmap v = ThumbnailUtils.createVideoThumbnail(f.getAbsolutePath(), android.provider.MediaStore.Images.Thumbnails.MINI_KIND);
            return v == null ? null : ThumbnailUtils.extractThumbnail(v, px, px);
        }
        if (kind == Fs.Kind.APK) {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null) return null;
            ApplicationInfo ai = pi.applicationInfo; ai.sourceDir = ai.publicSourceDir = f.getAbsolutePath();
            Drawable d = ai.loadIcon(pm);
            Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b); d.setBounds(0, 0, px, px); d.draw(c);
            return b;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) return null;
        int s = 1; while (o.outWidth / s > px * 2 && o.outHeight / s > px * 2) s *= 2;
        o = new BitmapFactory.Options(); o.inSampleSize = s; o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return b == null ? null : ThumbnailUtils.extractThumbnail(b, px, px, ThumbnailUtils.OPTIONS_RECYCLE_INPUT);
    }
}
