package com.local.aivoice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;

/** Фоновые блики вместо bg_blobs.xml: там цвета брались из атрибутов темы внутри gradient,
 *  а так умеет не каждая версия Android. Здесь то же самое, но без XML. */
public final class NxBlobs extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] colors;
    private final int base;
    private final float density;
    private int width, height;

    private NxBlobs(float density, int bg, int c1, int c2, int c3) {
        this.density = density <= 0f ? 1f : density;
        base = bg;
        colors = new int[]{c1, c2, c3};
    }

    public static void apply(View root) {
        if (root == null) return;
        Context c = root.getContext();
        NxBlobs d = new NxBlobs(
                c.getResources().getDisplayMetrics().density,
                Ui.color(c, R.attr.nxBg),
                Ui.color(c, R.attr.nxBlob1),
                Ui.color(c, R.attr.nxBlob2),
                Ui.color(c, R.attr.nxBlob3));
        root.setBackground(d);
    }

    public static void apply(android.app.Activity a) {
        View content = a.findViewById(android.R.id.content);
        if (content instanceof FrameLayout && ((FrameLayout) content).getChildCount() > 0) {
            apply(((FrameLayout) content).getChildAt(0));
        }
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        width = b.width();
        height = b.height();
    }

    @Override
    public void draw(Canvas canvas) {
        if (width <= 0 || height <= 0) return;
        canvas.drawColor(base);
        float k = density;
        glow(canvas, width * 0.28f, height * 0.24f, k * 420f, colors[0]);
        glow(canvas, width * 0.94f, height * 0.70f, k * 360f, colors[1]);
        glow(canvas, width * 0.50f, height * 1.04f, k * 300f, colors[2]);
    }

    private void glow(Canvas canvas, float cx, float cy, float radius, int color) {
        int a = Color.alpha(color);
        int mid = (color & 0x00FFFFFF) | ((int) (a * 0.45f) << 24);
        paint.setShader(new RadialGradient(cx, cy, radius,
                new int[]{color, mid, Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
