package com.local.aivoice;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Бесплатные нейроголоса Microsoft Edge (те же, что в Azure Speech):
 * русские Svetlana и Dmitry. Ключ и аккаунт не нужны.
 *
 * Работает по тому же протоколу, что и «Читать вслух» в браузере Edge:
 * WebSocket → конфиг → SSML → mp3 потоком.
 *
 * TrustedClientToken больше не зашит в код: старый постоянный ключ сервер
 * отвергает, а свежий выдаётся только самому клиенту Edge. Поэтому запрос
 * уходит без него, и если сервер отвечает отказом — возвращаем честную
 * ошибку, а не пустой «успех».
 */
final class EdgeTts {

    /** сервер ответил отказом (401/403/404) — это повод не пытаться снова, но не повод врать */
    static final class Refused extends Exception {
        final int code;
        Refused(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final String WS_BASE =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";

    /** голос по умолчанию для языка текста */
    static String defaultVoice(String langTag) {
        String t = langTag == null ? "" : langTag.toLowerCase(Locale.ROOT);
        if (t.startsWith("en")) return "en-US-AriaNeural";
        if (t.startsWith("de")) return "de-DE-KatjaNeural";
        if (t.startsWith("fr")) return "fr-FR-DeniseNeural";
        if (t.startsWith("es")) return "es-ES-ElviraNeural";
        return "ru-RU-SvetlanaNeural";
    }

    /** https/wss — шифрованные; ws:// допустим только в локальной сети */
    private static String insecurity(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("wss://") || u.startsWith("https://")) return null;
        if (u.startsWith("ws://")) return LlmClient.insecureUrl("http://" + u.substring(5));
        if (u.startsWith("http://")) return LlmClient.insecureUrl(u);
        return L.s("Неподдерживаемый адрес синтеза речи");
    }

    static byte[] synth(String text, String voice, float speed) throws Exception {
        if (text == null || text.trim().isEmpty()) return null;
        String connectionId = UUID.randomUUID().toString().replace("-", "");
        String url = WS_BASE + "?ConnectionId=" + connectionId;
        String bad = insecurity(url);
        if (bad != null) throw new Exception(bad);

        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1);
        final String[] error = {null};
        final int[] refused = {0};
        final String langTag = langOf(voice, text);

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

        Request wsReq = new Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                        + "Safari/537.36 Edg/130.0.0.0")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();
        WebSocket ws = http.newWebSocket(wsReq,
                new WebSocketListener() {
                    @Override public void onOpen(WebSocket webSocket, Response response) {
                        try {
                            webSocket.send(configMessage());
                            webSocket.send(ssmlMessage(text, voice, speed, langTag));
                        } catch (Throwable t) {
                            error[0] = t.toString();
                            done.countDown();
                        }
                    }

                    @Override public void onMessage(WebSocket webSocket, String text2) {
                        if (text2 != null && text2.contains("Path:turn.end")) {
                            try { webSocket.close(1000, "done"); } catch (Throwable ignored) {}
                            done.countDown();
                        }
                    }

                    @Override public void onMessage(WebSocket webSocket, okio.ByteString bytes) {
                        try {
                            byte[] raw = bytes.toByteArray();
                            int headerLen = ((raw[0] & 0xff) << 8) | (raw[1] & 0xff);
                            int start = 2 + headerLen;
                            if (raw.length <= start) return;
                            String header = new String(raw, 2, headerLen,
                                    java.nio.charset.StandardCharsets.UTF_8);
                            if (!header.contains("Path:audio")) return;
                            audio.write(raw, start, raw.length - start);
                        } catch (Throwable t) {
                            error[0] = t.toString();
                        }
                    }

                    @Override public void onFailure(WebSocket webSocket, Throwable t, Response r) {
                        int code = r == null ? 0 : r.code();
                        if (code == 401 || code == 403 || code == 404) refused[0] = code;
                        error[0] = t == null ? "соединение" : t.toString();
                        done.countDown();
                    }
                });

        boolean finished = done.await(12, TimeUnit.SECONDS);
        try { ws.cancel(); } catch (Throwable ignored) {}
        byte[] out = audio.toByteArray();
        if (out.length > 500) return out;
        if (refused[0] != 0) {
            throw new Refused(refused[0], L.s("Edge отклонил запрос (HTTP ") + refused[0] + ")"
                    + ". Он временно недоступен — выбери другой движок озвучки.");
        }
        if (!finished || error[0] != null) {
            throw new Exception(error[0] == null ? "нет ответа" : error[0]);
        }
        return out;
    }

    /** язык для SSML: префикс имени голоса ("en-US-AriaNeural"), иначе язык текста */
    private static String langOf(String voice, String text) {
        if (voice != null) {
            String v = voice.trim();
            if (v.length() > 6 && v.charAt(2) == '-' && v.indexOf('-', 3) > 3) {
                return v.substring(0, v.lastIndexOf('-'));
            }
        }
        String tag = TtsSpeaker.languageTag(text);
        return tag == null ? Locale.getDefault().toLanguageTag() : tag;
    }

    private static String stamp() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String configMessage() {
        return "X-Timestamp:" + stamp() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
    }

    private static String ssmlMessage(String text, String voice, float speed, String langTag) {
        String rate = speed >= 1.0f
                ? "+" + Math.round((speed - 1f) * 100) + "%"
                : "-" + Math.round((1f - speed) * 100) + "%";
        String escaped = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        String name = voice == null || voice.trim().isEmpty()
                ? defaultVoice(langTag) : voice.trim();
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' "
                + "xml:lang='" + langTag + "'><voice name='" + name + "'>"
                + "<prosody rate='" + rate + "' pitch='+0Hz'>" + escaped + "</prosody>"
                + "</voice></speak>";
        return "X-RequestId:" + UUID.randomUUID().toString().replace("-", "") + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + stamp() + "\r\n"
                + "Path:ssml\r\n\r\n" + ssml;
    }

    private EdgeTts() {}
}
