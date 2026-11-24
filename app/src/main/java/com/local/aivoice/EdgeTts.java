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
 */
final class EdgeTts {

    private static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String WS_BASE =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    /** без Sec-MS-GEC сервер отвечает 403 Forbidden — так было во всех версиях до 1.8.6 */
    private static final String GEC_VERSION = "1-130.0.2849.68";

    private static String secMsGec() {
        try {
            long ticks = System.currentTimeMillis() / 1000L + 11644473600L;
            ticks -= ticks % 300;
            ticks *= 10_000_000L;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((ticks + TRUSTED_TOKEN).getBytes("US-ASCII"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format(java.util.Locale.ROOT, "%02X", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static byte[] synth(String text, String voice, float speed) throws Exception {
        if (text == null || text.trim().isEmpty()) return null;
        String connectionId = UUID.randomUUID().toString().replace("-", "");
        String url = WS_BASE + "?TrustedClientToken=" + TRUSTED_TOKEN
                + "&Sec-MS-GEC=" + secMsGec()
                + "&Sec-MS-GEC-Version=" + GEC_VERSION
                + "&ConnectionId=" + connectionId;

        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1);
        final String[] error = {null};

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
                            webSocket.send(ssmlMessage(text, voice, speed));
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
                        error[0] = t == null ? "соединение" : t.toString();
                        done.countDown();
                    }
                });

        boolean finished = done.await(12, TimeUnit.SECONDS);
        try { ws.cancel(); } catch (Throwable ignored) {}
        byte[] out = audio.toByteArray();
        if (out.length > 500) return out;
        if (!finished || error[0] != null) {
            throw new Exception(error[0] == null ? "нет ответа" : error[0]);
        }
        return out;
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

    private static String ssmlMessage(String text, String voice, float speed) {
        String rate = speed >= 1.0f
                ? "+" + Math.round((speed - 1f) * 100) + "%"
                : "-" + Math.round((1f - speed) * 100) + "%";
        String escaped = text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        String name = voice == null || voice.trim().isEmpty()
                ? "ru-RU-SvetlanaNeural" : voice.trim();
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' "
                + "xml:lang='ru-RU'><voice name='" + name + "'>"
                + "<prosody rate='" + rate + "' pitch='+0Hz'>" + escaped + "</prosody>"
                + "</voice></speak>";
        return "X-RequestId:" + UUID.randomUUID().toString().replace("-", "") + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + stamp() + "\r\n"
                + "Path:ssml\r\n\r\n" + ssml;
    }

    private EdgeTts() {}
}
