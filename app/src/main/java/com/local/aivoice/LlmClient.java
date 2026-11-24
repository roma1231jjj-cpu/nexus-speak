package com.local.aivoice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LlmClient {
    /** token usage of the last completed call (bg thread, read right after callback) */
    public static volatile int lastPromptTokens, lastCompletionTokens;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public interface Callback {
        void onResult(String text, String error);
    }

    /** streaming callback: partial text arrives as it is generated */
    public interface StreamCb {
        void onDelta(String chunk);
        void onDone(String fullText, String error);
    }

    /** messages: [role, content] pairs, oldest first; sends as-is (system added separately) */
    public static void chat(String url, String key, String model, String systemPrompt,
                            List<String[]> messages, Callback cb) {
        doChat(url, key, model, systemPrompt, messages, null, cb);
    }

    /** vision: last user message content should be plain text; imageDataUrl optional */
    public static void chatVision(String url, String key, String model, String systemPrompt,
                                  List<String[]> messages, String imageDataUrl, Callback cb) {
        doChat(url, key, model, systemPrompt, messages, imageDataUrl, cb);
    }

    /** Открытый HTTP допустим только для локальной сети — иначе ключ уйдёт в эфир. */
    public static String insecureUrl(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        if (!u.startsWith("http://")) return null;
        String host = u.substring(7);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        if (host.equals("localhost") || host.endsWith(".local") || host.equals("::1")) return null;
        // частные диапазоны 10/8, 172.16/12, 192.168/16, 127/8
        String[] p = host.split("\\.");
        if (p.length == 4) {
            try {
                int a = Integer.parseInt(p[0]);
                int b = Integer.parseInt(p[1]);
                if (a == 10 || a == 127) return null;
                if (a == 192 && b == 168) return null;
                if (a == 172 && b >= 16 && b <= 31) return null;
            } catch (NumberFormatException ignored) {
            }
        }
        return L.s("Адрес без шифрования (http://) разрешён только для локальной сети. "
                + "Для интернета нужен https://");
    }

    /** понятная ошибка вместо «HTTP 401» когда провайдер вообще не настроен */
    public static String notConfigured(String url, String key, String model) {
        if (url == null || url.trim().isEmpty())
            return L.s("Провайдер не выбран. Настройки → «Выбрать провайдера».");
        if (key == null || key.trim().isEmpty())
            return L.s("Нет API-ключа. Настройки → вставь ключ провайдера.");
        if (model == null || model.trim().isEmpty())
            return L.s("Модель не выбрана. Настройки → «Модель для ответов».");
        return null;
    }

    /** технические исключения превращаем в человеческий текст */
    public static String friendly(String err) {
        if (err == null) return null;
        String e = err.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("expected url scheme") || e.contains("illegalargumentexception"))
            return L.s("Адрес провайдера выглядит битым. Настройки → «API» → вставь адрес заново "
                    + "(например https://polza.ai/api/v1).");
        if (e.contains("unable to resolve host") || e.contains("unknownhost"))
            return L.s("Не нашёл сервер провайдера. Проверь интернет и адрес в настройках.");
        if (e.contains("timeout") || e.contains("timed out"))
            return L.s("Провайдер не ответил вовремя. Попробуй ещё раз.");
        if (e.contains("connection refused") || e.contains("failed to connect"))
            return L.s("Сервер провайдера не отвечает. Проверь адрес в настройках.");
        return null;
    }

    /** короткая расшифровка частых кодов */
    public static String hint(int code) {
        switch (code) {
            case 400: return "провайдер отверг запрос — чаще всего неверная или пустая модель";
            case 401: return "неверный API-ключ";
            case 403: return "ключ без доступа к этой модели";
            case 404: return "неверный адрес провайдера";
            case 429: return "лимит запросов, попробуй позже";
            case 500: case 502: case 503: return "ошибка на стороне провайдера";
            default: return null;
        }
    }

    private static void doChat(String url, String key, String model, String systemPrompt,
                               List<String[]> messages, String imageDataUrl, Callback cb) {
        final String fUrl = url == null ? "" : url.trim();
        final String fKey = key == null ? "" : key.trim();
        final String fModel = model == null ? "" : model.trim();
        new Thread(() -> {
            try {
                String bad = insecureUrl(fUrl);
                if (bad != null) { post(cb, null, bad); return; }
                JSONObject body = new JSONObject();
                body.put("model", fModel);
                body.put("stream", false);
                lastPromptTokens = -1;
                lastCompletionTokens = -1;
                JSONArray msgs = new JSONArray();
                if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                    msgs.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                }
                for (int mi = 0; mi < messages.size(); mi++) {
                    String[] m = messages.get(mi);
                    if (imageDataUrl != null && "user".equals(m[0]) && mi == messages.size() - 1) {
                        JSONArray content = new JSONArray();
                        content.put(new JSONObject()
                                .put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", imageDataUrl)));
                        content.put(new JSONObject().put("type", "text").put("text", m[1]));
                        msgs.put(new JSONObject().put("role", "user").put("content", content));
                    } else {
                        msgs.put(new JSONObject().put("role", m[0]).put("content", m[1]));
                    }
                }
                body.put("messages", msgs);

                Request req = new Request.Builder()
                        .url(trimUrl(fUrl) + "/chat/completions")
                        .header("Authorization", "Bearer " + fKey)
                        .post(RequestBody.create(body.toString(),
                                MediaType.parse("application/json; charset=utf-8")))
                        .build();

                try (Response resp = CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        String detail = "";
                        try {
                            detail = resp.body() != null ? resp.body().string() : "";
                        } catch (Throwable ignored) {
                        }
                        String err = "HTTP " + resp.code() + " (" + trimUrl(fUrl)
                                + "/chat/completions, модель «" + fModel + "»)";
                        String h = hint(resp.code());
                        if (h != null) err += "\n" + h;
                        if (!detail.isEmpty()) err += "\n" + abbrev(detail);
                        post(cb, null, err);
                        return;
                    }
                    String json = resp.body() != null ? resp.body().string() : "";
                    JSONObject root = new JSONObject(json);
                    String content = root.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message")
                            .getString("content");
                    try {
                        JSONObject u = root.optJSONObject("usage");
                        if (u != null) {
                            lastPromptTokens = u.optInt("prompt_tokens", -1);
                            lastCompletionTokens = u.optInt("completion_tokens", -1);
                        }
                    } catch (Exception ignored) {
                    }
                    post(cb, content.trim(), null);
                }
            } catch (Exception e) {
                post(cb, null, friendly(String.valueOf(e)) != null ? friendly(String.valueOf(e)) : abbrev(String.valueOf(e)));
            }
        }).start();
    }

    /**
     * Same request as chat() but with stream=true. Deltas are delivered on the
     * main thread; onDone carries the whole text (or an error).
     */
    public static void chatStream(String url, String key, String model, String systemPrompt,
                                  List<String[]> messages, StreamCb cb) {
        streamOnce(url, key, model, systemPrompt, messages, null, cb, false);
    }

    public static void chatStreamVision(String url, String key, String model, String systemPrompt,
                                        List<String[]> messages, String imageDataUrl, StreamCb cb) {
        streamOnce(url, key, model, systemPrompt, messages, imageDataUrl, cb, false);
    }

    private static void streamOnce(String url, String key, String model, String systemPrompt,
                                   List<String[]> messages, String imageDataUrl, StreamCb cb,
                                   boolean retried) {
        final String fUrl = url == null ? "" : url.trim();
        final String fKey = key == null ? "" : key.trim();
        final String fModel = model == null ? "" : model.trim();
        new Thread(() -> {
            StringBuilder full = new StringBuilder();
            try {
                String bad = insecureUrl(fUrl);
                if (bad != null) { postStream(cb, null, bad); return; }
                JSONObject body = new JSONObject();
                body.put("model", fModel);
                body.put("stream", true);
                // просим провайдера прислать usage последним событием — иначе расход не считается
                body.put("stream_options", new JSONObject().put("include_usage", true));
                lastPromptTokens = -1;
                lastCompletionTokens = -1;
                JSONArray msgs = new JSONArray();
                if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                    msgs.put(new JSONObject().put("role", "system").put("content", systemPrompt));
                }
                for (int mi = 0; mi < messages.size(); mi++) {
                    String[] m = messages.get(mi);
                    if (imageDataUrl != null && "user".equals(m[0]) && mi == messages.size() - 1) {
                        JSONArray content = new JSONArray();
                        content.put(new JSONObject().put("type", "image_url")
                                .put("image_url", new JSONObject().put("url", imageDataUrl)));
                        content.put(new JSONObject().put("type", "text").put("text", m[1]));
                        msgs.put(new JSONObject().put("role", "user").put("content", content));
                    } else {
                        msgs.put(new JSONObject().put("role", m[0]).put("content", m[1]));
                    }
                }
                body.put("messages", msgs);

                Request req = new Request.Builder()
                        .url(trimUrl(fUrl) + "/chat/completions")
                        .header("Authorization", "Bearer " + fKey)
                        .header("Accept", "text/event-stream")
                        .post(RequestBody.create(body.toString(),
                                MediaType.parse("application/json; charset=utf-8")))
                        .build();

                try (Response resp = CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        String err = "HTTP " + resp.code();
                        if (resp.body() != null) err += ": " + abbrev(resp.body().string());
                        if (!retried) {
                            // provider without SSE support -> fall back to a normal call
                            chat(url, key, model, systemPrompt, messages, (t, e2) ->
                                    postStream(cb, t, e2));
                            return;
                        }
                        postStream(cb, null, abbrev(err));
                        return;
                    }
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(resp.body().byteStream(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) continue;
                        try {
                            JSONObject o = new JSONObject(data);
                            JSONObject u = o.optJSONObject("usage");
                            if (u != null) {
                                lastPromptTokens = u.optInt("prompt_tokens", -1);
                                lastCompletionTokens = u.optInt("completion_tokens", -1);
                            }
                            JSONArray ch = o.optJSONArray("choices");
                            if (ch == null || ch.length() == 0) continue;
                            JSONObject delta = ch.getJSONObject(0).optJSONObject("delta");
                            if (delta == null) continue;
                            if (delta.isNull("content")) continue;
                            Object raw = delta.opt("content");
                            String piece = raw instanceof String ? (String) raw : "";
                            if (piece.isEmpty() || "null".equals(piece)) continue;
                            full.append(piece);
                            final String p = piece;
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                    .post(() -> cb.onDelta(p));
                        } catch (Exception ignored) {
                        }
                    }
                    String out = full.toString().trim();
                    if (out.isEmpty()) {
                        if (!retried) {
                            chat(url, key, model, systemPrompt, messages, (t, e2) ->
                                    postStream(cb, t, e2));
                            return;
                        }
                        postStream(cb, null, "пустой потоковый ответ");
                        return;
                    }
                    postStream(cb, out, null);
                }
            } catch (Exception e) {
                if (!retried) {
                    chat(url, key, model, systemPrompt, messages, (t, e2) -> postStream(cb, t, e2));
                } else {
                    postStream(cb, null, abbrev(String.valueOf(e)));
                }
            }
        }).start();
    }

    private static void postStream(StreamCb cb, String text, String err) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> cb.onDone(text, err));
    }

    private static String trimUrl(String u) {
        u = u.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String abbrev(String s) {
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    private static void post(Callback cb, String text, String err) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> cb.onResult(text, err));
    }
}
