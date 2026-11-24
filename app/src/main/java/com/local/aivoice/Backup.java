package com.local.aivoice;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * Копия настроек, которая переживает обновление приложения (и переустановку —
 * если включён доступ к Загрузкам). Пишется в files/settings-backup.json и,
 * начиная с Android 10, дополнительно в «Загрузки/Nexus Speak/backup.json».
 */
public final class Backup {

    private static final String FILE = "settings-backup.json";

    /** ключи, которые нельзя выносить за пределы приложения */
    private static boolean secret(String k) {
        String l = k.toLowerCase(java.util.Locale.ROOT);
        return l.contains("key") || l.contains("token") || l.contains("secret")
                || l.equals("sessions") || l.startsWith("spend_");
    }

    public static void save(Context c) {
        try {
            JSONObject all = new JSONObject();
            JSONObject safe = new JSONObject();
            for (Map.Entry<String, ?> e : SettingsStore.p(c).getAll().entrySet()) {
                Object v = e.getValue();
                Object val = (v instanceof Boolean || v instanceof Integer || v instanceof Long)
                        ? v : String.valueOf(v);
                all.put(e.getKey(), val);
                if (!secret(e.getKey())) safe.put(e.getKey(), val);
            }
            // внутренняя копия (переживает обновление) — полная, она приватная
            try (FileOutputStream fos = new FileOutputStream(new File(c.getFilesDir(), FILE))) {
                fos.write(all.toString().getBytes("UTF-8"));
            }
            // копия в «Загрузках» — без ключей, чтобы секреты не утекали
            saveToDownloads(c, safe.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void saveToDownloads(Context c, String json) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            ContentValues v = new ContentValues();
            final String name = "nexus-settings-backup.json";
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Nexus Speak");
            // перезаписываем один файл: раньше каждая правка создавала новый дубль
            try {
                c.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{name, "Download/Nexus Speak%"});
            } catch (Throwable ignored) {
            }
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return;
            try (java.io.OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                if (os != null) os.write(json.getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    /** восстанавливает настройки в фоне: файл и JSON не должны тормозить старт приложения */
    public static void restoreIfNeeded(final Context c) {
        if (!needsRestore(c)) return;
        new Thread(() -> restoreNow(c.getApplicationContext()), "backup-restore").start();
    }

    private static boolean needsRestore(Context c) {
        SharedPreferences p = SettingsStore.p(c);
        // восстановление — только один раз за установку: иначе стёртый пользователем
        // ключ возвращался из копии при каждом запуске
        if (p.getBoolean("backupRestored", false)) return false;
        return p.getString("url", "").isEmpty() && p.getString("key", "").isEmpty();
    }

    private static void restoreNow(Context c) {
        try {
            SharedPreferences p = SettingsStore.p(c);
            if (!p.getString("url", "").isEmpty() || !p.getString("key", "").isEmpty()) return;
            File f = new File(c.getFilesDir(), FILE);
            if (!f.exists() || f.length() == 0) return;
            String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor e = p.edit();
            boolean any = false;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.get(k);
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) e.putInt(k, (Integer) v);
                else if (v instanceof Long) e.putLong(k, (Long) v);
                else e.putString(k, String.valueOf(v));
                any = true;
            }
            e.putBoolean("backupRestored", true);
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    private Backup() {}
}
