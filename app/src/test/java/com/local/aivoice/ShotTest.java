package com.local.aivoice;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.View.MeasureSpec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

/** Снимает главный экран в английской графитовой теме для README. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "en-rUS-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShotTest {

    @Test
    public void mainGraphite() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("theme", "graphite")
                .putString("lang", "en")
                .apply();
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);

        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Thread.sleep(2600);
        ShadowLooper.idleMainLooper();

        View root = activity.getWindow().getDecorView();
        root.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(2340, MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2340);
        Bitmap bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(0xFF08080C);
        root.draw(canvas);

        File dir = new File(System.getProperty("user.home"), "speak-shots");
        dir.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(dir, "en-main-graphite.png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        System.out.println("SHOT en-main-graphite.png");
    }
}