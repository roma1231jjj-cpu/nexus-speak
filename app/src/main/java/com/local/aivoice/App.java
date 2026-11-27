package com.local.aivoice;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class App extends Application {
    /** Android держит слушателя слабой ссылкой — без поля он умирает после GC */
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    @Override
    public void onCreate() {
        super.onCreate();
        try { L.init(this); } catch (Throwable ignored) {}
        // страховка: если прошлый запуск замьютил медиапоток и умер — вернуть звук
        try { BeepMuter.unmute(this); } catch (Throwable ignored) {}
        try { Backup.restoreIfNeeded(this); } catch (Throwable ignored) {}
        // один раз переводим голос на живой Edge-нейроголос: системный звучал роботом
        try { CloudTts.probeAsync(this); } catch (Throwable ignored) {}
        // прогреваем движок распознавания, иначе голосовая панель
        // начинает слушать только через 2–3 секунды после запуска
        try { Warm.start(this); } catch (Throwable ignored) {}
        // проверка «есть ли движок озвучки» раньше делалась синхронно в настройках
        // и вешала экран на 3 секунды — теперь один раз при старте, в фоне
        try { TtsProbe.start(this); } catch (Throwable ignored) {}
        // окно «ещё слушаю» после ответа было 20 секунд — оставляем 8, но
        // только один раз: если человек сам выбрал своё значение, не трогаем
        try {
            android.content.SharedPreferences sp = SettingsStore.p(this);
            if (!sp.getBoolean("talkWindowMigrated", false)) {
                int cur = sp.getInt("talkWindow", 20);
                sp.edit().putBoolean("talkWindowMigrated", true)
                        .putInt("talkWindow", cur == 20 ? 8 : cur).apply();
            }
        } catch (Throwable ignored) {}
        try {
            android.content.SharedPreferences sp = SettingsStore.p(this);
            if (!sp.getBoolean("voiceEdgeDefault", false)) {
                String cur = sp.getString("ttsCloud", "");
                android.content.SharedPreferences.Editor e = sp.edit()
                        .putBoolean("voiceEdgeDefault", true);
                if (cur.isEmpty() || CloudTts.FREE.equals(cur)) {
                    e.putString("ttsCloud", CloudTts.EDGE).putString("ttsCloudVoice", "");
                }
                e.apply();
            }
        } catch (Throwable ignored) {}
        // любое изменение настроек -> отложенная копия (переживает обновление и переустановку)
        try {
            prefListener = (sp, k) -> scheduleBackup();
            SettingsStore.p(this).registerOnSharedPreferenceChangeListener(prefListener);
        } catch (Throwable ignored) {}
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String log = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                        + " thread=" + t.getName() + "\n" + sw + "\n\n";
                File f = new File(getFilesDir(), "crash.log");
                PrintWriter pw = new PrintWriter(new java.io.FileOutputStream(f, true));
                pw.append(log);
                pw.close();
                copyToDownloads(log);
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    Toast.makeText(this, L.s("Нексус упал: ") + e, Toast.LENGTH_LONG).show();
                }
            } catch (Exception ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private final android.os.Handler bg = new android.os.Handler(android.os.Looper.getMainLooper());
    private long lastBackupAt = 0L;
    private final Runnable doBackup = this::backupNow;

    private void backupNow() {
        // Настройки меняются пачками (тема, голос, провайдер). Писать файл в
        // «Загрузки» на каждое изменение — лишний износ и риск показать
        // недописанный JSON; держим не чаще одного раза в минуту.
        long now = System.currentTimeMillis();
        if (now - lastBackupAt < 60_000L) {
            bg.postDelayed(doBackup, 60_000L - (now - lastBackupAt));
            return;
        }
        lastBackupAt = now;
        try { Backup.save(this); } catch (Throwable ignored) {}
    }

    private void scheduleBackup() {
        bg.removeCallbacks(doBackup);
        bg.postDelayed(doBackup, 10_000);
    }

    private void copyToDownloads(String text) {
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,
                    "NexusCrash_" + System.currentTimeMillis() + ".txt");
            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri != null) {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unused")
    private static void unused(SharedPreferences s) {
    }
}
