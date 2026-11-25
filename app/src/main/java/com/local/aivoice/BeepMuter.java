package com.local.aivoice;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * Глушит «дзынь», который система играет при старте и остановке распознавания.
 *
 * v1.9.3: главная причина того, что телефон сам переключал режим «вибрация →
 * беззвучный → обычный» и менял громкость уведомлений. На Android потоки
 * SYSTEM, RING, NOTIFICATION, SYSTEM_ENFORCED и DTMF живут в ОДНОЙ группе
 * громкости: любое приглушение любого из них меняет режим звонка, а
 * восстановление возвращает громкость не туда. Поэтому группу звонка мы
 * больше не трогаем вообще — только TTS, доступность, ассистента и медиа.
 *
 * Глушение и исходные громкости теперь лежат в SharedPreferences
 * (ключи beepmute_*): если процесс убили во время глушения, звук всё равно
 * вернётся при следующем запуске, а не останется выключенным навсегда.
 */
public final class BeepMuter {

    /** часть констант скрыта в SDK — пишем номерами, как их знает AudioFlinger */
    private static final int S_TTS = 9;
    private static final int S_ACCESSIBILITY = 10;
    private static final int S_ASSISTANT = 11;

    /** только потоки, которые не входят в группу звонка */
    private static final int[] ALWAYS = {S_TTS, S_ACCESSIBILITY, S_ASSISTANT};

    private static final String PREF = "beepmute";
    private static final String K_MUTED = "beepmute_muted";
    private static final String K_VOL = "beepmute_vol_";

    /** поток → громкость до глушения (-1 = он и так был беззвучный) */
    private static final HashMap<Integer, Integer> saved = new HashMap<>();
    private static boolean muted = false;
    private static boolean musicMuted = false;
    private static boolean inited = false;
    private static Runnable pendingUnmute;
    private static Context appCtx;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Восстановление после убитого процесса: если прошлая сессия оставила
     * muted=true, возвращаем громкости и чистим отметку. Вызывается из
     * mute()/unmute() и безопасен при повторных вызовах.
     */
    public static synchronized void init(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        appCtx = app;
        if (inited) return;
        inited = true;
        try {
            SharedPreferences sp = prefs(app);
            if (!sp.getBoolean(K_MUTED, false)) return;
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            for (int s : new int[]{S_TTS, S_ACCESSIBILITY, S_ASSISTANT,
                    AudioManager.STREAM_MUSIC}) {
                int v = sp.getInt(K_VOL + s, -1);
                if (v < 0) continue;
                saved.put(s, v);
            }
            if (am != null) {
                for (Integer s : new java.util.ArrayList<>(saved.keySet())) restore(am, s);
            }
            muted = false;
            musicMuted = false;
            sp.edit().clear().commit();
        } catch (Throwable ignored) {
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** сохраняем факт глушения и исходные громкости: переживает смерть процесса */
    private static void persist() {
        if (appCtx == null) return;
        try {
            SharedPreferences.Editor e = prefs(appCtx).edit().clear();
            if (muted) {
                e.putBoolean(K_MUTED, true);
                for (Map.Entry<Integer, Integer> en : saved.entrySet()) {
                    e.putInt(K_VOL + en.getKey(), en.getValue());
                }
            }
            e.commit();
        } catch (Throwable ignored) {
        }
    }

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
        if (c == null) return;
        init(c);
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
            persist();
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
            persist();
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
        if (c == null) return;
        init(c);
        if (!muted) return;
        if (pendingUnmute != null) main.removeCallbacks(pendingUnmute);
        final Context app = c.getApplicationContext();
        pendingUnmute = () -> unmute(app);
        main.postDelayed(pendingUnmute, Math.max(50, ms));
    }

    public static synchronized void unmute(Context c) {
        if (c == null) return;
        // если прошлый процесс убили во время глушения, init вернёт звук сам
        init(c);
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
        } finally {
            saved.clear();
            persist();
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
            if (c == null) return;
            init(c);
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
