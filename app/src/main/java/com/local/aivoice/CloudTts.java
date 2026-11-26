package com.local.aivoice;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Облачная озвучка: голоса, которые звучат заметно живее системного TTS.
 *
 * Поддерживаются три сервиса:
 *  - OpenAI (и любой совместимый шлюз): /audio/speech, модель gpt-4o-mini-tts
 *  - ElevenLabs: eleven_multilingual_v2 — лучший русский, дороже
 *  - Яндекс SpeechKit: голоса filipp / ermil / jane / alena и т.д.
 *
 * Запросы идут в фоновом потоке, фразы играются по очереди, поэтому стриминг
 * ответа озвучивается почти без задержки. Ключи хранятся только в телефоне.
 */
public class CloudTts {
    public interface Listener {
        void onDone();
        void onFail(String error);
    }

    public static final String OPENAI = "openai";
    public static final String ELEVEN = "eleven";
    public static final String YANDEX = "yandex";
    /** Бесплатно и без ключа: нейроголоса Microsoft Edge (Svetlana/Dmitry). */
    public static final String EDGE = "edge";
    /** Бесплатно и без ключа: голос Google-переводчика (женский, очень живой). */
    public static final String FREE = "free";

    /** сколько раз подряд облако не ответило; после двух — не тратим время на него */
    private static volatile int cloudFails = 0;
    private static volatile long cloudBlockedUntil = 0;

    /** Edge-сервер отвечает 403: запоминаем и не ждём таймаут на каждой фразе */
    private static volatile long edgeBlockedUntil = 0;

    static boolean edgeBlocked() {
        return System.currentTimeMillis() < edgeBlockedUntil;
    }

    static void blockEdge() {
        // из России сервер Edge отдаёт 403 постоянно, а каждая попытка
        // стоит до 12 секунд ожидания — поэтому помним сутки, не 10 минут
        edgeBlockedUntil = System.currentTimeMillis() + 24 * 60 * 60_000L;
    }

    /**
     * Блокируем только на настоящий отказ сервера (401/403/404).
     * Таймаут и офлайн — не повод запрещать движок на сутки.
     */
    private static void blockEdgeIfRefused(Throwable t) {
        if (TtsProbe.edgeRefusal(t)) blockEdge();
    }

    /** один фоновый замер при старте: к первому ответу уже знаем, живой ли Edge */
    public static void probeAsync(final Context c) {
        if (!EDGE.equals(provider(c)) || edgeBlocked()) return;
        new Thread(() -> {
            try {
                EdgeTts.synth("проверка", EdgeTts.defaultVoice("ru-RU"), 1f);
            } catch (Throwable t) {
                // пустой/короткий ответ и обрыв сети не блокируют: это не отказ
                blockEdgeIfRefused(t);
            }
        }, "edge-probe").start();
    }

    static void markOk() { cloudFails = 0; cloudBlockedUntil = 0; }

    static void markFail() {
        if (++cloudFails >= 2) {
            cloudBlockedUntil = System.currentTimeMillis() + 10 * 60_000L;
            cloudFails = 0;
        }
    }

