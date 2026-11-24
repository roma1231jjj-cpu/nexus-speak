package com.local.aivoice;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

public final class Ui {

    public static final String THEME_MIDNIGHT = "midnight";
    public static final String THEME_FROST = "frost";
    public static final String THEME_GRAPHITE = "graphite";

    public static boolean isAmoled(Activity a) {
        return SettingsStore.amoled(a);
    }

    public static String themeKey(Context c) {
        String v = SettingsStore.p(c).getString("theme", "");
        if (THEME_MIDNIGHT.equals(v) || THEME_FROST.equals(v) || THEME_GRAPHITE.equals(v)) return v;
        int legacy = SettingsStore.p(c).getInt("themeMode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (legacy == AppCompatDelegate.MODE_NIGHT_NO) return THEME_FROST;
        if (legacy == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return ui == Configuration.UI_MODE_NIGHT_YES ? THEME_MIDNIGHT : THEME_FROST;
        }
        return THEME_MIDNIGHT;
    }

    public static int nightMode(Context c) {
        if (SettingsStore.amoled(c)) return AppCompatDelegate.MODE_NIGHT_YES;
        return THEME_FROST.equals(themeKey(c))
                ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static void setTheme(Context c, String key) {
        String k = THEME_MIDNIGHT;
        if (THEME_FROST.equals(key)) k = THEME_FROST;
        else if (THEME_GRAPHITE.equals(key)) k = THEME_GRAPHITE;
        int mode = THEME_FROST.equals(k)
                ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
        SettingsStore.p(c).edit().putString("theme", k).putInt("themeMode", mode).apply();
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        } else if (c instanceof Activity) {
            ((Activity) c).recreate();
        }
    }

    public static int color(Context c, int attr) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) return ContextCompat.getColor(c, tv.resourceId);
            return tv.data;
        }
        return Color.WHITE;
    }

    /** светлая ли фоновая поверхность темы — по яркости nxBg */
    public static boolean isLightTheme(Context c) {
        int bg = color(c, R.attr.nxBg);
        double l = 0.2126 * Color.red(bg) + 0.7152 * Color.green(bg) + 0.0722 * Color.blue(bg);
        return l > 150;
    }

    public static boolean isNight(Activity a) {
        int mode = a.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void applyTheme(Activity a) {
        applyTheme(a, false);
    }

    public static void applyTheme(Activity a, boolean translucent) {
        int mode = nightMode(a);
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode);
        }
        a.setTheme(styleFor(a, translucent));
    }

    private static int styleFor(Activity a, boolean translucent) {
        String k = themeKey(a);
        if (SettingsStore.amoled(a)) return R.style.Theme_AIAssistant_Amoled;
        if (translucent) {
            if (THEME_FROST.equals(k)) return R.style.Theme_AIAssistant_Frost_Assistant;
            if (THEME_GRAPHITE.equals(k)) return R.style.Theme_AIAssistant_Graphite_Assistant;
            return R.style.Theme_AIAssistant_Midnight_Assistant;
        }
        if (THEME_FROST.equals(k)) return R.style.Theme_AIAssistant_Frost;
        if (THEME_GRAPHITE.equals(k)) return R.style.Theme_AIAssistant_Graphite;
        return R.style.Theme_AIAssistant_Midnight;
    }

    public static void applyAmoled(Activity a) {
        if (!isAmoled(a)) return;
        try {
            View root = a.getWindow().getDecorView().findViewById(android.R.id.content);
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) {
                    g.getChildAt(i).setBackgroundColor(Color.BLACK);
                }
            }
            if (root != null) repaint(root);
            View orbs = a.findViewById(R.id.orbsView);
            if (orbs != null) orbs.setVisibility(View.GONE);
            View sheet = a.findViewById(R.id.sheet);
            if (sheet != null) sheet.setBackgroundColor(0xFF08080C);
            a.getWindow().setStatusBarColor(Color.BLACK);
            a.getWindow().setNavigationBarColor(Color.BLACK);
        } catch (Throwable ignored) {
        }
    }

    private static void repaint(View v) {
        if ("glassCard".equals(v.getTag())) {
            v.setBackgroundResource(R.drawable.glass_card_amoled);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) repaint(g.getChildAt(i));
        }
    }

    public static void fadeIn(Activity a) {
        try {
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) {
        }
    }

    private Ui() {}
}
