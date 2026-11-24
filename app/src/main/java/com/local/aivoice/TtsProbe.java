package com.local.aivoice;

import android.content.Context;
import android.speech.tts.TextToSpeech;

/** Асинхронная проверка движка озвучки: результат кэшируется, окно не вешается. */
public final class TtsProbe {

    private static volatile Boolean ok;

    public static void start(Context c) {
        try {
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

    private TtsProbe() {
    }
}
