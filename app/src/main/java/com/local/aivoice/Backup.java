package com.local.aivoice;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Резервная копия настроек.
 *
 * Две копии:
 *  - полная приватная — files/settings-backup.json (внутри приложения, переживает обновление,
 *    но исчезает вместе с приложением, поэтому не единственный путь восстановления);
 *  - публичная — «Загрузки/Nexus Speak/nexus-settings-backup.json»: без API-ключей и без
 *    личных данных (userInfo, callGreeting), чтобы файл можно было показать/переслать.
 *
 * Восстановление — через SAF (ACTION_OPEN_DOCUMENT): пользователь сам выбирает JSON или ZIP
 * после переустановки, файл читается через ContentResolver.openInputStream. Автоматическое
 * восстановление при старте пробует приватную копию и ранее выбранный документ.
 */
public final class Backup {

    private static final String FILE = "settings-backup.json";
    private static final String PUBLIC_NAME = "nexus-settings-backup.json";
    private static final String PICK_MIME = "*/*";
    private static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024;
    /** ключи, которые нельзя выносить за пределы приложения */
    private static boolean secret(String k) {
        String l = k.toLowerCase(Locale.ROOT);
        return l.contains("key") || l.contains("token") || l.contains("secret")
                || l.equals("sessions") || l.startsWith("spend_");
    }

    /** ключи с личными данными: остаются только в приватной копии, в «Загрузки» не уходят */
    private static boolean personal(String k) {
        String l = k.toLowerCase(Locale.ROOT);
        return l.equals("userinfo") || l.equals("callgreeting") || l.equals("history")
                || l.equals("chats") || l.equals("backupuri") || l.equals("pendinginstall");
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
                if (!secret(e.getKey()) && !personal(e.getKey())) safe.put(e.getKey(), val);
            }
            // внутренняя копия (переживает обновление) — полная, она приватная
            try (FileOutputStream fos = new FileOutputStream(new File(c.getFilesDir(), FILE))) {
                fos.write(all.toString().getBytes(StandardCharsets.UTF_8));
            }
            // копия в «Загрузках» — без ключей и без личных данных
            savePublic(c, safe.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void savePublic(Context c, String json) {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, PUBLIC_NAME);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Nexus Speak");
            // перезаписываем один файл: раньше каждая правка создавала новый дубль
            try {
                c.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{PUBLIC_NAME, "Download/Nexus Speak%"});
            } catch (Throwable ignored) {
            }
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return;
            try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                if (os != null) os.write(json.getBytes(StandardCharsets.UTF_8));
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

    /**
     * Диалог выбора файла копии. Вызывать из Activity через
     * registerForActivityResult(StartActivityForResult) / startActivityForResult,
     * затем результат отдать в {@link #restoreFrom(Context, Uri)}.
     */
    public static Intent pickFileIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(PICK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/json", "text/plain", "application/octet-stream"});
        return i;
    }

    /** Автовосстановление при старте: приватная копия, затем ранее выбранный документ. */
    public static void restoreNow(Context c) {
        try {
            if (!needsRestore(c)) return;
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists() && f.length() > 0) {
                try (InputStream in = new java.io.FileInputStream(f)) {
                    JSONObject o = parse(in);
                    if (o != null && apply(c, o) > 0) return;
                } catch (Throwable ignored) {
                    // битая приватная копия не должна мешать попытке из документа
                }
            }
            String saved = SettingsStore.p(c).getString("backupUri", "");
            if (saved != null && !saved.isEmpty()) {
                restoreFrom(c, Uri.parse(saved));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Восстановление из выбранного файла (SAF). Понимает JSON и ZIP с копией внутри.
     * Возвращает текст для пользователя; при ошибке настройки не трогает.
     */
    public static String restoreFrom(Context c, Uri uri) {
        if (uri == null) return L.s("Файл не выбран");
        JSONObject o;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return L.s("Не удалось открыть файл копии");
            o = parse(in);
        } catch (Throwable t) {
            return L.s("Не удалось прочитать файл копии: ") + t.getClass().getSimpleName();
        }
        if (o == null || o.length() == 0) return L.s("В файле нет настроек Nexus Speak");
        int n = apply(c, o);
        if (n <= 0) return L.s("В файле нет настроек Nexus Speak");
        rememberSource(c, uri);
        return L.s("Настройки восстановлены (") + n + L.s(" ключей)");
    }

    /** запомнить документ, чтобы следующая установка могла восстановиться сама */
    public static void rememberSource(Context c, Uri uri) {
        try {
            c.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        SettingsStore.p(c).edit().putString("backupUri", uri.toString()).apply();
    }

    /** читает JSON напрямую или достаёт *.json из ZIP; больше MAX_BACKUP_BYTES не берём */
    private static JSONObject parse(InputStream in) throws Exception {
        java.io.BufferedInputStream bin = new java.io.BufferedInputStream(in);
        bin.mark(2);
        int b1 = bin.read();
        int b2 = bin.read();
        bin.reset();
        if (b1 == 'P' && b2 == 'K') {
            try (ZipInputStream zip = new ZipInputStream(bin)) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String nm = e.getName().toLowerCase(Locale.ROOT);
                    if (!nm.endsWith(".json")) continue;
                    byte[] data = readLimited(zip, MAX_BACKUP_BYTES);
                    if (data == null) return null;
                    return new JSONObject(new String(data, StandardCharsets.UTF_8));
                }
            }
            return null;
        }
        byte[] data = readLimited(bin, MAX_BACKUP_BYTES);
        if (data == null) return null;
        String s = new String(data, StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) return null;
        return new JSONObject(s);
    }

    /** null, если файл больше лимита — копия настроек больше мегабайт не бывает */
    private static byte[] readLimited(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            if (bos.size() + r > limit) return null;
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    /** пишет настройки; возвращает число применённых ключей */
    private static int apply(Context c, JSONObject o) {
        try {
            SharedPreferences.Editor e = SettingsStore.p(c).edit();
            int n = 0;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.get(k);
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) e.putInt(k, (Integer) v);
                else if (v instanceof Long) e.putLong(k, (Long) v);
                else if (v == JSONObject.NULL) continue;
                else e.putString(k, String.valueOf(v));
                n++;
            }
            e.putBoolean("backupRestored", true);
            e.apply();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private Backup() {}
}
