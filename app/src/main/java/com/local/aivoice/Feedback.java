package com.local.aivoice;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Short audio blip + haptic tick when the assistant starts/stops listening. */
public final class Feedback {

    public static void blip(Context c) {
        // no sound: system beeps annoyed users; haptic only
        vibrate(c, 40);
    }

    public static void tick(Context c) {
        vibrate(c, 25);
    }

    private static void vibrate(Context c, long ms) {
        try {
            Object sys = c.getSystemService(VibratorManager.class);
            Vibrator v;
            if (sys instanceof VibratorManager) {
                v = ((VibratorManager) sys).getDefaultVibrator();
            } else {
                v = (Vibrator) c.getSystemService(Vibrator.class);
            }
            if (v == null) return;
            v.vibrate(VibrationEffect.createOneShot(ms, 120));
        } catch (Throwable ignored) {
        }
    }

    private Feedback() {}
}
