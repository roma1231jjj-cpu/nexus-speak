package com.local.aivoice;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

public class SplashActivity extends AppCompatActivity {

    private static final String SKIP_KEY = "splashShown";
    private SplashView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        Ui.applyTheme(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        FrameLayout root = new FrameLayout(this);
        if (BuildConfig.SAFE_MODE) {
            // аварийный режим: анимацию не запускаем совсем, иначе она живёт вхолостую
            setContentView(root);
            root.postDelayed(this::goNext, 250);
            return;
        }
        try {
            view = new SplashView(this, null);
            view.setDoneListener(this::goNext);
            root.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            view = null; // splash failed — skip straight to the app
        }

        // 2.5D wireframe globe on top of the brand-orb animation
        if (view == null) {
            setContentView(root);
            root.postDelayed(this::goNext, 300);
            return;
        }
        final GlobeView globe = new GlobeView(this, null);
        globe.setBrandDots(true);
        globe.setSpinSpeed(0.62f);
        globe.setAlpha(0f);
        int accent = Ui.color(this, R.attr.nxPrimary);
        globe.setColor(accent);
        globe.setGlowColor((accent & 0x00FFFFFF) | 0xB0000000);
        int size = Math.round(getResources().getDisplayMetrics().widthPixels * 0.62f);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(size, size);
        glp.gravity = android.view.Gravity.CENTER;
        root.addView(globe, glp);
        root.postDelayed(() -> globe.animate().alpha(0.95f).setDuration(600).start(), 1500);
        root.postDelayed(() -> globe.animate().alpha(0f).setDuration(700).start(), 5100);
        if (SettingsStore.amoled(this)) root.setBackgroundColor(android.graphics.Color.BLACK);
        setContentView(root);

        // тап по экрану — сразу дальше; и жёсткий потолок 2.6 с вместо 5.5
        root.setOnClickListener(v -> goNext());
        root.postDelayed(this::goNext, 2600);
        long last = getSharedPreferences("settings", 0).getLong("lastSplash", 0L);
        if (last > 0 && System.currentTimeMillis() - last < 5L * 60L * 1000L) {
            // повторный запуск в течение пяти минут — без длинной заставки
            root.postDelayed(this::goNext, 350);
        }
    }

    public static void applyTheme(AppCompatActivity act) {
        int mode = Ui.nightMode(act);
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
    }

    private void applyTheme() {
        applyTheme(this);
    }

    private void goNext() {
        if (isFinishing()) return;
        getSharedPreferences("settings", 0).edit()
                .putBoolean(SKIP_KEY, true)
                .putLong("lastSplash", System.currentTimeMillis())
                .apply();
        // иконка должна открывать главный экран, а не сразу чат
        Intent i = new Intent(this, MainActivity.class);
        startActivity(i);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
