package com.local.aivoice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Intro: brand orbs converge to an invisible center -> shimmering globe with
 * cross-fading brand logos inside, dual-spin meridians, specular highlight ->
 * orbs burst out with smooth physics (wall bounce, pair repulsion, center
 * gravity, glow pulse) -> fade out -> app. All motion is time-based with
 * cubic ease-in-out for frame-rate-independent smoothness.
 */
public class SplashView extends View {

    interface DoneListener { void onSplashDone(); }

    private static final long T1 = 1400;   // converge
    private static final long T2 = 3000;   // globe
    private static final long T3 = 4600;   // float physics
    private static final long T4 = 5500;   // fade

    private static final int[][] BRANDS = {
            {0xFFD97757, 'A'},   // Anthropic
            {0xFF10A37F, 'O'},   // OpenAI
            {0xFF4285F4, 'G'},   // Google Gemini
            {0xFF7C5CFF, 'D'},   // DeepSeek
            {0xFFFF6B00, 'K'},   // Kimi
            {0xFFE8ECF1, 'X'},   // Grok
            {0xFFFF7000, 'M'},   // Mistral
            {0xFF6C4CE0, 'Q'},   // Qwen
    };

    private static final String[] LOGO_FILES = {
            "anthropic.png", "openai.png", "gemini.png", "deepseek.png",
            "kimi.jpg", "xai.png", "mistral.png", "qwen.png"};

    private static class Orb {
        float sx, sy;      // start pos
        float x, y;        // current (float phase)
        float vx, vy;
        float r;
        int color;
        char letter;
        android.graphics.Bitmap logo;
    }

    private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint globeBody = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint globeSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Orb> orbs = new ArrayList<>();
    private android.graphics.Bitmap[] logos;
    private final Random rnd = new Random();
    private final ValueAnimator hueAnim;
    private volatile int hueColor = BRANDS[0][0];

    private long t0 = -1;
    private boolean burstInit = false;
    private float w, h, cx, cy, R;
    private DoneListener doneListener;
    private boolean done = false;

    public SplashView(Context c, AttributeSet a) {
        super(c, a);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        letterPaint.setTextAlign(Paint.Align.CENTER);
        letterPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        globeBody.setStyle(Paint.Style.FILL);
        discPaint.setColor(0xFF12121A);
        logos = new android.graphics.Bitmap[LOGO_FILES.length];
        for (int i = 0; i < LOGO_FILES.length; i++) {
            try (java.io.InputStream in = c.getAssets().open("logos/" + LOGO_FILES[i])) {
                logos[i] = android.graphics.BitmapFactory.decodeStream(in);
            } catch (Throwable ignored) {
            }
        }
        rimPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStyle(Paint.Style.FILL);

        hueAnim = ValueAnimator.ofFloat(0f, (float) BRANDS.length);
        hueAnim.setDuration(2400);
        hueAnim.setRepeatCount(ValueAnimator.INFINITE);
        hueAnim.addUpdateListener(an -> {
            float p = (Float) an.getAnimatedValue();
            hueColor = paletteLerp(p * BRANDS.length % BRANDS.length);
            postInvalidateOnAnimation();
        });
        hueAnim.start();
    }

