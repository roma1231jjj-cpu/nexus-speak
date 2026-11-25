package com.local.aivoice;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TTS wrapper that NEVER blocks the caller.
 *
 * v1.3.1: главная причина «тишины» — старый код через 6 секунд помечал движок
 * мёртвым и ВЫБРАСЫВАЛ уже поставленный в очередь текст. На Honor/Google TTS
 * первый init легко занимает 5-10 секунд, поэтому ответ просто не озвучивался.
 * Теперь: текст не теряется, init повторяется, звук принудительно идёт в
 * STREAM_MUSIC (а не в системный поток, который глушит BeepMuter), и есть
 * диагностика «нет русского голоса».
 *
 * Язык берётся из самого текста: кириллица — ru-RU, латиница — en-US, другой
 * алфавит — Locale.getDefault(). Раньше локаль жёстко ставилась в ru-RU, и
 * английские ответы читались с русской фонетикой.
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
    private volatile String problem = null;   // "no_engine" | "no_ru" | "voice_missing" | "cloud" | null
    private String pendingText;
    private volatile boolean speaking = false;
    private int queued = 0;
    private int initAttempt = 0;
    private StateListener stateListener;

    /** локаль, реально стоящая у движка, и язык последнего текста */
    private volatile Locale currentLang;
    private volatile Locale wantLang;

    /** одноразовые отметки на utterance: один и тот же id не может дать «done» дважды */
    private final ConcurrentHashMap<String, AtomicBoolean> fired = new ConcurrentHashMap<>();
    private final AtomicLong uttSeq = new AtomicLong();
    /** растёт при каждом flush/stop: события старых utterance игнорируются */
    private volatile int gen = 0;
    private final AtomicBoolean cloudDone = new AtomicBoolean(false);
    private volatile String cloudError;

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
                    if (!cloudDone.compareAndSet(false, true)) return;
                    speaking = false;
                    synchronized (TtsSpeaker.this) { pendingText = null; }
                    notifyState(false);
                    notifyDone();
                }
                @Override public void onFail(String error) {
                    // второй отказ по тому же тексту не должен снова поднимать init
                    if (!cloudDone.compareAndSet(false, true)) return;
                    // облако не ответило — говорим системным голосом, чтобы не молчать.
                    // Раньше здесь сразу звали speakInternal(), но движок ещё не был
                    // готов: tts == null, исключение глоталось и ответ пропадал.
                    // Теперь фраза остаётся в pendingText, её озвучит onInit().
                    problem = "cloud";
                    cloudError = error;
                    if (error != null) android.util.Log.w("NexusTts", "cloud: " + error);
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

    /** текст последнего отказа облака — чтобы было видно, что именно не так */
    public String cloudError() { return cloudError; }

    // ------------------------------------------------------------- язык текста

    /**
     * Язык по самому тексту: кириллицы >= 15% букв — русский, латиницы >= 15% —
     * английский, остальные алфавиты — системная локаль. null — букв нет.
     */
    static Locale localeOf(String text) {
        if (text == null) return null;
        int letters = 0, cyr = 0, lat = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isLetter(ch)) continue;
            letters++;
            if (ch >= 0x0400 && ch <= 0x04FF) cyr++;
            else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) lat++;
        }
        if (letters == 0) return null;
        if (cyr * 100L >= letters * 15L) return new Locale("ru", "RU");
        if (lat * 100L >= letters * 15L) return Locale.US;
        return Locale.getDefault();
    }

    /** "ru-RU" / "en-US" / null, если букв в тексте нет */
    static String languageTag(String text) {
        Locale l = localeOf(text);
        if (l == null) return null;
        String c = l.getCountry();
        return c == null || c.isEmpty() ? l.getLanguage() : l.getLanguage() + "-" + c;
    }

    static boolean isEnglishText(String text) {
        Locale l = localeOf(text);
        return l != null && "en".equals(l.getLanguage());
    }

    private static boolean sameLanguage(Locale a, Locale b) {
        return a != null && b != null && a.getLanguage().equals(b.getLanguage());
    }

    private static boolean setLanguage(TextToSpeech t, Locale l) {
        try {
            int r = t.setLanguage(l);
            return r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** запомнить язык текста и, если движок готов, сразу поставить его */
    private void applyLanguage(String text) {
        Locale want = localeOf(text);
        if (want == null) return;
        wantLang = want;
        TextToSpeech t = tts;
        if (!ready || t == null) return;
        applyLanguageNow(t, want);
    }

    /**
     * Ставит язык/голос под нужную локаль. false — у движка нет ни голоса этой
     * локали, ни самого языка: тогда остаётся прежняя локаль, без падений.
     */
    private boolean applyLanguageNow(TextToSpeech t, Locale want) {
        if (want == null) return false;
        if (sameLanguage(currentLang, want)) return true;
        // выбранный человеком голос того же языка важнее «лучшего по качеству»
        if (!voiceName.isEmpty()) {
            android.speech.tts.Voice byName = pickVoice(voiceName);
            if (byName != null && byName.getLocale() != null
                    && want.getLanguage().equals(byName.getLocale().getLanguage())) {
                try {
                    t.setLanguage(byName.getLocale());
                    t.setVoice(byName);
                    currentLang = byName.getLocale();
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        android.speech.tts.Voice v = bestVoiceFor(want);
        if (v != null && v.getLocale() != null) {
            try {
                t.setLanguage(v.getLocale());
                t.setVoice(v);
                currentLang = v.getLocale();
                return true;
            } catch (Throwable ignored) {
            }
        }
        if (setLanguage(t, want)) {
            currentLang = want;
            return true;
        }
        Locale cur = currentLang;
        if (cur != null) setLanguage(t, cur);
        return false;
    }

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

    /** Самый живой голос нужного языка: сетевой лучше локального, «compact» — худший. */
    private android.speech.tts.Voice bestVoiceFor(Locale want) {
        java.util.Set<android.speech.tts.Voice> all;
        try {
            all = tts.getVoices();
        } catch (Throwable t) {
            return null;
        }
        if (all == null || all.isEmpty() || want == null) return null;
        String lang = want.getLanguage();
        String country = want.getCountry() == null ? "" : want.getCountry();
        android.speech.tts.Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (android.speech.tts.Voice v : all) {
            Locale vl = v.getLocale();
            if (vl == null || !lang.equals(vl.getLanguage())) continue;
            String n = v.getName() == null ? "" : v.getName().toLowerCase(Locale.ROOT);
            int score = 10;
            if (country.isEmpty() || country.equals(vl.getCountry())) score += 20;
            if (n.contains("network")) score += 100;
            if (n.contains("x-" + lang + "c")) score += 60;
            if (n.contains("local")) score += 40;
            if (n.contains("compact")) score -= 100;
            if (v.isNetworkConnectionRequired()) score += 25;
            if (n.contains("female") || n.contains(lang + "-" + lang)) score += 5;
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
            all = tts == null ? null : tts.getVoices();
        } catch (Throwable t) {
            return null;
        }
        if (all == null || all.isEmpty()) return null;
        String w = wanted.trim();
        if (w.isEmpty()) return null;
        String wl = w.toLowerCase(Locale.ROOT);
        for (android.speech.tts.Voice v : all) {
            if (w.equals(v.getName())) return v;
        }
        for (android.speech.tts.Voice v : all) {
            String n = v.getName() == null ? "" : v.getName().toLowerCase(Locale.ROOT);
            if (n.equals(wl)) return v;
        }
        for (android.speech.tts.Voice v : all) {
            String n = v.getName() == null ? "" : v.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(wl) || wl.endsWith(n) || n.contains(wl)) return v;
        }
        return null;
    }

    private void retry() {
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
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
        TextToSpeech t = tts;
        if (t == null) {
            failed = true;
            problem = "no_engine";
            notifyDone();
            return;
        }
        String pending;
        synchronized (TtsSpeaker.this) {
            pending = pendingText;
            pendingText = null;
        }
        try {
            // звук — в медиа-поток, чтобы не попадать под глушилку системных звуков
            t.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());

            try {
                t.setSpeechRate(Math.max(0.5f, Math.min(1.6f, ratePct / 100f)));
                t.setPitch(Math.max(0.5f, Math.min(1.8f, pitchPct / 100f)));
            } catch (Throwable ignored) {
            }
            t.setOnUtteranceProgressListener(progressListener);

            Locale target = wantLang != null ? wantLang : localeOf(pending);
            boolean voiceSet = false;
            if (!voiceName.isEmpty()) {
                // v1.9.3: раньше совпадение искалось строго по getName(), и
                // любая другая запись (другой регистр, отображаемое имя,
                // «Дмитрий» вместо ru-ru-x-dmc-local) молча игнорировалась —
                // человек слышал голос по умолчанию.
                android.speech.tts.Voice picked = pickVoice(voiceName);
                if (picked != null) {
                    try {
                        if (picked.getLocale() != null) {
                            setLanguage(t, picked.getLocale());
                            currentLang = picked.getLocale();
                        }
                        t.setVoice(picked);
                        voiceSet = true;
                        problem = null;
                    } catch (Throwable ignored) {
                    }
                } else {
                    problem = "voice_missing";
                }
            }
            if (!voiceSet) {
                Locale use = target != null ? target : Locale.getDefault();
                if (!applyLanguageNow(t, use) && "ru".equals(use.getLanguage())) {
                    // русского голоса в движке нет — человеку говорим именно об этом
                    problem = "no_ru";
                }
            }
            if (currentLang == null) {
                try { currentLang = t.getLanguage(); } catch (Throwable ignored) {}
            }
            ready = true;
            failed = false;
        } catch (Throwable ignored) {
            failed = true;
            problem = "no_engine";
        }
        if (ready && pending != null) speakInternal(pending);
        else if (!ready) notifyDone();
    }

    /** id вида "u<gen>-<n>": поколение отделяет отменённые utterance от живых */
    private int genOf(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith("u")) return -1;
        int dash = utteranceId.indexOf('-');
        if (dash <= 1) return -1;
        try {
            return Integer.parseInt(utteranceId.substring(1, dash));
        } catch (Throwable t) {
            return -1;
        }
    }

    private final UtteranceProgressListener progressListener = new UtteranceProgressListener() {
        @Override public void onStart(String utteranceId) {
            if (genOf(utteranceId) != gen) return;
            speaking = true;
            notifyState(true);
        }
        @Override public void onDone(String utteranceId) {
            finish(utteranceId);
        }
        @Override public void onError(String utteranceId) {
            finish(utteranceId);
        }
        @Override public void onStop(String utteranceId, boolean interrupted) {
            // отменённая фраза не «досказана»: gen уже сдвинут, id устарел
            finish(utteranceId);
        }
    };

    /** один utterance даёт не больше одного done, отменённый — ни одного */
    private void finish(String utteranceId) {
        if (genOf(utteranceId) != gen) return;
        AtomicBoolean flag = fired.get(utteranceId);
        if (flag == null || !flag.compareAndSet(false, true)) return;
        fired.remove(utteranceId, flag);
        speaking = false;
        notifyState(false);
        finishOne();
    }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) { notifyDone(); return; }
        synchronized (this) { problem = null; }
        cloudError = null;
        applyLanguage(text);
        if (cloud != null) {
            // если облако ответит ошибкой, эту же фразу скажет системный голос
            synchronized (this) { pendingText = text; }
            cloudDone.set(false);
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
        TextToSpeech t = tts;
        if (t == null || !ready) {
            synchronized (this) { pendingText = text; }
            return;
        }
        applyLanguage(text);
        String id;
        if (flush) {
            synchronized (this) {
                gen++;
                fired.clear();
                queued = 1;
                id = "u" + gen + "-" + uttSeq.incrementAndGet();
                fired.put(id, new AtomicBoolean(false));
            }
        } else {
            synchronized (this) {
                queued++;
                id = "u" + gen + "-" + uttSeq.incrementAndGet();
                fired.put(id, new AtomicBoolean(false));
            }
        }
        try {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,
                    android.media.AudioManager.STREAM_MUSIC);
            t.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, id);
        } catch (Throwable t2) {
            fired.remove(id);
            notifyDone();
        }
    }

    /** adds a phrase to the end of the queue — used for streamed answers */
    public void speakAppend(String text) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (this) { problem = null; }
        cloudError = null;
        applyLanguage(text);
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
            cloudDone.set(true);
            speaking = false;
            synchronized (this) { queued = 0; pendingText = null; }
            notifyState(false);
            return;
        }
        // сдвигаем поколение ДО stop(): события отменённой фразы уже не «done»
        synchronized (this) {
            gen++;
            fired.clear();
            queued = 0;
            pendingText = null;
        }
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        speaking = false;
        notifyState(false);
    }

    public void shutdown() {
        if (cloud != null) { cloud.shutdown(); cloud = null; }
        synchronized (this) {
            gen++;
            fired.clear();
            queued = 0;
        }
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