    static boolean blocked() {
        return System.currentTimeMillis() < cloudBlockedUntil;
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedList<String> queue = new LinkedList<>();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();

    private Thread worker;
    private volatile boolean cancelled = false;
    private MediaPlayer player;
    /** ждём окончания фразы; stopPlayback() обязан разбудить этот latch */
    private volatile java.util.concurrent.CountDownLatch playLatch;
    private volatile boolean doneSent = false;
    private final float volume;

    public CloudTts(Context c, float volume, Listener listener) {
        this.ctx = c.getApplicationContext();
        this.volume = volume;
        this.listener = listener;
    }

    // ------------------------------------------------------------- настройки

    public static String provider(Context c) {
        android.content.SharedPreferences p = SettingsStore.p(c);
        // По умолчанию — бесплатный голос Google-переводчика: Edge из России
        // стабильно отвечает 403, и первый же ответ молчал бы. Настройку не
        // трогали — берём самый предсказуемый живой голос.
        if (!p.contains("ttsCloud")) return FREE;
        return p.getString("ttsCloud", "");
    }

    public static boolean active(Context c) {
        String p = provider(c);
        if (p.isEmpty()) return false;
        // сервис уже дважды подряд молчал — сразу системный голос, без ожидания
        if (blocked()) return false;
        if (EDGE.equals(p) || FREE.equals(p)) return true;   // ключ не нужен
        return !SettingsStore.p(c).getString("ttsCloudKey", "").trim().isEmpty();
    }

    public static String defaultVoice(Context c) {
        String p = provider(c);
        if (EDGE.equals(p)) return "ru-RU-SvetlanaNeural";
        if (FREE.equals(p)) return "ru";
        if (ELEVEN.equals(p)) return "EXAVITQu4vr4xnSDxMaL";
        if (YANDEX.equals(p)) return "filipp";
        return "nova";
    }

    public static String voice(Context c) {
        String v = SettingsStore.p(c).getString("ttsCloudVoice", "").trim();
        return v.isEmpty() ? defaultVoice(c) : v;
    }

    // -------------------------------------------------------------- озвучка

    public void speak(String text, boolean flush) {
        if (text == null || text.trim().isEmpty()) {
            maybeDone();
            return;
        }
        cancelled = false;
        doneSent = false;
        synchronized (queue) {
            if (flush) {
                queue.clear();
                stopPlayback();
            }
            queue.add(text.trim());
        }
        startWorker();
    }

    public void stop() {
        cancelled = true;
        synchronized (queue) {
            queue.clear();
        }
        stopPlayback();
    }

    public void shutdown() {
        stop();
    }

    private void startWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(this::loop, "cloud-tts");
        worker.start();
    }

