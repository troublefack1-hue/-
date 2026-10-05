package ru.pcremote.files;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/** An ImageView with pinch zoom, drag and double-tap, bounded to the picture; tells the pager whether it is zoomed. */
public class ZoomImageView extends ImageView {
    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private float minScale = 1f, maxScale = 5f, baseScale = 1f;
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private Runnable onTap;
    private boolean laidOut;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float s = d.getScaleFactor(), cur = scale();
                float target = Math.max(minScale * 0.7f, Math.min(maxScale, cur * s));
                m.postScale(target / cur, target / cur, d.getFocusX(), d.getFocusY());
                bound(); setImageMatrix(m); return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) { if (scale() < minScale) animateTo(minScale, getWidth() / 2f, getHeight() / 2f); }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (!zoomed()) return false;
                m.postTranslate(-dx, -dy); bound(); setImageMatrix(m); return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                animateTo(zoomed() ? minScale : Math.min(maxScale, minScale * 2.5f), e.getX(), e.getY()); return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (onTap != null) onTap.run(); return true; }
        });
    }

    public void setOnTap(Runnable r) { onTap = r; }
    public boolean zoomed() { return scale() > minScale * 1.02f; }

    /** Can the pager take a horizontal drag in this direction? True at the picture's edge or when not zoomed. */
    public boolean atEdge(float dx) {
        if (!zoomed()) return true;
        RectF r = rect(); if (r == null) return true;
        return dx > 0 ? r.left >= -0.5f : r.right <= getWidth() + 0.5f;
    }

    private float scale() { m.getValues(v); return v[Matrix.MSCALE_X]; }

    private RectF rect() {
        Drawable d = getDrawable(); if (d == null) return null;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight()); m.mapRect(r); return r;
    }

    @Override public void setImageDrawable(Drawable d) { super.setImageDrawable(d); laidOut = false; fit(); }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); laidOut = false; fit(); }

    private void fit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || laidOut) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        baseScale = Math.min(getWidth() / dw, getHeight() / dh);
        minScale = baseScale; maxScale = Math.max(baseScale * 6f, 1f);
        m.reset(); m.postScale(baseScale, baseScale); m.postTranslate((getWidth() - dw * baseScale) / 2f, (getHeight() - dh * baseScale) / 2f);
        setImageMatrix(m); laidOut = true;
    }

    public void reset() { laidOut = false; fit(); }

    private void bound() {
        RectF r = rect(); if (r == null) return;
        float dx = 0, dy = 0, w = getWidth(), h = getHeight();
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left; else if (r.left > 0) dx = -r.left; else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2f - r.top; else if (r.top > 0) dy = -r.top; else if (r.bottom < h) dy = h - r.bottom;
        m.postTranslate(dx, dy);
    }

    private void animateTo(float target, float fx, float fy) {
        final float from = scale();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(220); a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(an -> { float t = (float) an.getAnimatedValue(); float s = from + (target - from) * t; float cur = scale(); m.postScale(s / cur, s / cur, fx, fy); bound(); setImageMatrix(m); });
        a.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e); gestures.onTouchEvent(e);
        return true;
    }
}
