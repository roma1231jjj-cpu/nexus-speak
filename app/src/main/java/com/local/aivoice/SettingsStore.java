package com.local.aivoice;

import android.content.Context;
import android.content.SharedPreferences;

public class SettingsStore {
    // никаких ключей и провайдеров в сборке: всё задаётся пользователем
    public static final String DEFAULT_URL = "";
    public static final String DEFAULT_KEY = "";
    public static final String DEFAULT_MODEL = "";
    public static final String DEFAULT_TEXT_FALLBACK = "";
    public static final String DEFAULT_VISION_MODEL = "";
    public static final String DEFAULT_VISION_FALLBACK = "";
    /** persona prompt: empty = «Нексус (по умолчанию)» */
    public static final String DEFAULT_SYSTEM = "";

    public static SharedPreferences p(Context c) {
        return c.getSharedPreferences("settings", 0);
    }

    public static String defaultLang() {
        String code = java.util.Locale.getDefault().getLanguage();
        return code != null && code.toLowerCase(java.util.Locale.US).startsWith("ru") ? "ru" : "en";
    }

    public static String lang(Context c) {
        String v = p(c).getString("lang", "");
        if (v == null || v.isEmpty()) return defaultLang();
        return "en".equals(v) ? "en" : "ru";
    }

    public static void setLang(Context c, String code) {
        p(c).edit().putString("lang", "en".equals(code) ? "en" : "ru").apply();
    }

    public static String url(Context c) {
        return Providers.sanitize(p(c).getString("url", DEFAULT_URL));
    }
    public static String key(Context c) { return p(c).getString("key", DEFAULT_KEY).trim(); }
    public static String model(Context c) {
        String m = p(c).getString("model", DEFAULT_MODEL).trim();
        // если основная не выбрана, а резервная есть — работать на резервной,
        // а не показывать «Модель не выбрана»
        if (m.isEmpty()) m = p(c).getString("textFallback", DEFAULT_TEXT_FALLBACK).trim();
        return m;
    }

    public static String visionModel(Context c) {
        String m = p(c).getString("visionModel", DEFAULT_VISION_MODEL).trim();
        if (m.isEmpty()) m = p(c).getString("visionFallback", DEFAULT_VISION_FALLBACK).trim();
        if (m.isEmpty()) m = model(c);
        return m;
    }
    public static String textFallback(Context c) { return p(c).getString("textFallback", DEFAULT_TEXT_FALLBACK).trim(); }
    public static String visionFallback(Context c) { return p(c).getString("visionFallback", DEFAULT_VISION_FALLBACK).trim(); }
    public static boolean visionEnabled(Context c) { return p(c).getBoolean("visionEnabled", true); }
    public static String system(Context c) { return p(c).getString("system", DEFAULT_SYSTEM); }
    public static boolean wakeEnabled(Context c) { return p(c).getBoolean("wakeEnabled", false); }

    public static String updateUrl(Context c) {
        return p(c).getString("updateUrl", "https://raw.githubusercontent.com/roma1231jjj-cpu/nexus-speak/main/update.json");
    }

    public static boolean autoUpdate(Context c) { return p(c).getBoolean("autoUpdate", true); }

    public static boolean keepScreenOn(Context c) { return p(c).getBoolean("keepScreenOn", false); }
    public static int ttsRate(Context c) { return p(c).getInt("ttsRate", 100); }

    public static int ttsPitch(Context c) { return p(c).getInt("ttsPitch", 100); }

    public static String ttsVoice(Context c) { return p(c).getString("ttsVoice", ""); }

    public static void setVoiceShape(Context c, int rate, int pitch, String voiceName) {
        p(c).edit().putInt("ttsRate", rate).putInt("ttsPitch", pitch)
                .putString("ttsVoice", voiceName == null ? "" : voiceName).apply();
    }

    public static int voiceVolume(Context c) { return p(c).getInt("voiceVolume", 100); }
    public static String ttsEngine(Context c) { return p(c).getString("ttsEngine", ""); }