    private void loop() {
        while (true) {
            String text;
            synchronized (queue) {
                if (cancelled || queue.isEmpty()) {
                    worker = null;
                    maybeDone();
                    return;
                }
                text = queue.poll();
            }
            byte[] audio = null;
            String error = null;
            try {
                audio = synth(text);
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            if (cancelled) {
                worker = null;
                return;
            }
            if (audio == null || audio.length < 500) {
                worker = null;
                markFail();
                final String e = error == null ? L.s("пустой ответ сервера") : error;
                main.post(() -> listener.onFail(e));
                return;
            }
            markOk();
            boolean played = play(audio);
            if (!played) {
                worker = null;
                return;
            }
        }
    }

    private void maybeDone() {
        boolean empty;
        synchronized (queue) {
            empty = queue.isEmpty();
        }
        if (empty && !cancelled && !doneSent) {
            doneSent = true;
            main.post(listener::onDone);
        }
    }

    // --------------------------------------------------------------- запросы

    private byte[] synth(String text) throws Exception {
        String provider = provider(ctx);
        String key = SettingsStore.p(ctx).getString("ttsCloudKey", "").trim();
        String voice = voice(ctx);
        int rate = SettingsStore.ttsRate(ctx);
        float speed = Math.max(0.6f, Math.min(1.4f, rate / 100f));

        if (EDGE.equals(provider)) {
            // Edge отдаёт самый живой голос, но сервер иногда отвечает 403 —
            // тогда молча берём бесплатный Google-голос, а не робота из системы
            String tag = TtsSpeaker.languageTag(text);
            boolean voiceChosen = !SettingsStore.p(ctx)
                    .getString("ttsCloudVoice", "").trim().isEmpty();
            if (!edgeBlocked()) {
                String useVoice = voiceChosen ? voice : EdgeTts.defaultVoice(tag);
                try {
                    byte[] audio = EdgeTts.synth(text, useVoice, speed);
                    if (audio != null && audio.length >= 500) return audio;
                } catch (Throwable t) {
                    android.util.Log.d("NexusTts", "edge fail: " + t);
                    blockEdgeIfRefused(t);
                }
            }
            // v1.9.3: если человек выбрал конкретный голос, подменять его
            // «женщиной из переводчика» нельзя — иначе выбор голоса виден только в настройках.
            // Пусть тогда скажет выбранный голос движка телефона.
            if (!voiceChosen) {
                byte[] google2 = googleFree(text);
                if (google2 != null && google2.length >= 500) return google2;
            }
            throw new Exception(L.s("Edge временно недоступен — выбери другой движок озвучки"));
        }

        if (FREE.equals(provider)) {
            byte[] audio = googleFree(text);
            if (audio == null || audio.length < 500) throw new Exception(L.s("Google не ответил"));
            return audio;
        }

        if (ELEVEN.equals(provider)) {
            String id = URLEncoder.encode(voice, "UTF-8");
            String url = "https://api.elevenlabs.io/v1/text-to-speech/" + id
                    + "?output_format=mp3_44100_128";
            checkUrl(url);
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("text", text);
            body.put("model_id", "eleven_multilingual_v2");
            org.json.JSONObject vs = new org.json.JSONObject();
            vs.put("stability", 0.45);
            vs.put("similarity_boost", 0.8);
            vs.put("style", 0.0);
            vs.put("use_speaker_boost", true);
            body.put("voice_settings", vs);
            Request req = new Request.Builder().url(url)
                    .addHeader("xi-api-key", key)
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build();
            return bytes(req);
        }

        if (YANDEX.equals(provider)) {
            String folder = SettingsStore.p(ctx).getString("ttsCloudFolder", "").trim();
            String yUrl = "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize";
            checkUrl(yUrl);
            FormBody.Builder form = new FormBody.Builder()
                    .add("text", text.length() > 4000 ? text.substring(0, 4000) : text)
                    .add("lang", yandexLang(text))
                    .add("voice", voice)
                    .add("speed", String.format(java.util.Locale.US, "%.1f", speed))
                    .add("format", "mp3")
                    .add("emotion", "neutral");
            if (!folder.isEmpty()) form.add("folderId", folder);
            Request req = new Request.Builder()
                    .url(yUrl)
                    .addHeader("Authorization", "Api-Key " + key)
                    .post(form.build())
                    .build();
            return bytes(req);
        }

        // openai / совместимый шлюз
        String base = SettingsStore.p(ctx).getString("ttsCloudUrl", "").trim();
        if (base.isEmpty()) base = "https://api.openai.com/v1";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        checkUrl(base + "/audio/speech");
        String model = SettingsStore.p(ctx).getString("ttsCloudModel", "").trim();
        if (model.isEmpty()) model = "gpt-4o-mini-tts";
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("model", model);
        body.put("voice", voice);
        body.put("input", text);
        body.put("response_format", "mp3");
        body.put("speed", speed);
        if (model.startsWith("gpt-4o")) {
            // язык подсказки — язык самого текста, а не интерфейса
            body.put("instructions", TtsSpeaker.isEnglishText(text)
                    ? "Speak English warmly and naturally, no theatrics, at a calm pace."
                    : "Говори по-русски тепло, живо и естественно, "
                    + "без театральности, в спокойном темпе.");
        }
        Request req = new Request.Builder().url(base + "/audio/speech")
                .addHeader("Authorization", "Bearer " + key)
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json; charset=utf-8")))
                .build();
        return bytes(req);
    }

    /** перед любым запросом: http:// вне локальной сети — отказ с понятным текстом */
    private static void checkUrl(String url) throws Exception {
        String bad = LlmClient.insecureUrl(url);
        if (bad != null) throw new Exception(bad);
    }

    /** язык синтеза из текста: у Яндекса поддерживаются ru-RU и en-US */
    private static String yandexLang(String text) {
        String tag = TtsSpeaker.languageTag(text);
        if (tag != null && tag.toLowerCase(java.util.Locale.ROOT).startsWith("en")) return "en-US";
        return "ru-RU";
    }

    /** Google-переводчик отдаёт mp3 без ключа; режем текст на куски по ~180 символов. */
    private byte[] googleFree(String text) throws Exception {
        String tag = TtsSpeaker.languageTag(text);
        String tl = tag == null
                ? java.util.Locale.getDefault().getLanguage()
                : tag.substring(0, 2).toLowerCase(java.util.Locale.ROOT);
        if (tl.isEmpty()) tl = "ru";
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        for (String chunk : splitForTts(text, 180)) {
            String url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl="
                    + tl + "&q=" + URLEncoder.encode(chunk, "UTF-8");
            checkUrl(url);
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) NexusSpeak")
                    .addHeader("Referer", "https://translate.google.com/")
                    .get().build();
            byte[] part = bytes(req);
            if (part != null && part.length > 0) all.write(part);
        }
        return all.toByteArray();
    }