    private int paletteLerp(float idx) {
        int i = (int) idx;
        float f = idx - i;
        int a = BRANDS[i % BRANDS.length][0];
        int b = BRANDS[(i + 1) % BRANDS.length][0];
        return Color.argb(255,
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    public void setDoneListener(DoneListener l) { doneListener = l; }

    @Override
    protected void onDraw(Canvas canvas) {
        if (t0 < 0) {
            t0 = android.os.SystemClock.uptimeMillis();
            w = getWidth(); h = getHeight();
            cx = w / 2f; cy = h / 2f;
            R = Math.min(w, h) * 0.28f;
            for (int[] b : BRANDS) {
                Orb o = new Orb();
                o.color = b[0];
                o.letter = (char) b[1];
                int idx = orbs.size();
                if (logos != null && idx < logos.length) o.logo = logos[idx];
                o.r = Math.min(w, h) * 0.072f;
                o.sx = rnd.nextBoolean() ? -o.r * 1.5f : w + o.r * 1.5f;
                o.sy = rnd.nextBoolean() ? -o.r * 1.5f : h + o.r * 1.5f;
                orbs.add(o);
            }
        }
        long t = android.os.SystemClock.uptimeMillis() - t0;
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        if (t < T1) {
            drawConverge(canvas, t / (float) T1);
        } else if (t < T2) {
            drawGlobe(canvas, (t - T1) / (float) (T2 - T1));
        } else {
            drawFloat(canvas, (t - T2) / 1000f, t >= T3 ? (t - T3) / (float) (T4 - T3) : 0f);
        }
        if (t >= T4 && !done) {
            done = true;
            if (doneListener != null) doneListener.onSplashDone();
        }
        postInvalidateOnAnimation();
    }

    // phase 1: fly to invisible center with spring-ease + shrink + fade
    private void drawConverge(Canvas c, float p) {
        for (int i = 0; i < orbs.size(); i++) {
            Orb o = orbs.get(i);
            // staggered start per orb for organic feel
            float t = clamp01((p - i * 0.03f) / (1f - i * 0.03f));
            float e = easeInOutSine(t);
            float x = lerp(o.sx, cx, e), y = lerp(o.sy, cy, e);
            float s = 1f - 0.92f * easeIn(t);
            float alpha = 1f - 0.6f * easeIn(t);
            drawOrb(c, x, y, o.r * s, o.color, o.letter, alpha, 0f, o.logo);
        }
    }

    // phase 2: globe
    private void drawGlobe(Canvas c, float p) {
        float grow = easeOutBack(clamp01(p / 0.35f));
        float rr = R * grow;
        if (rr <= 0) return;

        float spin = globeSpin(p);
        float spin2 = globeSpin2(p);

        // body: hue-cycling radial + rotating sweep
        globeBody.setShader(new RadialGradient(cx - rr * 0.3f, cy - rr * 0.35f, rr * 1.5f,
                new int[]{lighten(hueColor, 0.35f), hueColor, darken(hueColor, 0.45f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rr, globeBody);

        SweepGradient sg = new SweepGradient(cx, cy, paletteSweep(), null);
        Matrix m = new Matrix();
        m.postRotate(spin, cx, cy);
        sg.setLocalMatrix(m);
        globeSheen.setShader(new ComposeShader(sg,
                new RadialGradient(cx, cy, rr,
                        new int[]{0x00000000, 0x00000000, 0x66000000},
                        new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP),
                PorterDuff.Mode.SRC_OVER));
        globeSheen.setAlpha(110);
        c.drawCircle(cx, cy, rr, globeSheen);
        globeSheen.setAlpha(255);

        // meridian arcs, opposite directions
        rimPaint.setStrokeWidth(2.5f);
        for (int i = 0; i < 3; i++) {
            float srr = rr * (0.4f + 0.28f * i);
            float dir = (i % 2 == 0 ? spin : -spin2 * 1.25f);
            RectF rf = new RectF(cx - srr, cy - srr * 0.34f, cx + srr, cy + srr * 0.34f);
            rimPaint.setShader(null);
            rimPaint.setColor(Color.WHITE);
            rimPaint.setAlpha((int) (26 + 40 * Math.abs((float) Math.sin(spin * 0.02f + i))));
            c.save();
            c.rotate(dir, cx, cy);
            c.drawArc(rf, 0, 360, false, rimPaint);
            c.restore();
        }

        // specular highlight
        highlightPaint.setShader(new RadialGradient(cx - rr * 0.35f, cy - rr * 0.4f, rr * 0.5f,
                new int[]{0x59FFFFFF, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx - rr * 0.35f, cy - rr * 0.4f, rr * 0.5f, highlightPaint);

        // логотипы брендов по кругу: не буквы, а настоящие иконки провайдеров
        for (int i = 0; i < orbs.size(); i++) {
            Orb o = orbs.get(i);
            double a = spin * Math.PI / 180.0 + i * (2 * Math.PI / orbs.size());
            float ox = cx + (float) Math.cos(a) * rr * 1.32f;
            float oy = cy + (float) Math.sin(a) * rr * 0.92f;
            drawOrb(c, ox, oy, rr * 0.20f, o.color, o.letter, 0.95f, 0f, o.logo);
        }
        // в центре — тот же логотип, что и в орбите, без подмены на букву
        float seq = p * BRANDS.length;
        int idx = (int) seq;
        float frac = seq - idx;
        int[] b0 = BRANDS[idx % BRANDS.length];
        int[] b1 = BRANDS[(idx + 1) % BRANDS.length];
        android.graphics.Bitmap l0 = idx < orbs.size() ? orbs.get(idx).logo : null;
        int idx1 = (idx + 1) % BRANDS.length;
        android.graphics.Bitmap l1 = idx1 < orbs.size() ? orbs.get(idx1).logo : null;
        float fade = smoothstep(clamp01((frac - 0.75f) / 0.25f));
        drawOrb(c, cx, cy, rr * 0.40f, b0[0], (char) b0[1], 1f - fade, 0f, l0);
        if (fade > 0f) drawOrb(c, cx, cy, rr * 0.40f, b1[0], (char) b1[1], fade, 0f, l1);
    }

    private int[] paletteSweep() {
        int[] cols = new int[BRANDS.length + 1];
        for (int i = 0; i <= BRANDS.length; i++) cols[i] = BRANDS[i % BRANDS.length][0];
        return cols;
    }

    private float globeSpin(float p) {
        return easeInOutSine(p) * 540f;
    }

    private float globeSpin2(float p) {
        return easeInOutSine(p) * 300f;
    }

    // phase 3: burst + float + collide, alpha fades near end
    private void drawFloat(Canvas c, float dt, float fadeP) {
        float globalAlpha = fadeP <= 0 ? 1f : 1f - smoothstep(clamp01(fadeP));
        if (globalAlpha <= 0) return;
        drawBackGlobe(c, globalAlpha);
        if (!burstInit) {
            burstInit = true;
            for (Orb o : orbs) {
                float ang = rnd.nextFloat() * 2f * (float) Math.PI;
                float sp = Math.min(w, h) * (0.5f + rnd.nextFloat() * 0.45f);
                o.vx = (float) Math.cos(ang) * sp;
                o.vy = (float) Math.sin(ang) * sp;
                o.x = cx; o.y = cy;
            }
        }
        dt = Math.min(dt, 0.05f);
        for (Orb o : orbs) {
            // soft center gravity keeps composition alive
            float dx = cx - o.x, dy = cy - o.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 1f;
            o.vx += dx / d * Math.min(w, h) * 0.05f * dt;
            o.vy += dy / d * Math.min(w, h) * 0.05f * dt;
            o.x += o.vx * dt;
            o.y += o.vy * dt;
            if (o.x < o.r) { o.x = o.r; o.vx = Math.abs(o.vx) * 0.85f; }
            if (o.x > w - o.r) { o.x = w - o.r; o.vx = -Math.abs(o.vx) * 0.85f; }
            if (o.y < o.r) { o.y = o.r; o.vy = Math.abs(o.vy) * 0.85f; }
            if (o.y > h - o.r) { o.y = h - o.r; o.vy = -Math.abs(o.vy) * 0.85f; }
        }
        for (int i = 0; i < orbs.size(); i++) {
            for (int j = i + 1; j < orbs.size(); j++) {
                Orb a = orbs.get(i), b = orbs.get(j);
                float dx = b.x - a.x, dy = b.y - a.y;
                float d2 = dx * dx + dy * dy;
                float min = a.r + b.r;
                if (d2 > 0.01f && d2 < min * min) {
                    float d = (float) Math.sqrt(d2);
                    float nx = dx / d, ny = dy / d;
                    float rel = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny;
                    if (rel > 0) {           // elastic-ish impulse
                        a.vx -= rel * nx * 0.9f; a.vy -= rel * ny * 0.9f;
                        b.vx += rel * nx * 0.9f; b.vy += rel * ny * 0.9f;
                    }
                    float overlap = (min - d) * 0.5f;
                    a.x -= nx * overlap; a.y -= ny * overlap;
                    b.x += nx * overlap; b.y += ny * overlap;
                }
            }
        }
        float clock = android.os.SystemClock.uptimeMillis() / 1000f;
        for (Orb o : orbs) {
            o.vx *= 0.995f; o.vy *= 0.995f;
            float pulse = 1f + 0.04f * (float) Math.sin(clock * 2.2f + o.x * 0.01f);
            drawOrb(c, o.x, o.y, o.r * pulse, o.color, o.letter, globalAlpha, clock, o.logo);
        }
    }

    /** глобус, который продолжает крутиться за логотипами */
    private void drawBackGlobe(Canvas c, float alpha) {
        float rr = R * 0.92f;
        if (rr <= 0) return;
        float spin = (android.os.SystemClock.uptimeMillis() - t0) / 26f;
        globeBody.setShader(new RadialGradient(cx - rr * 0.3f, cy - rr * 0.35f, rr * 1.5f,
                new int[]{withAlpha(lighten(hueColor, 0.3f), (int) (90 * alpha)),
                        withAlpha(hueColor, (int) (70 * alpha)),
                        withAlpha(darken(hueColor, 0.5f), (int) (40 * alpha))},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        globeBody.setAlpha(255);
        c.drawCircle(cx, cy, rr, globeBody);
        rimPaint.setShader(null);
        rimPaint.setStrokeWidth(2f);
        for (int i = 0; i < 3; i++) {
            float srr = rr * (0.4f + 0.28f * i);
            rimPaint.setColor(Color.WHITE);
            rimPaint.setAlpha((int) ((18 + 22 * Math.abs((float) Math.sin(spin * 0.02f + i))) * alpha));
            RectF rf = new RectF(cx - srr, cy - srr * 0.34f, cx + srr, cy + srr * 0.34f);
            c.save();
            c.rotate((i % 2 == 0 ? spin : -spin * 1.2f), cx, cy);
            c.drawArc(rf, 0, 360, false, rimPaint);
            c.restore();
        }
    }

    private static int withAlpha(int c, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)),
                Color.red(c), Color.green(c), Color.blue(c));
    }

    private void drawOrb(Canvas c, float x, float y, float r, int color, char letter,
                         float alpha, float clock) {
        drawOrb(c, x, y, r, color, letter, alpha, clock, null);
    }

    private void drawOrb(Canvas c, float x, float y, float r, int color, char letter,
                         float alpha, float clock, android.graphics.Bitmap logo) {
        alpha = clamp01(alpha);
        int a8 = (int) (255 * alpha);
        orbPaint.setShader(new RadialGradient(x - r * 0.25f, y - r * 0.3f, r * 1.3f,
                new int[]{lighten(color, 0.5f), color, darken(color, 0.4f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        orbPaint.setAlpha(a8);
        c.drawCircle(x, y, r, orbPaint);
        orbPaint.setShader(null);

        glowPaint.setShader(null);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(r * 0.10f);
        glowPaint.setColor(color);
        glowPaint.setAlpha((int) (85 * alpha));
        glowPaint.setMaskFilter(new BlurMaskFilter(r * 0.35f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(x, y, r * 1.04f, glowPaint);

        if (logo != null) {
            int save = c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(x, y, r * 0.90f, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            discPaint.setAlpha((int) (255 * Math.min(1f, alpha)));
            c.drawCircle(x, y, r * 0.92f, discPaint);
            logoPaint.setAlpha((int) (255 * Math.min(1f, alpha)));
            float size = r * 1.55f;
            android.graphics.RectF dst = new android.graphics.RectF(
                    x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f);
            c.drawBitmap(logo, null, dst, logoPaint);
            c.restoreToCount(save);
            return;
        }
        letterPaint.setColor(pickLetterColor(color));
        letterPaint.setAlpha(a8);
        letterPaint.setTextSize(r * 1.12f);
        float ty = y - (letterPaint.descent() + letterPaint.ascent()) / 2f;
        c.drawText(String.valueOf(letter), x, ty, letterPaint);

        // mini specular dot
        highlightPaint.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.35f, r * 0.4f,
                new int[]{0x40FFFFFF, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        highlightPaint.setAlpha(a8);
        c.drawCircle(x - r * 0.3f, y - r * 0.35f, r * 0.4f, highlightPaint);
    }

    private int pickLetterColor(int brand) {
        double lum = 0.299 * Color.red(brand) + 0.587 * Color.green(brand) + 0.114 * Color.blue(brand);
        return lum > 140 ? 0xFF10131C : 0xFFFFFFFF;
    }

    private static int lighten(int col, float f) {
        return Color.rgb((int) (Color.red(col) + (255 - Color.red(col)) * f),
                (int) (Color.green(col) + (255 - Color.green(col)) * f),
                (int) (Color.blue(col) + (255 - Color.blue(col)) * f));
    }

    private static int darken(int col, float f) {
        return Color.rgb((int) (Color.red(col) * (1 - f)),
                (int) (Color.green(col) * (1 - f)),
                (int) (Color.blue(col) * (1 - f)));
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float clamp01(float t) { return t < 0 ? 0 : (t > 1 ? 1 : t); }
    private static float easeIn(float t) { return t * t; }
    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (t - 1) * (t - 1) * (t - 1) + c1 * (t - 1) * (t - 1);
    }
    private static float easeInOutSine(float t) {
        return -(float) Math.cos(Math.PI * t) / 2f + 0.5f;
    }
    private static float smoothstep(float t) { return t * t * (3 - 2 * t); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // раньше случайное касание (и синтетический тап от системы) обрывал
        // анимацию на середине — теперь сплэш всегда доигрывает
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        hueAnim.cancel();
        super.onDetachedFromWindow();
    }
}
