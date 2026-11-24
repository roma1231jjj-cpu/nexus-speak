package com.local.aivoice;

import android.content.Context;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Постоянное прослушивание слова «Нексус» через системное распознавание речи
 * с частичными результатами и самовосстановлением при сбоях.
 */
public class WakeService extends Service {
    /** Wake words are fixed (no user setting): «нексус», «некси», «nexus». */
    public static final String[] WAKE_WORDS = {"нэксус", "нексус", "нэкси", "некси", "нэкс", "некс", "nexus"};

    public static String wakeWord(android.content.Context c) {
        return WAKE_WORDS[0];
    }
    public static final String ACTION_PAUSE = "com.local.aivoice.PAUSE_WAKE";
    public static final String ACTION_RESUME = "com.local.aivoice.RESUME_WAKE";
    public static final String ACTION_START = "com.local.aivoice.START_WAKE";
    public static final String ACTION_STOP = "com.local.aivoice.STOP_WAKE";
    public static final String EXTRA_TEXT = "prequery";

    private static final String NOTIF_CH = "wake";

    /** true if the service is really alive right now (not just a stale pref) */
    public static boolean isRunningNow(Context c) {
        return runningFlag;
    }

    private static volatile boolean runningFlag = false;

    private void setRunningPref(boolean on) {
        SettingsStore.p(this).edit().putBoolean("wakeRunning", on).apply();
        try {
            android.service.quicksettings.TileService.requestListeningState(this,
                    new android.content.ComponentName(this, WakeTileService.class));
        } catch (Throwable ignored) {
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private android.os.PowerManager.WakeLock wakeLock;

    /** 10 минут на один захват: вечный wakelock не даёт телефону спать и злит систему */
    private static final long WAKELOCK_MS = 10 * 60 * 1000L;

    private final Runnable renewWakeLock = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            try {
                if (wakeLock != null) wakeLock.acquire(WAKELOCK_MS);
            } catch (Throwable ignored) {
            }
            main.postDelayed(this, WAKELOCK_MS - 60 * 1000L);
        }
    };

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Nexus:wake");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(WAKELOCK_MS);
            main.removeCallbacks(renewWakeLock);
            main.postDelayed(renewWakeLock, WAKELOCK_MS - 60 * 1000L);
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        main.removeCallbacks(renewWakeLock);
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }

    private SpeechRecognizer google;
    private volatile boolean recognizing = false;
    private AudioManager audioManager;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private android.content.BroadcastReceiver ctl;

    @Override
    public void onCreate() {
        super.onCreate();
        ctl = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i == null || i.getAction() == null) return;
                onStartCommand(i, 0, 0);
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(ACTION_PAUSE);
        f.addAction(ACTION_RESUME);
        try {
            androidx.core.content.ContextCompat.registerReceiver(this, ctl, f,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(a)) {
            // явное «Выключить» из шторки/плитки — это решение пользователя,
            // поэтому гасим и настройку, иначе прослушка поднимется сама
            runningFlag = false;
            setRunningPref(false);
            SettingsStore.p(this).edit().putBoolean("wakeEnabled", false).apply();
        } else {
            runningFlag = true;
            setRunningPref(true);
        }
        audioManager = getSystemService(AudioManager.class);
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(a)) {
            paused.set(true);
            stopListening();
            return START_STICKY;
        }
        if (ACTION_RESUME.equals(a)) {
            paused.set(false);
            main.postDelayed(this::listen, 80);
            return START_STICKY;
        }
        // START
        paused.set(false);
        if (!startForegroundSafe()) return START_NOT_STICKY;
        acquireWakeLock();
        running.set(true);
        main.postDelayed(this::listen, 120);
        return START_STICKY;
    }

    /** тип microphone обязателен на Android 14; без try/catch сервис роняет процесс */
    private boolean startForegroundSafe() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(44, notif(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(44, notif());
            }
            return true;
        } catch (Throwable t) {
            runningFlag = false;
            setRunningPref(false);
            SettingsStore.p(this).edit()
                    .putString("wakeErr", L.s("система не разрешила фоновую прослушку"))
                    .apply();
            try { stopSelf(); } catch (Throwable ignored) {}
            return false;
        }
    }

    @Override
    public void onDestroy() {
        try { if (ctl != null) unregisterReceiver(ctl); } catch (Throwable ignored) {}
        runningFlag = false;
        setRunningPref(false);
        running.set(false);
        stopListening();
        releaseWakeLock();
        detachAnchor();
        BeepMuter.unmute(this);
        super.onDestroy();
    }

    private android.app.Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel existing = nm.getNotificationChannel(NOTIF_CH);
        String chName = L.s("Нексус: wake-слово");
        if (existing == null) {
            NotificationChannel ch = new NotificationChannel(NOTIF_CH, chName,
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } else if (!chName.contentEquals(existing.getName())) {
            // имя канала создаётся один раз — иначе в шторке навсегда старый язык
            existing.setName(chName);
            nm.createNotificationChannel(existing);
        }
        Intent stop = new Intent(this, WakeService.class).setAction(ACTION_STOP);
        android.app.PendingIntent stopPi = android.app.PendingIntent.getService(this, 12, stop,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(this, 13, open,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, NOTIF_CH)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(L.s("Нэксус слушает"))
                .setContentText(L.s("Скажи «Нэксус …» · нажми, чтобы выключить"))
                .setContentIntent(stopPi)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this,
                                android.R.drawable.ic_lock_power_off),
                        L.s("Выключить"), stopPi).build())
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this,
                                android.R.drawable.ic_menu_preferences),
                        L.s("Настройки"), openPi).build())
                .setOngoing(true)
                .build();
    }

    // ------------------------------------------------------------- listening

    private int googleFailures = 0;
    private volatile long lastSignal = 0;

    /** если распознаватель «завис» без событий — перезапускаем его */
    private final Runnable stallWatch = new Runnable() {
        @Override public void run() {
            if (!running.get() || paused.get()) return;
            if (System.currentTimeMillis() - lastSignal > 20000) {
                SettingsStore.p(WakeService.this).edit()
                        .putString("wakeErr", L.s("перезапуск распознавания")).apply();
                stopListening();
                main.postDelayed(WakeService.this::listen, 600);
                return;
            }
            // проверка пришла раньше срока — не теряем сторож, иначе он умирает
            main.postDelayed(stallWatch, 8000);
        }
    };

    private void listen() {
        if (!running.get() || paused.get()) return;
        // панель ассистента на экране — микрофон её, иначе оба распознавателя глушат друг друга
        if (MicOwner.isPanelActive()) {
            main.postDelayed(this::listen, 700);
            return;
        }
        // не отбираем микрофон/звук у проигрываемой музыки
        if (audioManager != null && audioManager.isMusicActive()) {
            main.postDelayed(this::listen, 3000);
            return;
        }
        prepareRoute();
        if (isAsleep()) {
            // watchdog: recognizers die in doze — periodically re-kick
            main.removeCallbacks(watchdog);
            main.postDelayed(watchdog, 5000);
        }
        listenGoogle();
    }

    private final Runnable watchdog = this::listen;

    private void listenGoogle() {
        // на некоторых прошивках распознавание речи вообще не установлено —
        // не делаем вид, что слушаем: честно пишем ошибку в состояние
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            SettingsStore.p(this).edit()
                    .putString("wakeErr", L.s("нет системного распознавания речи")).apply();
            main.postDelayed(this::listen, 8000);
            return;
        }
        if (googleFailures == 0) SettingsStore.p(this).edit().putString("wakeErr", "").apply();
        if (google == null) {
            google = SpeechRecognizer.createSpeechRecognizer(this);
            google.setRecognitionListener(wakeListener);
        }
        if (recognizing) return;
        recognizing = true;
        startListeningNow();
    }

    /** один-единственный слушатель на всё время работы сервиса */
    private final RecognitionListener wakeListener = new RecognitionListener() {
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float r) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() {
                // «дзынь» остановки движок играет в этот момент
                BeepMuter.muteFor(WakeService.this, 1500, false);
            }
            @Override public void onEvent(int t, Bundle p) { }
            @Override public void onReadyForSpeech(Bundle p) {
                // держим тишину: «дзынь» старта играет сразу после startListening
            }
            @Override public void onPartialResults(Bundle p) {
                handleUtterance(p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION), true);
            }
            @Override public void onResults(Bundle p) {
                BeepMuter.muteFor(WakeService.this, 1200, false);
                recognizing = false;
                boolean wake = handleUtterance(
                        p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION), false);
                if (!wake) main.postDelayed(() -> { if (running.get() && !paused.get()) listen(); }, 250);
            }
            @Override public void onError(int error) {
                BeepMuter.muteFor(WakeService.this, 1200, false);
                recognizing = false;
                long delay = 1000;
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) delay = 700;
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                        || error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_NETWORK) {
                    googleFailures++;
                } else {
                    googleFailures = 0;
                }
                // честно сообщаем в интерфейс, что Google разваливается
                if (googleFailures == 3) {
                    SettingsStore.p(WakeService.this).edit()
                            .putString("wakeErr", L.s("распознавание сбоит — перезапускаю")).apply();
                }
                main.postDelayed(() -> { if (running.get() && !paused.get()) listen(); }, delay);
            }
    };

    private void startListeningNow() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // держим одну длинную сессию: каждый перезапуск движок сопровождает
        // звуком, поэтому раньше «дзынь» слышался каждые несколько секунд
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 60_000);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500);
        // окно на «дзынь» старта; дольше нельзя — звонок в той же группе громкости
        BeepMuter.muteFor(this, 2200, false);
        lastSignal = System.currentTimeMillis();
        main.removeCallbacks(stallWatch);
        main.postDelayed(stallWatch, 20000);
        try {
            google.startListening(i);
        } catch (Exception e) {
            BeepMuter.unmute(WakeService.this);
            recognizing = false;
            stopGoogle();
            main.postDelayed(this::listen, 1500);
        }
    }

    /** @return true if wake word found */
    private boolean handleUtterance(java.util.ArrayList<String> list, boolean partial) {
        if (list == null) return false;
        for (String s : list) {
            if (s == null) continue;
            String low = s.toLowerCase(new java.util.Locale("ru", "RU")).trim();
            if (!containsWake(low)) continue;
            String rest = extractRest(low);
            onWakeHeard(rest.isEmpty() && !partial ? "" : rest);
            return true;
        }
        return false;
    }

    private boolean containsWake(String low) {
        for (String w : WAKE_WORDS) {
            if (low.contains(w)) return true;
            // «нексус» часто распознаётся как «нексус» / «некси» / «нексу»
            if (w.length() > 5 && low.contains(w.substring(0, w.length() - 1))) return true;
        }
        return false;
    }

    private String extractRest(String low) {
        for (String w : WAKE_WORDS) {
            int idx = low.indexOf(w);
            if (idx >= 0) {
                String rest = low.substring(idx + w.length()).replaceFirst("^[,. ]+", "");
                for (String f : new String[]{"привет", "слушай", "эй"}) {
                    if (rest.startsWith(f)) rest = rest.substring(f.length()).replaceFirst("^[,. ]+", "");
                }
                return rest;
            }
        }
        return "";
    }

    private void onWakeHeard(String prequery) {
        // сначала отпускаем микрофон, иначе панель получит «recognition busy»
        stopListening();
        releaseRoute();
        openAssistant(prequery);
    }

    /** С Android 10 фон не может просто так показать окно: нужен оверлей или full-screen intent. */
    private void openAssistant(String prequery) {
        Intent i = new Intent(this, AssistantActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        i.putExtra(EXTRA_TEXT, prequery);
        // с видимым оверлеем Android 10+ не блокирует запуск окна из фона
        attachAnchor();
        boolean overlay = false;
        try {
            overlay = android.provider.Settings.canDrawOverlays(this);
        } catch (Throwable ignored) {
        }
        if (overlay) {
            try { startActivity(i); return; } catch (Throwable ignored) { }
        }
        // без «Поверх других приложений» система молча блокирует запуск активности —
        // дублируем полноэкранным уведомлением, оно пробивает фон
        showFullScreenWake(i);
        try { startActivity(i); } catch (Throwable ignored) { }
    }

    private android.view.View anchor;
    private android.view.WindowManager anchorWm;

    /** 1x1 прозрачное окно поверх: делает приложение «видимым» для системы */
    private void attachAnchor() {
        if (anchor != null) return;
        try {
            if (!android.provider.Settings.canDrawOverlays(this)) return;
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm == null) return;
            android.view.View v = new android.view.View(this);
            android.view.WindowManager.LayoutParams lp =
                    new android.view.WindowManager.LayoutParams(
                            1, 1,
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                            android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            wm.addView(v, lp);
            anchor = v;
            anchorWm = wm;
            main.postDelayed(this::detachAnchor, 5000);
        } catch (Throwable ignored) {
        }
    }

    private void detachAnchor() {
        try {
            if (anchor != null && anchorWm != null) anchorWm.removeViewImmediate(anchor);
        } catch (Throwable ignored) {
        }
        anchor = null;
        anchorWm = null;
    }

    private void showFullScreenWake(Intent open) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            final String chId = "wake_open";
            if (nm.getNotificationChannel(chId) == null) {
                NotificationChannel ch = new NotificationChannel(chId,
                        L.s("Нэксус: открыть панель"), NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setShowBadge(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 21, open,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                            | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, chId)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(L.s("Нэксус слушает"))
                    .setContentText(L.s("Открываю панель — нажми, если не открылась"))
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .build();
            nm.notify(45, n);
        } catch (Throwable ignored) {
        }
    }

    private void stopListening() {
        main.removeCallbacks(watchdog);
        stopGoogle();
        releaseRoute();
    }

    private void stopGoogle() {
        recognizing = false;
        if (google != null) {
            try { google.cancel(); } catch (Exception ignored) {}
            try { google.destroy(); } catch (Exception ignored) {}
            google = null;
        }
    }

    // ------------------------------------------------------------ audio route

    /** Звук всегда в обычном режиме: MODE_IN_COMMUNICATION уводил голос в трубку. */
    private void prepareRoute() {
        BeepMuter.ensureNormalMode(this);
    }

    private void releaseRoute() {
        BeepMuter.ensureNormalMode(this);
    }

    // -------------------------------------------------------------- screen off

    /** Recognizers die in doze; when screen is off we simply re-listen frequently. */
    private boolean isAsleep() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        return pm != null && !pm.isInteractive();
    }

    public static void start(Context c) {
        Intent i = new Intent(c, WakeService.class).setAction(ACTION_START);
        try {
            androidx.core.content.ContextCompat.startForegroundService(c, i);
        } catch (Throwable t) {
            try { c.startService(i); } catch (Throwable ignored) { }
        }
    }

    /** Освободить микрофон, не сбрасывая настройку «Слушать постоянно». */
    public static void halt(Context c) {
        try { c.stopService(new Intent(c, WakeService.class)); } catch (Throwable ignored) {}
    }

    public static void stop(Context c) {
        SettingsStore.p(c).edit().putBoolean("wakeEnabled", false).apply();
        try {
            c.stopService(new Intent(c, WakeService.class));
        } catch (Throwable ignored) {
        }
    }

}
