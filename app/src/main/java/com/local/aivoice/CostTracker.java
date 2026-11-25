package com.local.aivoice;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Tracks rough spend per day using cached model prices (₽/1M tokens). */
public final class CostTracker {

    /** Caches {id: [pin,pout]} from the last successful catalog fetch. */
    public static synchronized void cachePrices(Context c, java.util.List<ModelCatalog.Info> models) {
        try {
            JSONObject root = new JSONObject();
            for (ModelCatalog.Info f : models) {
                root.put(f.id, new JSONArray().put(f.pin).put(f.pout));
            }
            SettingsStore.p(c).edit().putString("catalogCache", root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Adds the cost of the last LLM call, using the model that really answered. */
    public static synchronized void addLast(Context c) {
        LlmClient.Usage usage = LlmClient.takeUsage();
        if (usage == null || usage.prompt < 0) return;
        add(c, usage.model, usage.prompt, usage.completion);
    }

    /** Kept for older call sites: the model argument is ignored on purpose. */
    public static synchronized void addLast(Context c, String ignoredModel) {
        addLast(c);
    }

    private static void add(Context c, String model, int promptTokens, int completionTokens) {
        try {
            String cache = SettingsStore.p(c).getString("catalogCache", null);
            if (cache == null) return;
            JSONObject root = new JSONObject(cache);
            JSONArray arr = root.optJSONArray(model);
            if (arr == null) return;
            double pin = arr.optDouble(0, -1), pout = arr.optDouble(1, -1);
            if (pin < 0) return;
            double cost = pin * promptTokens / 1e6
                    + pout * completionTokens / 1e6;
            String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date());
            String key = "spend_" + day;
            double prev = SettingsStore.p(c).getFloat(key, 0f);
            SettingsStore.p(c).edit().putFloat(key, (float) (prev + cost)).apply();
        } catch (Exception ignored) {
        }
    }

    public static synchronized String todaySummary(Context c) {
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        float v = SettingsStore.p(c).getFloat("spend_" + day, 0f);
        if (v <= 0) return L.s("Расход сегодня: ~0 ₽");
        return String.format(java.util.Locale.US, L.s("Расход сегодня: ~%.2f ₽"), v);
    }

    private CostTracker() {}
}
