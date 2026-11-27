package com.local.aivoice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.provider.Settings;
import android.widget.Toast;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import androidx.core.content.FileProvider;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Update flow (two-stage, with progress and integrity checks):
 *  1. check()/checkOnly() — fetch update.json, compare versionCode, read "sha256".
 *     Neither method ever downloads anything.
 *  2. download() — fetch APK, hash it while writing, drop the file on mismatch.
 *  3. install() — verify the APK signature matches the installed app, then install.
 *
 * update.json must carry "sha256" (64 hex chars) of the APK it points to.
 * Updates without a checksum are refused: "обновление без контрольной суммы не устанавливаю".
 */
public final class UpdateChecker {

    public interface CheckCb { void onResult(String message, boolean newerAvailable, String apkUrl, String name); }
    public interface DownloadCb { void onProgress(int percent); void onDone(File apk, String name, String error); }

    private static final long CHECK_CACHE_MS = 120_000;
    private static final long MIN_APK_BYTES = 100_000;

    private static volatile boolean checking = false;
    private static volatile boolean downloading = false;
    private static long lastCheckAt = 0;
    private static String lastMsg = null;
    private static boolean lastNewer = false;
    private static String lastApk = null, lastName = null, lastSha = null;
    /** адрес и хеш, подтверждённые явной (не тихой) проверкой: только они разрешают download() */
    private static String approvedApk = null, approvedSha = null;

    /** Проверка по кнопке пользователя. Ничего не качает; разрешает последующее скачивание. */
    public static void check(final Context c, CheckCb cb) {
        runCheck(c, cb, true);
    }

    /**
     * Тихая проверка (открытие настроек, автообновление). Сообщает только о наличии версии:
     * download() после неё не запустится — 49 МБ никто не просил качать.
     */
    public static void checkOnly(final Context c, CheckCb cb) {
        runCheck(c, cb, false);
    }

