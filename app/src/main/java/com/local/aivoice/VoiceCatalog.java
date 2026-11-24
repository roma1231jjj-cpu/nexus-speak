package com.local.aivoice;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Список установленных голосов для русского — чтобы выбрать тот, что звучит лучше. */
public final class VoiceCatalog {

    /** {имя для настроек, подпись} */
    public interface Cb { void onDone(List<String[]> voices, String error); }

    public static void fetch(Context ctx, String engine, Cb cb) {
        new Thread(() -> {
            TextToSpeech tts = null;
            try {
                final CountDownLatch latch = new CountDownLatch(1);
                final int[] ok = {0};
                TextToSpeech.OnInitListener l = status -> {
                    ok[0] = status;
                    latch.countDown();
                };
                if (engine == null || engine.trim().isEmpty()) tts = new TextToSpeech(ctx, l);
                else tts = new TextToSpeech(ctx, l, engine.trim());
                latch.await(8, TimeUnit.SECONDS);
                if (ok[0] != TextToSpeech.SUCCESS || tts == null) {
                    cb.onDone(null, L.s("Движок синтеза речи не отвечает"));
                    return;
                }
                List<String[]> out = new ArrayList<>();
                for (android.speech.tts.Voice v : tts.getVoices()) {
                    Locale loc = v.getLocale();
                    if (loc == null || !"ru".equals(loc.getLanguage())) continue;
                    if (v.isNetworkConnectionRequired()) continue;
                    String label = loc.toLanguageTag() + " · " + v.getName();
                    if (v.getQuality() >= android.speech.tts.Voice.QUALITY_HIGH) label += L.s(" · высокое");
                    out.add(new String[]{v.getName(), label});
                }
                cb.onDone(out, out.isEmpty() ? L.s("Русских голосов не нашлось") : null);
            } catch (Throwable t) {
                cb.onDone(null, String.valueOf(t));
            } finally {
                try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
            }
        }).start();
    }

    private VoiceCatalog() {}
}
