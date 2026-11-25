package com.local.aivoice;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Асинхронная проверка движка озвучки: результат кэшируется, окно не вешается. */
public final class TtsProbe {

    private static volatile Boolean ok;

    public static void start(Context c) {
        try {
            // проверяем только локальный движок телефона: его сбой никогда не
            // должен запрещать облачные голоса и наоборот
            new TextToSpeech(c.getApplicationContext(), status -> {
                ok = status == TextToSpeech.SUCCESS;
            });
        } catch (Throwable ignored) {
        }
    }

    /** true/false — известно, null — ещё не проверили. */
    public static Boolean known() {
        return ok;
    }

    /**
     * Настоящий отказ сервера — это только явный код 401/403/404.
     * Таймаут, офлайн, обрыв соединения отказом не считаются: блокировать
     * движок на сутки из-за пропавшего интернета нельзя.
     */
    static boolean edgeRefusal(Throwable t) {
        if (t == null) return false;
        if (t instanceof EdgeTts.Refused) {
            int code = ((EdgeTts.Refused) t).code;
            return code == 401 || code == 403 || code == 404;
        }
        String raw = String.valueOf(t.getMessage()) + " " + t.getClass().getSimpleName();
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.contains("403") || s.contains("401") || s.contains("404")) return true;
        return s.contains("forbidden") || s.contains("unauthorized");
    }

    private TtsProbe() {
    }
}
