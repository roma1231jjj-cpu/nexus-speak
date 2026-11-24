package com.local.aivoice;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * TTS wrapper that NEVER blocks the caller.
 *
 * v1.3.1: главная причина «тишины» — старый код через 6 секунд помечал движок
 * мёртвым и ВЫБРАСЫВАЛ уже поставленный в очередь текст. На Honor/Google TTS
 * первый init легко занимает 5-10 секунд, поэтому ответ просто не озвучивался.
 * Теперь: текст не теряется, init повторяется, звук принудительно идёт в
 * STREAM_MUSIC (а не в системный поток, который глушит BeepMuter), и есть
 * диагностика «нет русского голоса».
 */
public class TtsSpeaker {
    public interface DoneListener { void onDone(); }
    public interface StateListener { void onSpeaking(boolean speaking); }

    private final Context ctx;
    private final DoneListener doneListener;
    private final String engine;
    private final float volume; // 0..1
    private final int ratePct;
    private final int pitchPct;
    private final String voiceName;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CloudTts cloud;
    private volatile TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile boolean failed = false;
    private volatile String problem = null;   // "no_engine" | "no_ru" | null
    private String pendingText;
    private boolean speaking = false;
    private int queued = 0;
    private int initAttempt = 0;
    private StateListener stateListener;

    public TtsSpeaker(Context ctx, DoneListener doneListener) {
        this(ctx, "", 100, doneListener);
    }

    public TtsSpeaker(Context ctx, String engine, int volumePct, DoneListener doneListener) {
        this(ctx, engine, volumePct, 100, 100, "", doneListener);
    }

    public TtsSpeaker(Context ctx, String engine, int volumePct, int ratePct, int pitchPct,
                      String voiceName, DoneListener doneListener) {
        this.ctx = ctx.getApplicationContext();
        this.engine = engine == null ? "" : engine.trim();
        this.volume = Math.max(0.05f, Math.min(1f, volumePct / 100f));
        this.ratePct = ratePct <= 0 ? 100 : ratePct;
        this.pitchPct = pitchPct <= 0 ? 100 : pitchPct;
        this.voiceName = voiceName == null ? "" : voiceName;
        this.doneListener = doneListener;
        if (CloudTts.active(ctx)) {
            cloud = new CloudTts(ctx, this.volume, new CloudTts.Listener() {
                @Override public void onDone() {
                    speaking = false;
                    synchronized (TtsSpeaker.this) { pendingText = null; }
                    notifyState(false);
                    notifyDone();
                }
                @Override public void onFail(String error) {
                    // облако не ответило — говорим системным голосом, чтобы не молчать.
                    // Раньше здесь сразу звали speakInternal(), но движок ещё не был
                    // готов: tts == null, исключение глоталось и ответ пропадал.
                    // Теперь фраза остаётся в pendingText, её озвучит onInit().
                    problem = "cloud";
                    main.post(() -> {
                        if (cloud != null) { cloud.shutdown(); cloud = null; }
                        init();
                    });
                }
            });
            ready = true;
            return;
        }
        init(); // async, returns immediately
    }

    public void setStateListener(StateListener l) { this.stateListener = l; }

    public boolean isReady() { return ready; }

    /** null = всё хорошо, иначе причина тишины */
    public String problem() { return problem; }

    /** Лучший движок из установленных: системный часто «деревянный», Google — нет. */
    private String resolveEngine() {
        if (!engine.isEmpty()) return engine;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            pm.getPackageInfo("com.google.android.tts", 0);
            return "com.google.android.tts";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Самый живой русский голос движка: сетевой лучше локального, «compact» — худший. */
    private android.speech.tts.Voice bestRuVoice() {
        java.util.Set<android.speech.tts.Voice> all;
        try {
            all = tts.getVoices();
        } catch (Throwable t) {
            return null;
        }
        if (all == null || all.isEmpty()) return null;
        android.speech.tts.Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (android.speech.tts.Voice v : all) {
            if (v.getLocale() == null) continue;
            if (!"ru".equals(v.getLocale().getLanguage())) continue;
            String n = v.getName() == null ? "" : v.getName().toLowerCase(java.util.Locale.ROOT);
            int score = 0;
            if (n.contains("network")) score += 100;
            if (n.contains("x-ruc")) score += 60;
            if (n.contains("local")) score += 40;
            if (n.contains("compact")) score -= 100;
            if (v.isNetworkConnectionRequired()) score += 25;
            if (n.contains("female") || n.contains("ru-ru")) score += 5;
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    private void init() {
        initAttempt++;
        String useEngine = resolveEngine();
        try {
            if (useEngine.isEmpty()) {
                tts = new TextToSpeech(ctx, this::onInit);
            } else {
                tts = new TextToSpeech(ctx, this::onInit, useEngine);
            }
        } catch (Throwable t) {
            onInit(TextToSpeech.ERROR);
            return;
        }
        // не убиваем движок насовсем: если за 10с не поднялся — отпускаем диалог,
        // но текст остаётся в очереди и озвучится, если init всё-таки придёт
        main.postDelayed(() -> {
            if (!ready && !failed) {
                String pending;
                synchronized (TtsSpeaker.this) { pending = pendingText; }
                if (pending != null && initAttempt < 2) {
                    retry();
                } else {
                    if (!ready) problem = "no_engine";
                    notifyDone();
                }
            }
        }, 10000);
    }

    private android.speech.tts.Voice pickVoice(String wanted) {
        java.util.Set<android.speech.tts.Voice> all;
        try {
            all = tts.getVoices();
        } catch (Throwable t) {
            return null;
        }
        if (all == null || all.isEmpty()) return null;
        String w = wanted.trim();
        String wl = w.toLowerCase(java.util.Locale.ROOT);
        for (android.speech.tts.Voice v : all) {
            if (w.equals(v.getName())) return v;
        }
        for (android.speech.tts.Voice v : all) {
            String n = v.getName() == null ? "" : v.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.equals(wl)) return v;
        }
        for (android.speech.tts.Voice v : all) {
            String n = v.getName() == null ? "" : v.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(wl) || wl.endsWith(n) || n.contains(wl)) return v;
        }
        return null;
    }

    private void retry() {        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
        tts = null;
        ready = false;
        init();
    }

    private void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true;
            problem = "no_engine";
            notifyDone();
            return;
        }
        try {
            // звук — в медиа-поток, чтобы не попадать под глушилку системных звуков
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());

