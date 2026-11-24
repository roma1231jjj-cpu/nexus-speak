package com.local.aivoice;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Ambient floating orbs behind the settings glass cards. */
public class FloatingOrbsView extends View {

    private static class Orb {
        float x, y, vx, vy, r;
        int color;
        android.graphics.Shader shader;
        android.graphics.Bitmap logo;
        final android.graphics.Matrix matrix = new android.graphics.Matrix();
    }

    private static final String[] LOGO_FILES = {
            "anthropic.png", "openai.png", "gemini.png", "deepseek.png",
            "kimi.jpg", "xai.png", "mistral.png", "qwen.png"};
    private android.graphics.Bitmap[] logos;
    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.RectF dst = new android.graphics.RectF();

    /** тусклые логотипы: на белой теме цветные пятна читались как мусор */
    private boolean dim = false;

    private final List<Orb> orbs = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private long last = -1;

    private static final int[] COLORS = {0xFFB57CFF, 0xFF5B8CFF, 0xFFFF5EC8, 0xFF10A37F, 0xFFD97757};

    public FloatingOrbsView(Context c, AttributeSet a) {
        super(c, a);
        dim = Ui.isLightTheme(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        logos = new android.graphics.Bitmap[LOGO_FILES.length];
        for (int i = 0; i < LOGO_FILES.length; i++) {
            try (java.io.InputStream in = c.getAssets().open("logos/" + LOGO_FILES[i])) {
                android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeStream(in);
                logos[i] = roundCrop(src);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = android.os.SystemClock.uptimeMillis();
        if (last < 0) {
            last = now;
            int w = getWidth(), h = getHeight();
            float base = Math.min(w, h);
            int count = Math.max(5, Math.min(7, logos == null ? 5 : logos.length));
            for (int i = 0; i < count; i++) {
                Orb o = new Orb();
                o.r = base * (0.10f + rnd.nextFloat() * 0.09f);
                o.x = rnd.nextFloat() * w;
                o.y = rnd.nextFloat() * h;
                float sp = base * 0.03f * (0.6f + rnd.nextFloat() * 0.8f);
                float a = rnd.nextFloat() * 2f * (float) Math.PI;
                o.vx = (float) Math.cos(a) * sp;
                o.vy = (float) Math.sin(a) * sp;
                o.color = COLORS[i % COLORS.length];
                if (logos != null && i < logos.length) o.logo = logos[i];
                orbs.add(o);
            }
        }
        float dt = Math.min(0.05f, (now - last) / 1000f);
        last = now;
        int w = getWidth(), h = getHeight();
        for (Orb o : orbs) {
            o.x += o.vx * dt;
            o.y += o.vy * dt;
            if (o.x < -o.r) o.x = w + o.r;
            if (o.x > w + o.r) o.x = -o.r;
            if (o.y < -o.r) o.y = h + o.r;
            if (o.y > h + o.r) o.y = -o.r;
            // gentle wander
            o.vx += (rnd.nextFloat() - 0.5f) * base(dt, w) * 0.02f;
            o.vy += (rnd.nextFloat() - 0.5f) * base(dt, w) * 0.02f;
            // градиент строим один раз на орб, двигаем матрицей: без аллокаций в кадре
            if (o.shader == null) {
                int a1 = dim ? 26 : 90, a2 = dim ? 10 : 35;
                o.shader = new RadialGradient(0f, 0f, o.r,
                        new int[]{withAlpha(o.color, a1), withAlpha(o.color, a2), 0x00000000},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            }
            android.graphics.Matrix mtx = o.matrix;
            mtx.setTranslate(o.x, o.y);
            o.shader.setLocalMatrix(mtx);
            paint.setShader(o.shader);
            canvas.drawCircle(o.x, o.y, o.r, paint);
            // настоящий логотип провайдера поверх свечения
            if (o.logo != null) {
                float side = o.r * 0.78f;
                dst.set(o.x - side / 2f, o.y - side / 2f, o.x + side / 2f, o.y + side / 2f);
                logoPaint.setAlpha(dim ? 16 : 70);
                canvas.drawBitmap(o.logo, null, dst, logoPaint);
            }
        }
        postInvalidateOnAnimation();
    }

    /** обрезаем логотип в круг: квадратные плашки в фоне выглядели мусором */
    private static android.graphics.Bitmap roundCrop(android.graphics.Bitmap src) {
        if (src == null) return null;
        int side = Math.min(src.getWidth(), src.getHeight());
        if (side <= 0) return src;
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                side, side, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.Path clip = new android.graphics.Path();
        clip.addCircle(side / 2f, side / 2f, side / 2f, android.graphics.Path.Direction.CW);
        c.clipPath(clip);
        android.graphics.Rect srcR = new android.graphics.Rect(
                (src.getWidth() - side) / 2, (src.getHeight() - side) / 2,
                (src.getWidth() - side) / 2 + side, (src.getHeight() - side) / 2 + side);
        c.drawBitmap(src, srcR, new android.graphics.Rect(0, 0, side, side), p);
        return out;
    }

    private float base(float dt, int w) { return w * dt; }

    private static int withAlpha(int c, int a) {
        return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c));
    }
}