    private static java.util.List<String> splitForTts(String text, int limit) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String rest = text.replaceAll("\\s+", " ").trim();
        while (rest.length() > limit) {
            int cut = rest.lastIndexOf(' ', limit);
            if (cut < 40) cut = limit;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private byte[] bytes(Request req) throws Exception {
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                String hint = "";
                try {
                    String s = resp.body() == null ? "" : resp.body().string();
                    hint = s.length() > 160 ? s.substring(0, 160) : s;
                } catch (Throwable ignored) {
                }
                throw new Exception("HTTP " + resp.code() + (hint.isEmpty() ? "" : " · " + hint));
            }
            return resp.body().bytes();
        }
    }

    private boolean play(byte[] audio) {
        try {
            // уникальный файл: два одновременных ответа больше не перетирают озвучку
            File f = File.createTempFile("tts-", ".mp3", ctx.getCacheDir());
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(audio);
            }
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setVolume(volume, volume);
            mp.setOnCompletionListener(p -> {
                fadeThenRelease(p, done);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                p.release();
                done.countDown();
                return true;
            });
            player = mp;
            mp.prepare();
            if (cancelled) {
                mp.release();
                player = null;
                f.delete();
                return false;
            }
            // v1.9.3: без плавного входа и выхода MediaPlayer щёлaет в динамик
            // началом буфера и резким release() на хвосте.
            mp.setVolume(0f, 0f);
            mp.start();
            fade(mp, 0f, volume, 70);
            playLatch = done;
            done.await(120, TimeUnit.SECONDS);
            playLatch = null;
            player = null;
            f.delete();
            return !cancelled;
        } catch (Throwable t) {
            player = null;
            return false;
        }
    }

    private void stopPlayback() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            fade(mp, volume, 0f, 40);
            main.postDelayed(() -> {
                try { mp.stop(); } catch (Throwable ignored) {}
                try { mp.release(); } catch (Throwable ignored) {}
            }, 60);
        }
        // без этого воркер висел в await() до двух минут и очередь не играла
        java.util.concurrent.CountDownLatch l = playLatch;
        if (l != null) l.countDown();
    }

    /** плавно меняем громкость MediaPlayer: убирает щелчки на старте и стопе */
    private void fade(final MediaPlayer mp, final float from, final float to, int ms) {
        final int steps = Math.max(2, ms / 15);
        for (int i = 1; i <= steps; i++) {
            final float k = i / (float) steps;
            main.postDelayed(() -> {
                try { mp.setVolume(from + (to - from) * k, from + (to - from) * k); }
                catch (Throwable ignored) {}
            }, (long) (ms * k));
        }
    }

    private void fadeThenRelease(final MediaPlayer mp, final java.util.concurrent.CountDownLatch done) {
        fade(mp, volume, 0f, 45);
        main.postDelayed(() -> {
            try { mp.release(); } catch (Throwable ignored) {}
            done.countDown();
        }, 55);
    }
}
