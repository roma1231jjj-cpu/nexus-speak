package com.local.aivoice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Pulsing "listening" indicator (three concentric breathing rings). */
public class PulseView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long start = -1;
    private int color = Color.WHITE;
    private boolean active = true;

    public PulseView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setColor(int c) { color = c; invalidate(); }
    public void setActive(boolean a) { active = a; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        if (start < 0) start = android.os.SystemClock.uptimeMillis();
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(cx, cy) * 0.5f;
        float t = (android.os.SystemClock.uptimeMillis() - start) / 1000f;
        for (int i = 0; i < 3; i++) {
            float phase = (t * 0.8f + i * 0.33f) % 1f;
            float r = base * (0.6f + phase * 0.7f);
            int alpha = (int) ((1f - phase) * (active ? 110 : 35));
            paint.setColor(color);
            paint.setAlpha(alpha);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            canvas.drawCircle(cx, cy, r, paint);
        }
        paint.setAlpha(active ? 200 : 80);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, base * 0.42f, paint);
        postInvalidateOnAnimation();
    }
}
