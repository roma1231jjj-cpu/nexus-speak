package com.local.aivoice;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;

/**
 * Глушит «дзынь», который система играет при старте и остановке распознавания.
 *
 * v1.9.3: главная причина того, что телефон сам переключал режим «вибрация →
 * беззвучный → обычный» и менял громкость уведомлений. На Android потоки
 * SYSTEM, RING, NOTIFICATION, SYSTEM_ENFORCED и DTMF живут в ОДНОЙ группе
 * громкости: любое приглушение любого из них меняет режим звонка, а
 * восстановление возвращает громкость не туда. Поэтому группу звонка мы
 * больше не трогаем вообще — только TTS, доступность, ассистента и медиа.
 */
public final class BeepMuter {

    /** часть констант скрыта в SDK — пишем номерами, как их знает AudioFlinger */
    private static final int S_TTS = 9;
    private static final int S_ACCESSIBILITY = 10;
    private static final int S_ASSISTANT = 11;

    /** только потоки, которые не входят в группу звонка */
    private static final int[] ALWAYS = {S_TTS, S_ACCESSIBILITY, S_ASSISTANT};

    /** поток → громкость до глушения (-1 = он и так был беззвучный) */
    private static final HashMap<Integer, Integer> saved = new HashMap<>();
    private static boolean muted = false;
    private static boolean musicMuted = false;
    private static Runnable pendingUnmute;
    private static Context appCtx;
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized void mute(Context c) {
        mute(c, true);
    }

    public static void muteFor(Context c, long ms) {
        muteFor(c, ms, true);
    }

    public static void muteFor(Context c, long ms, boolean includeNotifications) {
        mute(c, includeNotifications);
        unmuteSoon(c, ms);
    }

    public static synchronized void mute(Context c, boolean includeNotifications) {
        final Context app = c.getApplicationContext();
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;

        boolean music = musicPlaying(c);
        try {
            if (music && musicMuted) {
                restore(am, AudioManager.STREAM_MUSIC);
                musicMuted = false;
            }
            for (int s : ALWAYS) muteStream(am, s);
            if (!music) {
                muteStream(am, AudioManager.STREAM_MUSIC);
                musicMuted = true;
                appCtx = app;
                main.removeCallbacks(musicWatch);
                main.postDelayed(musicWatch, 1500);
            }
            muted = true;
            if (pendingUnmute != null) main.removeCallbacks(pendingUnmute);
            pendingUnmute = () -> unmute(app);
            main.postDelayed(pendingUnmute, 15000);
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable musicWatch = new Runnable() {
        @Override public void run() {
            if (!muted || !musicMuted || appCtx == null) return;
            if (!musicPlaying(appCtx)) {
                main.postDelayed(this, 1500);
                return;
            }
            try {
                AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) restore(am, AudioManager.STREAM_MUSIC);
            } catch (Throwable ignored) {
            }
            musicMuted = false;
        }
    };

    private static void muteStream(AudioManager am, int stream) {
        try {
            if (!saved.containsKey(stream)) saved.put(stream, am.getStreamVolume(stream));
            am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
            if (!am.isStreamMute(stream)) am.setStreamVolume(stream, 0, 0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Возвращаем звук только тому потоку, который реально глушили, и только
     * если он всё ещё беззвучный: если человек сам крутил громкость, его
     * значение главнее.
     */
    private static void restore(AudioManager am, int stream) {
        Integer v = saved.remove(stream);
        if (v == null) return;
        try {
            if (!am.isStreamMute(stream) && am.getStreamVolume(stream) > 0) {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                return;
            }
            am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
            if (v > 0) am.setStreamVolume(stream, v, 0);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void unmuteSoon(Context c, long ms) {
        if (!muted) return;
        if (pendingUnmute != null) main.removeCallbacks(pendingUnmute);
        final Context app = c.getApplicationContext();
        pendingUnmute = () -> unmute(app);
        main.postDelayed(pendingUnmute, Math.max(50, ms));
    }

    public static synchronized void unmute(Context c) {
        if (!muted) return;
        muted = false;
        musicMuted = false;
        try {
            AudioManager am = (AudioManager) c.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            for (int s : new int[]{S_TTS, S_ACCESSIBILITY, S_ASSISTANT, AudioManager.STREAM_MUSIC}) {
                restore(am, s);
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean musicPlaying(Context c) {
        try {
            AudioManager am = (AudioManager) c.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

/**
     * Возвращает обычный аудиорежим, но только когда звонка нет.
     * Раньше приложение ставило MODE_NORMAL безусловно — и если человек
     * говорил по телефону, звук разговора уезжал в динамик и возвращался.
     */
    public static void ensureNormalMode(Context c) {
        try {
            AudioManager am = (AudioManager) c.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (am.getMode() == AudioManager.MODE_NORMAL) return;
            int callState = android.telephony.TelephonyManager.CALL_STATE_IDLE;
            try {
                android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                        c.getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) callState = tm.getCallState();
                else return;
            } catch (Throwable noPerm) {
                return;
            }
            if (callState != android.telephony.TelephonyManager.CALL_STATE_IDLE) return;
            am.setMode(AudioManager.MODE_NORMAL);
        } catch (Throwable ignored) {
        }
    }

    private BeepMuter() {}
}