            try {
                tts.setSpeechRate(Math.max(0.5f, Math.min(1.6f, ratePct / 100f)));
                tts.setPitch(Math.max(0.5f, Math.min(1.8f, pitchPct / 100f)));
            } catch (Throwable ignored) {
            }
            // сначала язык, потом голос: setLanguage() сбрасывает выбранный setVoice()
            int lang = tts.setLanguage(new Locale("ru", "RU"));
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                problem = "no_ru";
                int ru = tts.setLanguage(new Locale("ru"));
                if (ru == TextToSpeech.LANG_MISSING_DATA || ru == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.getDefault());
                }
            }
            if (voiceName.isEmpty()) {
                android.speech.tts.Voice auto = bestRuVoice();
                if (auto != null) {
                    try {
                        tts.setVoice(auto);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!voiceName.isEmpty()) {
                // v1.9.3: раньше совпадение искалось строго по getName(), и
                // любая другая запись (другой регистр, отображаемое имя,
                // «Дмитрий» вместо ru-ru-x-dmc-local) молча игнорировалась —
                // человек слышал голос по умолчанию.
                android.speech.tts.Voice picked = pickVoice(voiceName);
                if (picked != null) {
                    try {
                        tts.setVoice(picked);
                        problem = null;
                    } catch (Throwable ignored) {
                    }
                } else {
                    problem = "voice_missing";
                }
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    speaking = true;
                    notifyState(true);
                }
                @Override public void onDone(String utteranceId) {
                    speaking = false;
                    notifyState(false);
                    finishOne();
                }
                @Override public void onError(String utteranceId) {
                    speaking = false;
                    notifyState(false);
                    finishOne();
                }
            });
            ready = true;
            failed = false;
        } catch (Throwable ignored) {
            failed = true;
            problem = "no_engine";
        }
        String queued;
        synchronized (TtsSpeaker.this) {
            queued = pendingText;
            pendingText = null;
        }
        if (ready && queued != null) speakInternal(queued);
        else if (!ready) notifyDone();
    }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) { notifyDone(); return; }
        synchronized (this) { problem = null; }
        if (cloud != null) {
            // если облако ответит ошибкой, эту же фразу скажет системный голос
            synchronized (this) { pendingText = text; }
            speaking = true;
            notifyState(true);
            cloud.speak(text, true);
            return;
        }
        if (!ready) {
            synchronized (this) { pendingText = text; }
            return;
        }
        speakInternal(text);
    }

    private void speakInternal(String text) {
        speakInternal(text, true);
    }

    private void speakInternal(String text, boolean flush) {
        try {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,
                    android.media.AudioManager.STREAM_MUSIC);
            if (flush) {
                synchronized (this) { queued = 1; }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utt-" + System.nanoTime());
            } else {
                synchronized (this) { queued++; }
                tts.speak(text, TextToSpeech.QUEUE_ADD, params, "utt-" + System.nanoTime());
            }
        } catch (Throwable t) {
            notifyDone();
        }
    }

    /** adds a phrase to the end of the queue — used for streamed answers */
    public void speakAppend(String text) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (this) { problem = null; }
        if (cloud != null) {
            synchronized (this) {
                pendingText = pendingText == null ? text : pendingText + " " + text;
            }
            speaking = true;
            notifyState(true);
            cloud.speak(text, false);
            return;
        }
        if (!ready) {
            synchronized (this) {
                pendingText = pendingText == null ? text : pendingText + " " + text;
            }
            return;
        }
        speakInternal(text, false);
    }

    private void finishOne() {
        int left;
        synchronized (this) {
            queued--;
            left = queued;
        }
        if (left <= 0) {
            synchronized (this) { queued = 0; }
            notifyDone();
        }
    }

    public boolean isSpeaking() { return speaking; }

    /** true, если сейчас говорит облачный голос */
    public boolean isCloud() { return cloud != null; }

    public void stop() {
        if (cloud != null) {
            cloud.stop();
            speaking = false;
            notifyState(false);
            return;
        }
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        speaking = false;
        synchronized (this) { queued = 0; pendingText = null; }
    }

    public void shutdown() {
        if (cloud != null) { cloud.shutdown(); cloud = null; }
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
        tts = null;
        ready = false;
    }

    private void notifyDone() {
        if (doneListener != null) main.post(doneListener::onDone);
    }

    private void notifyState(boolean s) {
        if (stateListener != null) main.post(() -> stateListener.onSpeaking(s));
    }
}
