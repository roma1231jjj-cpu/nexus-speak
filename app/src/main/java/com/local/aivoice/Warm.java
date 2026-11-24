package com.local.aivoice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;

/**
 * Прогрев того, из-за чего голосовая панель раньше думала 2–3 секунды:
 * движок распознавания Google поднимается отдельным процессом, и первый
 * createSpeechRecognizer ждал его целиком. Держим распознаватель созданным
 * заранее — к моменту открытия панели сервис уже живой.
 */
public final class Warm {

    private static SpeechRecognizer warm;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Warm() {
    }

    public static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        main.post(() -> {
            try {
                if (warm == null && SpeechRecognizer.isRecognitionAvailable(app)) {
                    warm = SpeechRecognizer.createSpeechRecognizer(app);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** Отдаём прогретый распознаватель панели; если его нет — создаём обычный. */
    public static SpeechRecognizer take(Context ctx) {
        SpeechRecognizer r = warm;
        warm = null;
        if (r != null) return r;
        return SpeechRecognizer.createSpeechRecognizer(ctx);
    }

    public static void drop() {
        SpeechRecognizer r = warm;
        warm = null;
        if (r != null) {
            try {
                r.destroy();
            } catch (Throwable ignored) {
            }
        }
    }
}