    /** постоянная память о пользователе (третий промпт, редактируемый) */
    /**
     * Секрет процесса для внутренних запусков ассистента. Экран ассистента
     * экспортирован (иначе система не отдаст ему ASSIST), поэтому текст запроса
     * принимается только вместе с этим токеном: чужое приложение его не знает.
     */
    public static String internalToken(Context c) {
        SharedPreferences sp = p(c);
        String token = sp.getString("internalToken", null);
        if (token == null || token.length() < 16) {
            token = java.util.UUID.randomUUID().toString();
            sp.edit().putString("internalToken", token).apply();
        }
        return token;
    }

    public static String userInfo(Context c) { return p(c).getString("userInfo", ""); }

    public static void setUserInfo(Context c, String v) {
        p(c).edit().putString("userInfo", v == null ? "" : v).apply();
    }

    public static boolean announceNotifications(Context c) {
        return p(c).getBoolean("announceNotifications", false);
    }

    public static boolean announceCalls(Context c) {
        return p(c).getBoolean("announceCalls", false);
    }

    public static void setAnnounce(Context c, boolean notifications, boolean calls) {
        p(c).edit()
                .putBoolean("announceNotifications", notifications)
                .putBoolean("announceCalls", calls)
                .apply();
    }

    public static boolean callAnswer(Context c) { return p(c).getBoolean("callAnswer", false); }

    public static void setCallAnswer(Context c, boolean on) {
        p(c).edit().putBoolean("callAnswer", on).apply();
    }

    public static boolean callAutoHangup(Context c) { return p(c).getBoolean("callHangup", true); }

    public static int callAnswerDelay(Context c) { return p(c).getInt("callDelay", 6); }

    public static String callGreeting(Context c) {
        return p(c).getString("callGreeting",
                "Здравствуйте! Это Нексус, ассистент владельца телефона. "
                        + "Он сейчас не может ответить. Пожалуйста, перезвоните позже или напишите сообщение.");
    }

    public static void setCallPrefs(Context c, String greeting, int delay, boolean hangup) {
        p(c).edit()
                .putString("callGreeting", greeting == null ? "" : greeting)
                .putInt("callDelay", Math.max(2, Math.min(30, delay)))
                .putBoolean("callHangup", hangup)
                .apply();
    }

    /** AMOLED: чистый чёрный фон */
    public static boolean amoled(Context c) { return p(c).getBoolean("amoled", false); }

    public static void setAmoled(Context c, boolean on) {
        p(c).edit().putBoolean("amoled", on).apply();
    }

    public static boolean announceQuiet(Context c) {
        return p(c).getBoolean("announceQuiet", true);
    }

    public static void setAnnounceQuiet(Context c, boolean on) {
        p(c).edit().putBoolean("announceQuiet", on).apply();
    }

    /** сколько секунд ассистент ждёт продолжения после ответа (0 = закрыть сразу) */
    public static int talkWindow(Context c) { return p(c).getInt("talkWindow", 8); }

    public static void setTalkWindow(Context c, int seconds) {
        p(c).edit().putInt("talkWindow", Math.max(0, Math.min(120, seconds))).apply();
    }

    public static boolean autonomy(Context c) { return p(c).getBoolean("autonomy", true); }

    public static void setAutonomy(Context c, boolean on) {
        p(c).edit().putBoolean("autonomy", on).apply();
    }

    public static void save(Context c, String url, String key, String model, String visionModel,
                            String textFallback, String visionFallback,
                            boolean visionEnabled, String system, boolean wakeEnabled) {
        p(c).edit()
                .putString("url", url)
                .putString("key", key)
                .putString("model", model)
                .putString("visionModel", visionModel)
                .putString("textFallback", textFallback)
                .putString("visionFallback", visionFallback)
                .putBoolean("visionEnabled", visionEnabled)
                .putString("system", system)
                .putBoolean("wakeEnabled", wakeEnabled)
                .apply();
    }

    public static void saveAssistantPrefs(Context c, boolean keepScreenOn,
                                          int voiceVolume, String ttsEngine) {
        p(c).edit()
                .putBoolean("keepScreenOn", keepScreenOn)
                .putInt("voiceVolume", Math.max(10, Math.min(100, voiceVolume)))
                .putString("ttsEngine", ttsEngine == null ? "" : ttsEngine.trim())
                .apply();
    }
}
