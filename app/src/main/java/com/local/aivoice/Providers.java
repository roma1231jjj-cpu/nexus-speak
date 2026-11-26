package com.local.aivoice;

import java.util.ArrayList;
import java.util.List;

/**
 * Известные OpenAI-совместимые провайдеры. Нужны только для удобной подстановки
 * адреса — приложение не привязано ни к одному из них.
 */
public final class Providers {

    public static class P {
        public final String name;
        public final String url;
        public final String keyUrl;
        public final String logo;   // файл в assets/logos (может быть null)

        P(String name, String url, String keyUrl, String logo) {
            this.name = name;
            this.url = url;
            this.keyUrl = keyUrl;
            this.logo = logo;
        }
    }

    public static final P[] KNOWN = {
            new P("Polza.ai", "https://polza.ai/api/v1", "https://polza.ai", "deepseek.png"),
            new P("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", "openai.png"),
            new P("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys", "openai.png"),
            new P("DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys", "deepseek.png"),
            new P("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", "openai.png"),
            new P("Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys", "mistral.png"),
            new P("Together AI", "https://api.together.xyz/v1", "https://api.together.ai/settings/api-keys", "openai.png"),
            new P("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                    "https://aistudio.google.com/apikey", "gemini.png"),
            new P("Local server (LM Studio / Ollama)", "http://192.168.1.2:1234/v1", null, null),
    };

    public static P byUrl(String url) {
        if (url == null) return null;
        String u = norm(url);
        for (P p : KNOWN) {
            if (norm(p.url).equals(u)) return p;
        }
        return null;
    }

    /** угадываем провайдера по домену — даже если адрес введён вручную */
    public static String detectName(String url) {
        P p = byUrl(url);
        if (p != null) return p.name;
        String host = host(url);
        if (host == null) return L.s("Свой провайдер");
        if (host.contains("polza")) return "Polza.ai";
        if (host.contains("openrouter")) return "OpenRouter";
        if (host.contains("openai")) return "OpenAI";
        if (host.contains("deepseek")) return "DeepSeek";
        if (host.contains("groq")) return "Groq";
        if (host.contains("mistral")) return "Mistral";
        if (host.contains("together")) return "Together AI";
        if (host.contains("googleapis") || host.contains("gemini")) return "Google Gemini";
        if (host.contains("anthropic")) return "Anthropic";
        if (host.contains("localhost") || host.startsWith("192.168.")
                || host.startsWith("10.") || host.contains("127.0.0.1")) return L.s("Локальный сервер");
        return L.s("Свой провайдер");
    }

    public static String host(String url) {
        try {
            String u = url.trim();
            if (!u.contains("://")) u = "https://" + u;
            return java.net.URI.create(u).getHost();
        } catch (Throwable t) {
            return null;
        }
    }

    /** «chttp://…», пробелы, лишний текст вокруг ссылки — приводим к нормальному виду */
    public static String sanitize(String u) {
        if (u == null) return "";
        String s = u.trim().replace(" ", "");
        s = s.replace(String.valueOf((char) 10), "").replace(String.valueOf((char) 9), "");
        int i = s.indexOf("http://");
        int j = s.indexOf("https://");
        int k = i < 0 ? j : (j < 0 ? i : Math.min(i, j));
        if (k > 0) s = s.substring(k);
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        return s;
    }

    public static String norm(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** варианты адреса каталога моделей: с /v1 и без */
    public static List<String> candidateBases(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        List<String> out = new ArrayList<>();
        if (u.isEmpty()) return out;
        out.add(u);
        if (!u.endsWith("/v1")) out.add(u + "/v1");
        // если вставили полный адрес /chat/completions — отрежем хвост
        if (u.endsWith("/chat/completions")) {
            String b = u.substring(0, u.length() - "/chat/completions".length());
            out.add(0, b);
        }
        return out;
    }

    private Providers() {}
}
