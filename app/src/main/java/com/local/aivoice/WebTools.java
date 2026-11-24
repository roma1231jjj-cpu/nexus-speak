package com.local.aivoice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Web access for the assistant: DuckDuckGo search + page reading. */
public final class WebTools {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private WebTools() {}

    /** Returns top results as "title | url | snippet" lines. */
    public static String search(String query) {
        try {
            String html = get("https://lite.duckduckgo.com/lite/?q=" +
                    java.net.URLEncoder.encode(query, "UTF-8"), null);
            List<String[]> out = new ArrayList<>();
            Matcher m = Pattern.compile(
                    "<a rel=\"nofollow\" href=\"([^\"]+)\" class='result-link'>(.*?)</a>",
                    Pattern.DOTALL).matcher(html);
            Matcher s = Pattern.compile(
                    "class='result-snippet'>(.*?)</td>", Pattern.DOTALL).matcher(html);
            List<String> snippets = new ArrayList<>();
            while (s.find()) snippets.add(strip(s.group(1)));
            int i = 0;
            while (m.find() && out.size() < 5) {
                String url = m.group(1);
                String title = strip(m.group(2));
                String snip = i < snippets.size() ? snippets.get(i) : "";
                i++;
                if (title.isEmpty()) continue;
                out.add(new String[]{title, url, snip});
            }
            if (out.isEmpty()) return "Ничего не найдено в поиске.";
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < out.size(); j++) {
                sb.append(j + 1).append(". ").append(out.get(j)[0]).append(" | ")
                        .append(out.get(j)[1]).append(" | ").append(out.get(j)[2]).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Ошибка поиска: " + e;
        }
    }

    /** Fetches a page and returns readable text (limit ~4000 chars). */
    public static String openUrl(String url) {
        try {
            String low = url.trim().toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("intent:") || low.startsWith("javascript:")
                    || low.startsWith("data:") || low.startsWith("file:")) {
                return "Такую ссылку открывать не буду.";
            }
            if (!low.startsWith("http://") && !low.startsWith("https://")) url = "https://" + url;
            String html = get(url, null);
            String text = strip(html);
            text = text.replaceAll("\\n{3,}", "\n\n").trim();
            if (text.length() > 4000) text = text.substring(0, 4000) + " …(обрезано)";
            return text;
        } catch (Exception e) {
            return "Ошибка открытия страницы: " + e;
        }
    }

    private static String get(String url, String referer) throws Exception {
        Request.Builder b = new Request.Builder().url(url)
                .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; Pixel) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        if (referer != null) b.header("Referer", referer);
        try (Response r = HTTP.newCall(b.build()).execute()) {
            return r.body() != null ? r.body().string() : "";
        }
    }

    private static String strip(String html) {
        html = html.replaceAll("(?s)<script.*?</script>", " ")
                .replaceAll("(?s)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
        return html.replaceAll("[ \\t]{2,}", " ").trim();
    }
}