    private static void runCheck(final Context c, final CheckCb cb, final boolean allowDownload) {
        // экран настроек пересоздаётся при смене темы и дёргает проверку снова —
        // без этого обновление качалось дважды
        synchronized (UpdateChecker.class) {
            if (checking) {
                // проверка уже идёт: раньше вызывающий не получал ответа вообще
                postCheckReply(cb, L.s("Уже проверяю обновление…"), false, null, null);
                return;
            }
            if (lastMsg != null && System.currentTimeMillis() - lastCheckAt < CHECK_CACHE_MS) {
                if (allowDownload && lastNewer && lastApk != null && isSha256(lastSha)) {
                    approvedApk = lastApk;
                    approvedSha = lastSha.toLowerCase(Locale.ROOT);
                }
                postCheckReply(cb, lastMsg, lastNewer, lastApk, lastName);
                return;
            }
            checking = true;
        }
        new Thread(() -> {
            try {
                OkHttpClient http = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS).build();
                String feed = SettingsStore.updateUrl(c);
                if (!isHttpsUrl(feed)) throw new Exception(L.s("адрес обновлений должен быть https"));
                Request req = new Request.Builder().url(feed).build();
                try (Response r = http.newCall(req).execute()) {
                    if (!r.isSuccessful() || r.body() == null)
                        throw new Exception("HTTP " + r.code());
                    JSONObject j = new JSONObject(r.body().string());
                    long remote = j.optLong("versionCode", -1);
                    String name = j.optString("versionName", "?");
                    String apk = j.optString("apkUrl", "").trim();
                    if (apk.isEmpty()) apk = j.optString("url", "").trim();
                    String sha = j.optString("sha256", "").trim();

                    long local;
                    String cur;
                    try {
                        PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
                        local = pi.versionCode;
                        cur = pi.versionName;
                    } catch (Exception e) {
                        local = -1;
                        cur = String.valueOf(local);
                    }
                    if (remote <= local) {
                        postCheck(cb, L.s("У тебя последняя версия (") + cur + ")",
                                false, null, null, null, allowDownload);
                    } else if (apk.isEmpty()) {
                        postCheck(cb, L.s("Новая версия ") + name
                                        + L.s(" есть, но в update.json нет ссылки на APK — обновление не предлагаю"),
                                false, null, null, null, allowDownload);
                    } else if (!isSha256(sha)) {
                        // пустой или битый хеш — не «просто предупреждение», а отказ
                        postCheck(cb, L.s("Новая версия ") + name
                                        + L.s(" есть, но в update.json нет корректной контрольной суммы — "
                                        + "обновление без контрольной суммы не устанавливаю"),
                                false, null, null, null, allowDownload);
                    } else {
                        postCheck(cb, L.s("Доступна новая версия ") + name,
                                true, apk, name, sha.toLowerCase(Locale.ROOT), allowDownload);
                    }
                }
            } catch (Exception e) {
                postCheck(cb, L.s("Не удалось проверить: ") + e, false, null, null, null, allowDownload);
            }
        }).start();
    }

    /**
     * Скачивание APK. Требует контрольную сумму: либо переданную явно, либо из проверки по кнопке.
     * Хеш считается по ходу записи, при несовпадении файл удаляется.
     */
    public static void download(final Context c, final String apkUrl, final String name,
                                final DownloadCb cb) {
        String approved;
        synchronized (UpdateChecker.class) {
            approved = (apkUrl != null && apkUrl.equals(approvedApk)) ? approvedSha : null;
        }
        if (approved == null) {
            postError(cb, name, L.s("Скачивание не запущено: обновление не подтверждено проверкой "
                    + "с контрольной суммой. Нажми «Проверить обновления»."));
            return;
        }
        download(c, apkUrl, name, approved, cb);
    }

    /** Скачивание с явно известной контрольной суммой (hex SHA-256 из update.json). */
    public static void download(final Context c, final String apkUrl, final String name,
                                final String expectedSha256, final DownloadCb cb) {
        final String expected = expectedSha256 == null
                ? "" : expectedSha256.trim().toLowerCase(Locale.ROOT);
        if (!isSha256(expected)) {
            postError(cb, name, L.s("Обновление без контрольной суммы не устанавливаю: "
                    + "в update.json нет поля sha256"));
            return;
        }
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            postError(cb, name, L.s("Нет ссылки на APK в update.json"));
            return;
        }
        if (!isHttpsUrl(apkUrl)) {
            postError(cb, name, L.s("Ссылка на APK должна быть https — обновление отменено"));
            return;
        }
        synchronized (UpdateChecker.class) {
            if (downloading) {
                postError(cb, name, L.s("Скачивание уже идёт — дождись его окончания"));
                return;
            }
            downloading = true;
        }
        new Thread(() -> {
            File out = new File(c.getFilesDir(), "update.apk");
            File tmp = new File(c.getFilesDir(), "update.tmp");
            try {
                OkHttpClient http = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS).build();
                Request req = new Request.Builder().url(apkUrl).build();
                try (Response r = http.newCall(req).execute()) {
                    if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
                    long total = r.body().contentLength();
                    // Пишем во временный файл: прошлая рабочая копия не пропадёт,
                    // если сеть оборвётся или хеш не совпадёт.
                    if (tmp.exists() && !tmp.delete()) throw new Exception("не удалось убрать прошлый временный файл");
                    InputStream in = r.body().byteStream();
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    long done = 0;
                    int lastPct = -1;
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                            md.update(buf, 0, n);
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
                    String actual = hex(md.digest());
                    if (!expected.equals(actual)) {
                        deleteQuietly(tmp);
                        postDone(cb, null, name, L.s("Контрольная сумма не совпала — файл удалён, "
                                + "обновление отменено (ожидалось ") + shortHash(expected)
                                + L.s(", получено ") + shortHash(actual) + ")");
                        return;
                    }
                    if (tmp.length() < MIN_APK_BYTES) {
                        deleteQuietly(tmp);
                        postDone(cb, null, name, L.s("Скачанный файл слишком мал — обновление отменено"));
                        return;
                    }
                    if (out.exists() && !out.delete()) throw new Exception("не удалось заменить прошлый APK");
                    if (!tmp.renameTo(out)) throw new Exception("не удалось сохранить скачанный APK");
                    postDone(cb, out, name, null);
                }
            } catch (Exception e) {
                deleteQuietly(tmp);
                postDone(cb, null, name, String.valueOf(e));
            } finally {
                // Без сброса флага следующее скачивание после ошибки/успеха
                // молча не запускалось до перезапуска приложения.
                synchronized (UpdateChecker.class) { downloading = false; }
            }
        }).start();
    }

    /** Prompt the user to install a downloaded APK. */
    /** файл обновления, который ждёт разрешения «установка из неизвестных источников» */
    public static final String PENDING = "pendingInstall";

    /** итог сверки подписи APK с установленным приложением */
    private enum Sig { MATCH, MISMATCH, UNKNOWN }

    /**
     * Установка APK. Раньше здесь был один FileProvider + ACTION_VIEW, и на Honor
     * это падало (URI не отдавался установщику). Теперь: проверка подписи, проверка
     * разрешения, PackageInstaller-сессия, и только если она недоступна — старый путь.
     */
    public static void install(Context c, File apk) {
        try {
            if (apk == null || !apk.exists() || apk.length() < MIN_APK_BYTES) {
                Toast.makeText(c, L.s("Файл обновления не найден — скачай ещё раз"),
                        Toast.LENGTH_LONG).show();
                return;
            }
            Sig sig = verifySignature(c, apk);
            if (sig == Sig.UNKNOWN) sig = verifySignatureCopy(c, apk);
            if (sig == Sig.MISMATCH) {
                deleteQuietly(apk);
                SettingsStore.p(c).edit().remove(PENDING).apply();
                Toast.makeText(c, L.s("Подпись APK не совпадает с подписью установленного "
                        + "приложения или это другое приложение — установка отменена, файл удалён"),
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (sig == Sig.UNKNOWN) {
                Toast.makeText(c, L.s("Не удалось проверить подпись APK — установка отменена, "
                        + "скачай обновление ещё раз"), Toast.LENGTH_LONG).show();
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
        if (!f.exists() || f.length() < MIN_APK_BYTES) {
            SettingsStore.p(c).edit().remove(PENDING).apply();
            return false;
        }
        Sig sig = verifySignature(c, f);
        if (sig == Sig.UNKNOWN) sig = verifySignatureCopy(c, f);
        if (sig != Sig.MATCH) {
            if (sig == Sig.MISMATCH) deleteQuietly(f);
            SettingsStore.p(c).edit().remove(PENDING).apply();
            Toast.makeText(c, L.s("Обновление отменено: подпись APK не совпадает с приложением "
                    + "или не читается"), Toast.LENGTH_LONG).show();
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= 26
                && !c.getPackageManager().canRequestPackageInstalls()) return false;
        SettingsStore.p(c).edit().remove(PENDING).apply();
        doInstall(c, f);
        return true;
    }

    /**
     * Сверка подписи: SHA-256 сертификата(ов) скачанного APK должно совпасть с подписью
     * установленного приложения (история подписей — на случай ротации ключа).
     */
    private static Sig verifySignature(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo arch = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (mine == null || arch == null) return Sig.UNKNOWN;
            if (arch.packageName != null && !c.getPackageName().equals(arch.packageName))
                return Sig.MISMATCH;
            Set<String> mineHashes = certHashes(mine.signingInfo, true);
            Set<String> apkHashes = certHashes(arch.signingInfo, false);
            if (mineHashes.isEmpty() || apkHashes.isEmpty()) return Sig.UNKNOWN;
            return mineHashes.containsAll(apkHashes) ? Sig.MATCH : Sig.MISMATCH;
        } catch (Throwable t) {
            return Sig.UNKNOWN;
        }
    }

    /**
     * На части прошивок PackageManager не читает архив из приватной папки. Тогда копируем APK
     * во внешнюю папку приложения (её PM видит всегда) и проверяем подпись ещё раз.
     */
    private static Sig verifySignatureCopy(Context c, File apk) {
        File dir = c.getExternalFilesDir("verify");
        if (dir == null) return Sig.UNKNOWN;
        File copy = new File(dir, "update-verify.apk");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return Sig.UNKNOWN;
            try (InputStream in = new java.io.FileInputStream(apk);
                 FileOutputStream out = new FileOutputStream(copy)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return verifySignature(c, copy);
        } catch (Throwable t) {
            return Sig.UNKNOWN;
        } finally {
            deleteQuietly(copy);
        }
    }

    private static Set<String> certHashes(SigningInfo info, boolean installed) {
        Set<String> out = new HashSet<>();
        if (info == null) return out;
        Signature[] sigs;
        if (info.hasMultipleSigners()) {
            sigs = info.getApkContentsSigners();
        } else {
            sigs = installed ? info.getSigningCertificateHistory() : info.getApkContentsSigners();
            if (sigs == null || sigs.length == 0) sigs = info.getApkContentsSigners();
        }
        if (sigs == null) return out;
        for (Signature s : sigs) {
            if (s == null) continue;
            try {
                out.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
            } catch (Throwable ignored) {
            }
        }
        return out;
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

    private static void postCheck(CheckCb cb, String msg, boolean newer, String apk, String name,
                                  String sha, boolean allowDownload) {
        synchronized (UpdateChecker.class) {
            checking = false;
            lastCheckAt = System.currentTimeMillis();
            lastMsg = msg;
            lastNewer = newer;
            lastApk = apk;
            lastName = name;
            lastSha = sha;
            if (allowDownload && newer && apk != null && isSha256(sha)) {
                approvedApk = apk;
                approvedSha = sha;
            } else if (!newer) {
                approvedApk = null;
                approvedSha = null;
            }
        }
        postCheckReply(cb, msg, newer, apk, name);
    }

    private static void postCheckReply(CheckCb cb, String msg, boolean newer, String apk, String name) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onResult(msg, newer, apk, name));
    }

    private static void postProgress(DownloadCb cb, int pct) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onProgress(pct));
    }

    private static void postDone(DownloadCb cb, File apk, String name, String err) {
        synchronized (UpdateChecker.class) { downloading = false; }
        new Handler(Looper.getMainLooper()).post(() -> cb.onDone(apk, name, err));
    }

    private static void postError(DownloadCb cb, String name, String err) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onDone(null, name, err));
    }

    /**
     * Обновление скачивается только по https. http и userinfo-трюк
     * (https://good@evil) отсекаются до сетевого запроса; host обязателен.
     */
    private static boolean isHttpsUrl(String raw) {
        try {
            Uri u = Uri.parse(raw == null ? "" : raw.trim());
            return "https".equalsIgnoreCase(u.getScheme())
                    && u.getHost() != null && !u.getHost().isEmpty()
                    && u.getUserInfo() == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSha256(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.length() != 64) return false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String shortHash(String h) {
        if (h == null || h.length() < 16) return String.valueOf(h);
        return h.substring(0, 8) + "…" + h.substring(h.length() - 8);
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private UpdateChecker() {}
}
