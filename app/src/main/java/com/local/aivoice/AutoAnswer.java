package com.local.aivoice;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;

/**
 * «Автоответчик»: если владелец не берёт трубку N секунд, Нексус принимает
 * звонок, включает громкую связь, произносит приветствие и (по настройке)
 * кладёт трубку. Услышать ответ абонента телефон не даёт без root, поэтому
 * это именно автоответчик, а не полноценный разговор.
 */
public final class AutoAnswer {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean answeredByUs = false;
    private static Runnable pending;

    public static void onRinging(final Context c) {
        cancel();
        if (!SettingsStore.callAnswer(c)) return;
        long delay = Math.max(2, SettingsStore.callAnswerDelay(c)) * 1000L;
        pending = () -> pickUp(c);
        main.postDelayed(pending, delay);
    }

    public static void onIdleOrOffhook(Context c) {
        // пользователь ответил сам или звонок закончился — отменяем всё
        cancel();
        if (answeredByUs) {
            answeredByUs = false;
            releaseAudio(c);
        }
    }

    private static void cancel() {
        if (pending != null) {
            main.removeCallbacks(pending);
            pending = null;
        }
    }

    private static void pickUp(Context c) {
        pending = null;
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (tm == null || am == null) return;
            if (am.getMode() == AudioManager.MODE_IN_CALL) return; // уже ответили

            if (androidx.core.content.ContextCompat.checkSelfPermission(c,
                    android.Manifest.permission.ANSWER_PHONE_CALLS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            tm.acceptRingingCall();
            answeredByUs = true;

            main.postDelayed(() -> {
                try { am.setSpeakerphoneOn(true); } catch (Throwable ignored) {}
                String greeting = SettingsStore.callGreeting(c);
                TtsSpeaker tts = new TtsSpeaker(c, SettingsStore.ttsEngine(c),
                        SettingsStore.voiceVolume(c), () -> {
                            if (SettingsStore.callAutoHangup(c)) hangUp(c);
                        });
                tts.speak(greeting);
                // страховка: если TTS не отработал — всё равно завершаем
                main.postDelayed(() -> {
                    if (SettingsStore.callAutoHangup(c)) hangUp(c);
                }, 20000);
            }, 1200);
        } catch (Throwable ignored) {
        }
    }

    private static void hangUp(Context c) {
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) return;
            if (androidx.core.content.ContextCompat.checkSelfPermission(c,
                    android.Manifest.permission.ANSWER_PHONE_CALLS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            tm.endCall();
        } catch (Throwable ignored) {
        } finally {
            answeredByUs = false;
            releaseAudio(c);
        }
    }

    private static void releaseAudio(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setSpeakerphoneOn(false);
        } catch (Throwable ignored) {
        }
    }

    private AutoAnswer() {}
}
