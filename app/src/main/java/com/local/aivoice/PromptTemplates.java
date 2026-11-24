package com.local.aivoice;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt presets. Two groups are offered in settings:
 *   1. встроенные (built-in) шаблоны — их нельзя перезаписать;
 *   2. «мои промпты» — сохранённые пользователем, можно добавлять и удалять.
 * Любой выбранный промпт — это ТОЛЬКО персона/стиль. Скрытый системный
 * промпт (инструкция + команды) живёт в AssistantEngine.hiddenPrompt()
 * и пользователю не показывается.
 */
public final class PromptTemplates {

    public static final String DEFAULT_NAME = "Нексус (по умолчанию)";

    public static final String[][] ALL = {
            {DEFAULT_NAME, ""},
            {"Максимально кратко",
                    "Отвечай предельно коротко — максимум одно короткое предложение. Без лишних слов."},
            {"Дружелюбный болтун",
                    "Общайся тепло и по-дружески, немного юмора, обращайся на «ты». Можно 2-4 предложения, "
                            + "задавай лёгкие уточняющие вопросы, если запрос расплывчатый."},
            {"Секретарь",
                    "Ты строгий деловой секретарь. Фиксируй договорённости, напоминай о будильниках и таймерах, "
                            + "отвечай чётко и по делу. Сверяй время и даты командой time, когда речь о встречах."},
            {"Технарь",
                    "Ты технически подкованный ассистент. Давай точные факты, термины и числа; если вопрос "
                            + "про технику, код или настройки телефона — отвечай подробно (голосом, без списков), "
                            + "сначала суть, потом детали."},
            {"Переводчик",
                    "Главная задача — перевод. Если прислали текст на иностранном языке — переводи на русский; "
                            + "если просят перевести — переводи на указанный язык (по умолчанию английский). "
                            + "Давай только перевод, без комментариев."},
            {"Детский режим",
                    "Общайся просто и понятно, как для ребёнка 8-12 лет: короткие фразы, простые слова, "
                            + "добрый тон. Сложные вещи объясняй на примерах."},
    };

    public static class UserPrompt {
        public final String name, text;
        public UserPrompt(String name, String text) { this.name = name; this.text = text; }
    }

    public static List<String> names() {
        List<String> l = new ArrayList<>();
        for (String[] t : ALL) l.add(t[0]);
        return l;
    }

    /** built-in text by name, null if not a built-in */
    public static String builtInText(String name) {
        for (String[] t : ALL) if (t[0].equals(name)) return t[1];
        return null;
    }

    public static boolean isBuiltIn(String name) {
        return builtInText(name) != null;
    }

    public static List<UserPrompt> userList(Context c) {
        List<UserPrompt> out = new ArrayList<>();
        try {
            String json = SettingsStore.p(c).getString("userPrompts", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String n = o.optString("name", "");
                if (!n.trim().isEmpty()) out.add(new UserPrompt(n, o.optString("text", "")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveUser(Context c, String name, String text) {
        String raw = name == null ? "" : name.trim();
        final String n = raw.isEmpty() ? "Мой промпт" : raw;
        List<UserPrompt> list = userList(c);
        list.removeIf(p -> p.name.equalsIgnoreCase(n));
        list.add(new UserPrompt(n, text == null ? "" : text));
        persist(c, list);
        setSelectedName(c, n);
    }

    public static void deleteUser(Context c, String name) {
        List<UserPrompt> list = userList(c);
        list.removeIf(p -> p.name.equalsIgnoreCase(name));
        persist(c, list);
        if (name.equalsIgnoreCase(selectedName(c))) setSelectedName(c, DEFAULT_NAME);
    }

    private static void persist(Context c, List<UserPrompt> list) {
        JSONArray arr = new JSONArray();
        try {
            for (UserPrompt p : list) {
                arr.put(new JSONObject().put("name", p.name).put("text", p.text));
            }
        } catch (Exception ignored) {
        }
        SettingsStore.p(c).edit().putString("userPrompts", arr.toString()).apply();
    }

    public static String selectedName(Context c) {
        return SettingsStore.p(c).getString("promptName", DEFAULT_NAME);
    }

    public static void setSelectedName(Context c, String name) {
        SettingsStore.p(c).edit().putString("promptName", name).apply();
    }

    /**
     * Applies a preset by name: writes its text into the persona setting and
     * remembers which preset it was.
     */
    public static void apply(Context c, String name) {
        String built = builtInText(name);
        if (built != null) {
            // уходя со своего промпта, сохраняем его текст: раньше он затирался встроенным
            String prev = selectedName(c);
            if (!isBuiltIn(prev)) {
                String text = SettingsStore.system(c);
                if (!text.trim().isEmpty()) {
                    List<UserPrompt> list = userList(c);
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).name.equalsIgnoreCase(prev)) {
                            list.set(i, new UserPrompt(prev, text));
                            persist(c, list);
                            break;
                        }
                    }
                }
            }
            SettingsStore.p(c).edit().putString("system", built).apply();
            setSelectedName(c, name);
            return;
        }
        for (UserPrompt p : userList(c)) {
            if (p.name.equals(name)) {
                SettingsStore.p(c).edit().putString("system", p.text).apply();
                setSelectedName(c, name);
                return;
            }
        }
    }

    private PromptTemplates() {}
}
