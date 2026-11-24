package com.local.aivoice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 2.5D wireframe globe: a real sphere with latitude rings and meridians,
 * projected with perspective (nearer lines are thicker/brighter, far ones
 * fade), spinning on its Y axis and slowly rocking on X — so the rotation
 * is visible in different directions. Used on the splash, as the assistant
 * avatar and as the settings header.
 */
public class GlobeView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int BRANDS = 8;
    private static final int[] BRAND_COLORS = {
            0xFFD97757, 0xFF10A37F, 0xFF4285F4, 0xFF7C5CFF,
            0xFFFF6B00, 0xFFE8ECF1, 0xFFFF7000, 0xFF6C4CE0
    };

    private static final int LAT_RINGS = 6;     // horizontal circles
    private static final int MERIDIANS = 10;    // vertical circles
    private static final int STEPS = 44;

    private long last = -1;
    private float yaw = 0f;       // continuous spin
    private float pitch = 0.35f;  // rocking tilt
    private float roll = 0f;

    private int color = 0xFFB57CFF;
    private int glowColor = 0xFF7C5CFF;
    private boolean brandDots = false;
    private boolean pulse = false;
    private boolean perspective = true;
    private float spinSpeed = 0.55f; // rad/sec
    private Shader glowShader;
    private long glowKey = Long.MIN_VALUE;

    public GlobeView(Context c, AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
    }

    public void setColor(int c) { this.color = c; invalidate(); }
    public void setGlowColor(int c) { this.glowColor = c; invalidate(); }
    public void setBrandDots(boolean b) { this.brandDots = b; invalidate(); }
    public void setPulseActive(boolean p) { this.pulse = p; invalidate(); }
    public void setSpinSpeed(float s) { this.spinSpeed = s; }
    public void setPerspective(boolean p) { this.perspective = p; }
    public void nudge(float dYaw) { this.yaw += dYaw; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = android.os.SystemClock.uptimeMillis();
        if (last < 0) last = now;
        float dt = Math.min(0.05f, (now - last) / 1000f);
        last = now;

        yaw += spinSpeed * dt;
        pitch = 0.30f + (float) Math.sin(now / 2400.0) * 0.34f;  // rock up/down
        roll = (float) Math.sin(now / 5200.0) * 0.10f;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float R = Math.min(getWidth(), getHeight()) * 0.5f * 0.86f;

        // core glow: градиент кешируем, иначе аллокация в каждом кадре
        int glowAlpha = pulse ? 120 : 92;
        if (glowShader == null || glowKey != (glowColor * 31L + glowAlpha) * 31L + (long) (R * 1.5f)) {
            glowKey = (glowColor * 31L + glowAlpha) * 31L + (long) (R * 1.5f);
            glowShader = new RadialGradient(cx, cy, R * 1.5f,
                    new int[]{withAlpha(glowColor, glowAlpha),
                            withAlpha(glowColor, 34), 0x00000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        glow.setShader(glowShader);
        canvas.drawCircle(cx, cy, R * 1.5f, glow);

        if (pulse) {
            // breathing rings while listening
            float t = (now % 2200) / 2200f;
            for (int i = 0; i < 2; i++) {
                float ph = (t + i * 0.5f) % 1f;
                ring.setStrokeWidth(2.5f);
                ring.setColor(withAlpha(color, (int) ((1 - ph) * 90)));
                canvas.drawCircle(cx, cy, R * (1.02f + ph * 0.34f), ring);
            }
        }

        // latitude rings
        for (int l = 0; l <= LAT_RINGS; l++) {
            float phi = (float) (Math.PI * (l / (float) LAT_RINGS) - Math.PI / 2);
            if (Math.abs(phi) > 1.45f) continue; // poles collapse, skip
            drawCurve(canvas, cx, cy, R, points -> {
                for (int s = 0; s <= STEPS; s++) {
                    float theta = (float) (2 * Math.PI * s / STEPS);
                    points[s * 3] = (float) (Math.cos(phi) * Math.cos(theta));
                    points[s * 3 + 1] = (float) Math.sin(phi);
                    points[s * 3 + 2] = (float) (Math.cos(phi) * Math.sin(theta));
                }
            });
        }

        // meridians
        for (int m = 0; m < MERIDIANS; m++) {
            float theta0 = (float) (Math.PI * m / MERIDIANS);
            drawCurve(canvas, cx, cy, R, points -> {
                for (int s = 0; s <= STEPS; s++) {
                    float phi = (float) (2 * Math.PI * s / STEPS - Math.PI / 2);
                    points[s * 3] = (float) (Math.cos(phi) * Math.cos(theta0));
                    points[s * 3 + 1] = (float) Math.sin(phi);
                    points[s * 3 + 2] = (float) (Math.cos(phi) * Math.sin(theta0));
                }
            });
        }

        // brand dots travelling on the sphere surface
        if (brandDots) {
            for (int i = 0; i < BRANDS; i++) {
                double phi = Math.PI * (0.18 + 0.64 * ((i * 3 % BRANDS) / (double) (BRANDS - 1))) - Math.PI / 2;
                double theta = 2 * Math.PI * i / BRANDS + yaw * 0.5;
                float x = (float) (Math.cos(phi) * Math.cos(theta));
                float y = (float) Math.sin(phi);
                float z = (float) (Math.cos(phi) * Math.sin(theta));
                float[] p = project(x, y, z, cx, cy, R);
                if (p[2] <= 0) continue;
                dot.setColor(withAlpha(BRAND_COLORS[i], (int) (90 + 165 * p[2])));
                canvas.drawCircle(p[0], p[1], R * (0.035f + 0.02f * p[2]), dot);
            }
        }

        postInvalidateOnAnimation();
    }

    private interface Shaper { void shape(float[] points); }

    private float[] buf;

    /** draws one globe curve as depth-faded segments */
    private void drawCurve(Canvas canvas, float cx, float cy, float R, Shaper shaper) {
        if (buf == null || buf.length < (STEPS + 1) * 3) buf = new float[(STEPS + 1) * 3];
        shaper.shape(buf);
        float px = 0, py = 0, pz = 0;
        for (int s = 0; s <= STEPS; s++) {
            float[] p = project(buf[s * 3], buf[s * 3 + 1], buf[s * 3 + 2], cx, cy, R);
            if (s > 0) {
                float zAvg = (p[2] + pz) * 0.5f;              // -1..1
                float near = (zAvg + 1f) * 0.5f;              // 0..1
                int alpha = (int) (18 + 150 * near * near);
                line.setColor(withAlpha(color, alpha));
                line.setStrokeWidth(0.8f + 1.7f * near);
                canvas.drawLine(px, py, p[0], p[1], line);
            }
            px = p[0];
            py = p[1];
            pz = p[2];
        }
    }

    /** rotate + perspective-project a point on the unit sphere. returns {x, y, depth01} */
    private float[] project(float x, float y, float z, float cx, float cy, float R) {
        // yaw (around Y)
        float cy1 = (float) Math.cos(yaw), sy1 = (float) Math.sin(yaw);
        float x1 = x * cy1 + z * sy1;
        float z1 = -x * sy1 + z * cy1;
        // pitch (around X)
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        float y1 = y * cp - z1 * sp;
        float z2 = y * sp + z1 * cp;
        // roll (around Z) — slight, adds life
        float cr = (float) Math.cos(roll), sr = (float) Math.sin(roll);
        float x2 = x1 * cr - y1 * sr;
        float y2 = x1 * sr + y1 * cr;

        if (perspective) {
            float cam = 3.1f;
            float k = cam / (cam - z2);          // z2 in [-1,1] -> k in [0.75,1.48]
            return new float[]{cx + x2 * R * k, cy + y2 * R * k, z2};
        }
        return new float[]{cx + x2 * R, cy + y2 * R, z2};
    }

    private static int withAlpha(int c, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)),
                Color.red(c), Color.green(c), Color.blue(c));
    }
}
