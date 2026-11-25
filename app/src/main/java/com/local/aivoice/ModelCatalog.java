package com.local.aivoice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fetches the provider's model catalog with capabilities and pricing. */
public final class ModelCatalog {

    public static class Info {
        public String id, name;
        public boolean image;      // accepts image input
        public double pin, pout;   // per million tokens
        public String currency = "RUB";
    }

    public interface Cb { void onDone(List<Info> models, String error); }

    public static void fetch(String url, String key, Cb cb) {
        // Каталог идёт с тем же ключом, что и чат: открытый http:// вне локальной
        // сети здесь так же недопустим, как и в LlmClient.
        String insecure = LlmClient.insecureUrl(url);
        if (insecure != null) {
            cb.onDone(null, insecure);
            return;
        }
        new Thread(() -> {
            List<Info> out = new ArrayList<>();
            try {
                OkHttpClient http = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS).build();
                JSONObject root = null;
                String lastError = null;
                for (String base : Providers.candidateBases(url)) {
                    try {
                        Request req = new Request.Builder().url(base + "/models")
                                .header("Authorization", "Bearer " + key).build();
                        try (Response r = http.newCall(req).execute()) {
                            if (!r.isSuccessful()) {
                                String body = "";
                                try {
                                    body = r.body() != null ? r.body().string() : "";
                                } catch (Throwable ignored) {
                                }
                                String h = LlmClient.hint(r.code());
                                lastError = "HTTP " + r.code() + (h != null ? " — " + h : "")
                                        + (body.isEmpty() ? "" : "\n" + body.substring(0,
                                                Math.min(300, body.length())));
                                continue;
                            }
                            root = new JSONObject(r.body().string());
                            break;
                        }
                    } catch (Exception e) {
                        lastError = String.valueOf(e);
                    }
                }
                if (root == null) throw new Exception(lastError == null
                        ? L.s("Не удалось прочитать каталог моделей") : lastError);
                {
                    {
                    JSONArray arr = root.optJSONArray("data");
                    if (arr == null) throw new Exception(L.s("Нет поля data"));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.optJSONObject(i);
                        if (m == null) continue;
                        Info f = new Info();
                        f.id = m.optString("id");
                        f.name = m.optString("name", f.id);
                        JSONArray in = m.optJSONArray("input_modalities");
                        if (in == null) {
                            in = m.optJSONObject("architecture") != null
                                    ? m.getJSONObject("architecture").optJSONArray("input_modalities")
                                    : null;
                        }
                        if (in != null) {
                            for (int j = 0; j < in.length(); j++)
                                if ("image".equalsIgnoreCase(in.optString(j))) f.image = true;
                        }
                        if (in == null) {
                            // fallback heuristic
                            String idl = f.id.toLowerCase(java.util.Locale.ROOT);
                            f.image = idl.contains("vision") || idl.contains("gemini")
                                    || idl.contains("gpt-4o") || idl.contains("claude");
                        }
                        JSONObject pr = m.optJSONObject("pricing");
                        if (pr == null && m.optJSONObject("top_provider") != null) {
                            pr = m.getJSONObject("top_provider").optJSONObject("pricing");
                        }
                        if (pr != null) {
                            String cur = pr.optString("currency", "");
                            if (pr.has("prompt_per_million")) {
                                f.pin = parse(pr.optString("prompt_per_million"));
                                f.pout = parse(pr.optString("completion_per_million"));
                                f.currency = cur.isEmpty() ? "RUB" : cur;
                            } else if (pr.has("prompt") || pr.has("completion")) {
                                // OpenRouter и совместимые: цена за один токен в долларах
                                f.pin = parse(pr.optString("prompt")) * 1_000_000;
                                f.pout = parse(pr.optString("completion")) * 1_000_000;
                                f.currency = "USD";
                            }
                        }
                        out.add(f);
                    }
                    }
                }
                post(cb, out, null);
            } catch (Exception e) {
                post(cb, null, String.valueOf(e));
            }
        }).start();
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return -1; }
    }

    public static String priceStr(Info f) {
        if (f.pin < 0) return "";
        String sym = "RUB".equalsIgnoreCase(f.currency) ? "₽" : "$";
        return String.format("  ·  %.2f%s→%.2f%s /1M", f.pin, sym, f.pout, sym);
    }

    private static void post(Cb cb, List<Info> l, String err) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> cb.onDone(l, err));
    }

    private ModelCatalog() {}
}
