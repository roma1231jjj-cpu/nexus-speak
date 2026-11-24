package com.local.aivoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import android.widget.Toast;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import androidx.core.content.FileProvider;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Update flow (two-stage, with progress):
 *  1. check()  — fetch update.json, compare versionCode, report (no download).
 *  2. download() — fetch APK, report percent progress, then prompt install.
 */
public final class UpdateChecker {

    public interface CheckCb { void onResult(String message, boolean newerAvailable, String apkUrl, String name); }
    public interface DownloadCb { void onProgress(int percent); void onDone(File apk, String name, String error); }

    private static volatile boolean checking = false;
    private static volatile boolean downloading = false;
    private static long lastCheckAt = 0;
    private static String lastMsg = null;
    private static boolean lastNewer = false;
    private static String lastApk = null, lastName = null;

    /** Compare versions only. Never downloads. */
    public static void check(final Context c, CheckCb cb) {
        // экран настроек пересоздаётся при смене темы и дёргает проверку снова —
        // без этого обновление качалось дважды
        synchronized (UpdateChecker.class) {
            if (checking) {
                // проверка уже идёт: раньше вызывающий не получал ответа вообще
                new Handler(Looper.getMainLooper())
                        .post(() -> cb.onResult(L.s("Уже проверяю обновление…"), false, null, null));
                return;
            }
            if (lastMsg != null && System.currentTimeMillis() - lastCheckAt < 120_000) {
                cb.onResult(lastMsg, lastNewer, lastApk, lastName);
                return;
            }
            checking = true;
        }
        new Thread(() -> {
            try {
                OkHttpClient http = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS).build();
                Request req = new Request.Builder().url(SettingsStore.updateUrl(c)).build();
                try (Response r = http.newCall(req).execute()) {
                    if (!r.isSuccessful() || r.body() == null)
                        throw new Exception("HTTP " + r.code());
                    JSONObject j = new JSONObject(r.body().string());
                    long remote = j.optLong("versionCode", -1);
                    String name = j.optString("versionName", "?");
                    String apk = j.optString("apkUrl", "");
                    long local;
                    try {
                        PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                        local = pi.versionCode;
                    } catch (Exception e) {
                        local = -1;
                    }
                    if (remote <= local) {
                        String cur;
                        try {
                            cur = c.getPackageManager()
                                    .getPackageInfo(c.getPackageName(), 0).versionName;
                        } catch (Exception e) {
                            cur = String.valueOf(local);
                        }
                        postCheck(cb, L.s("У тебя последняя версия (") + cur + ")", false, null, null);
                    } else {
                        postCheck(cb, L.s("Доступна новая версия ") + name, true, apk, name);
                    }
                }
            } catch (Exception e) {
                postCheck(cb, L.s("Не удалось проверить: ") + e, false, null, null);
            }
        }).start();
    }

    /** Download APK with percent progress, then onDone. */
    public static void download(final Context c, final String apkUrl, final String name,
                                final DownloadCb cb) {
        synchronized (UpdateChecker.class) {
            if (downloading) return;
            downloading = true;
        }
        new Thread(() -> {
            try {
                OkHttpClient http = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS).build();
                Request req = new Request.Builder().url(apkUrl).build();
                try (Response r = http.newCall(req).execute()) {
                    if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
                    long total = r.body().contentLength();
                    File out = new File(c.getFilesDir(), "update.apk");
                    InputStream in = r.body().byteStream();
                    long done = 0;
                    int lastPct = -1;
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                int pct = (int) (done * 100 / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    postProgress(cb, pct);
                                }
                            }
                        }
                    }
                    postDone(cb, out, name, null);
                }
            } catch (Exception e) {
                postDone(cb, null, name, String.valueOf(e));
            }
        }).start();
    }

    /** Prompt the user to install a downloaded APK. */
    /** файл обновления, который ждёт разрешения «установка из неизвестных источников» */
    public static final String PENDING = "pendingInstall";

    /**
     * Установка APK. Раньше здесь был один FileProvider + ACTION_VIEW, и на Honor
     * это падало (URI не отдавался установщику). Теперь: проверка разрешения,
     * PackageInstaller-сессия, и только если она недоступна — старый путь.
     */
    public static void install(Context c, File apk) {
        try {
            if (apk == null || !apk.exists() || apk.length() < 100000) {
                Toast.makeText(c, L.s("Файл обновления не найден — скачай ещё раз"),
                        Toast.LENGTH_LONG).show();
                return;
            }
            SettingsStore.p(c).edit().putString(PENDING, apk.getAbsolutePath()).apply();

            if (android.os.Build.VERSION.SDK_INT >= 26
                    && !c.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(c, L.s("Разреши Nexus Speak установку приложений — и обновление ")
                        + L.s("поставится само"), Toast.LENGTH_LONG).show();
                try {
                    c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + c.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable t) {
                    c.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return;
            }
            doInstall(c, apk);
        } catch (Throwable t) {
            Log.crash(c, t);
            Toast.makeText(c, L.s("Не смог открыть установщик: ") + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** повторная попытка после выдачи разрешения */
    public static boolean retryPending(Context c) {
        String path = SettingsStore.p(c).getString(PENDING, "");
        if (path == null || path.isEmpty()) return false;
        File f = new File(path);
        if (!f.exists()) {
            SettingsStore.p(c).edit().remove(PENDING).apply();
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= 26
                && !c.getPackageManager().canRequestPackageInstalls()) return false;
        SettingsStore.p(c).edit().remove(PENDING).apply();
        doInstall(c, f);
        return true;
    }

    private static void doInstall(Context c, File apk) {
        // 1) современный путь: сессия PackageInstaller (не нужны content:// и гранты)
        if (android.os.Build.VERSION.SDK_INT >= 26 && sessionInstall(c, apk)) return;
        // 2) запасной: старый intent через FileProvider
        try {
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".update", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Log.crash(c, t);
            notifyFallback(c, apk);
        }
    }

    private static boolean sessionInstall(Context c, File apk) {
        try {
            android.content.pm.PackageInstaller installer =
                    c.getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = installer.createSession(params);

            android.content.pm.PackageInstaller.Session session = installer.openSession(id);
            try (InputStream in = new java.io.FileInputStream(apk);
                 java.io.OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(c, InstallResultReceiver.class)
                    .setAction("com.local.aivoice.INSTALL_RESULT");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(c, id, result,
                    android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pi.getIntentSender());
            session.close();
            return true;
        } catch (Throwable t) {
            Log.crash(c, t);
            return false;
        }
    }

    private static void notifyFallback(Context c, File apk) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel("update") == null) {
            nm.createNotificationChannel(new NotificationChannel("update",
                    L.s("Обновления Нексуса"), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(c, c.getPackageName() + ".update", apk);
        } catch (Throwable t) {
            return;
        }
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(c, 9, open,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(L.s("Обновление Нексуса готово"))
                .setContentText(L.s("Нажми, чтобы установить"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(77, n);
    }

    private static void postCheck(CheckCb cb, String msg, boolean newer, String apk, String name) {
        synchronized (UpdateChecker.class) {
            checking = false;
            lastCheckAt = System.currentTimeMillis();
            lastMsg = msg;
            lastNewer = newer;
            lastApk = apk;
            lastName = name;
        }
        new Handler(Looper.getMainLooper()).post(() -> cb.onResult(msg, newer, apk, name));
    }

    private static void postProgress(DownloadCb cb, int pct) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onProgress(pct));
    }

    private static void postDone(DownloadCb cb, File apk, String name, String err) {
        synchronized (UpdateChecker.class) { downloading = false; }
        new Handler(Looper.getMainLooper()).post(() -> cb.onDone(apk, name, err));
    }

    private UpdateChecker() {}
}
