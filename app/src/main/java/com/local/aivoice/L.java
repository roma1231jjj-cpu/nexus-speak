package com.local.aivoice;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

public final class L {

    /** Метка «не переводить»: пользовательские пузыри, названия диалогов, данные. */
    public static final Object KEEP = new Object();

    private static Map<String, String> en;
    private static Map<String, String> back;
    private static String current = "ru";

    private static final WeakHashMap<View, String> ORIGINALS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> HOOKS =
            new WeakHashMap<>();
    private static boolean hooked = false;

    public static void init(Context c) {
        try {
            current = SettingsStore.lang(c);
            load(c);
            hookLifecycle(c);
        } catch (Throwable ignored) {
        }
    }

    public static String lang(Context c) {
        return SettingsStore.lang(c);
    }

    public static void setLang(Context c, String code) {
        current = code == null ? "ru" : code;
        SettingsStore.setLang(c, current);
    }

    public static boolean isEn() {
        return "en".equals(current);
    }

    private static synchronized void load(Context c) {
        if (en != null && !en.isEmpty()) return;
        Map<String, String> m = new HashMap<>();
        try (InputStream in = c.getAssets().open("i18n_en.json")) {
            byte[] buf = new byte[65536];
            StringBuilder sb = new StringBuilder();
            int r;
            while ((r = in.read(buf)) > 0) sb.append(new String(buf, 0, r, StandardCharsets.UTF_8));
            JSONObject o = new JSONObject(sb.toString());
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, o.optString(k, k));
            }
        } catch (Throwable ignored) {
        }
        en = m;
        Map<String, String> b = new HashMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            String v = e.getValue();
            if (v != null && !v.isEmpty() && !b.containsKey(v)) b.put(v, e.getKey());
        }
        back = b;
    }

    /** Английский вариант русского оригинала. */
    private static String enOf(String ru) {
        if (ru == null || en == null) return null;
        return en.get(ru);
    }

    /** Русский оригинал для английской строки. */
    private static String ruOf(String txt) {
        if (txt == null || back == null) return null;
        return back.get(txt);
    }

    public static String s(String ru) {
        if (ru == null || !isEn() || en == null) return ru;
        String v = en.get(ru);
        return v == null ? ru : v;
    }

    public static String t(Context c, String ru) {
        if (ru == null) return null;
        load(c);
        current = SettingsStore.lang(c);
        return s(ru);
    }

    public static String t(Context c, String ru, Object... args) {
        String f = t(c, ru);
        try {
            return String.format(f, args);
        } catch (Throwable t) {
            return f;
        }
    }

    /** Помечает view как данные: текст не переводится и язык не подменяет его. */
    public static void keep(View v) {
        if (v != null) v.setTag(KEEP);
    }

    public static void attach(final Activity a) {
        if (a == null) return;
        try {
            current = SettingsStore.lang(a);
            load(a);
            hookLifecycle(a);
            final View root = a.getWindow().getDecorView();
            walk(root);
            ViewTreeObserver.OnGlobalLayoutListener l = () -> walk(root);
            HOOKS.put(a, l);
            root.getViewTreeObserver().addOnGlobalLayoutListener(l);
        } catch (Throwable ignored) {
        }
    }

    public static void detach(Activity a) {
        try {
            ViewTreeObserver.OnGlobalLayoutListener l = HOOKS.remove(a);
            if (l != null && a != null) {
                a.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(l);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void apply(Activity a) {
        if (a == null) return;
        try {
            current = SettingsStore.lang(a);
            load(a);
            walk(a.getWindow().getDecorView());
        } catch (Throwable ignored) {
        }
    }

    /** Снимает слушатель сам, без правок в каждой активити. */
    private static void hookLifecycle(Context c) {
        if (hooked) return;
        Context app = c.getApplicationContext();
        if (!(app instanceof Application)) return;
        hooked = true;
        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityDestroyed(Activity activity) {
                detach(activity);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle b) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle b) {
            }
        });
    }

    private static void walk(View v) {
        if (v == null) return;
        try {
            if (v.getTag() == KEEP) return;

            CharSequence cd = v.getContentDescription();
            if (cd != null) {
                String want = render(v, cd.toString());
                if (want != null && !want.contentEquals(cd)) v.setContentDescription(want);
            }
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                if (!(v instanceof EditText)) {
                    CharSequence txt = tv.getText();
                    if (txt != null && txt.length() > 0) {
                        String want = render(v, txt.toString());
                        if (want != null && !want.contentEquals(txt)) tv.setText(want);
                    }
                } else {
                    EditText et = (EditText) v;
                    CharSequence hint = et.getHint();
                    if (hint != null) {
                        String want = render(v, hint.toString());
                        if (want != null && !want.contentEquals(hint)) et.setHint(want);
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Сначала возвращает текст к русскому оригиналу, потом переводит по языку. */
    private static String render(View v, String now) {
        String base = toRu(v, now);
        if (!isEn()) return base;
        String tr = enOf(base);
        return tr == null ? base : tr;
    }

    /** Русский оригинал: текст мог быть поставлен уже переведённым. */
    private static String toRu(View v, String now) {
        String known = ORIGINALS.get(v);
        if (known != null) {
            if (now.equals(known)) return known;
            String knownEn = enOf(known);
            if (knownEn != null && now.equals(knownEn)) return known;
        }
        String ru = ruOf(now);
        if (ru != null) {
            ORIGINALS.put(v, ru);
            return ru;
        }
        ORIGINALS.put(v, now);
        return now;
    }

    private L() {
    }
}
