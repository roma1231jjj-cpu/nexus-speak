package com.local.aivoice;

import android.content.Context;

import org.json.JSONObject;

/** Постоянная память о пользователе, которой управляет сам ассистент. */
public final class Memory {

    public static String handle(Context c, JSONObject args) {
        String mode = args.optString("mode", "add").toLowerCase(java.util.Locale.ROOT);
        String text = args.optString("text", "").trim();
        String cur = SettingsStore.userInfo(c);

        if ("clear".equals(mode)) {
            SettingsStore.setUserInfo(c, "");
            return "Память очищена. Больше ничего о пользователе не помню.";
        }
        if ("show".equals(mode) || text.isEmpty()) {
            return cur.isEmpty() ? "Память пуста." : "В памяти: " + cur;
        }
        if ("forget".equals(mode)) {
            // Частичное забывание: убираем только фразы с указанным текстом,
            // остальные факты остаются. Раньше «забудь» стирало всю память.
            String needle = text.toLowerCase(java.util.Locale.ROOT);
            String[] parts = cur.split("(?<=[.!?])\s+");
            StringBuilder keep = new StringBuilder();
            int removed = 0;
            for (String part : parts) {
                if (part.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    removed++;
                } else if (part.trim().length() > 0) {
                    if (keep.length() > 0) keep.append(' ');
                    keep.append(part.trim());
                }
            }
            if (removed == 0) return "В памяти нет фразы: " + text;
            SettingsStore.setUserInfo(c, keep.toString().trim());
            return "Забыл: " + text;
        }
        if (text.length() > 300) text = text.substring(0, 300);

        String lowCur = cur.toLowerCase(java.util.Locale.ROOT);
        if (lowCur.contains(text.toLowerCase(java.util.Locale.ROOT))) {
            return "Это уже в памяти: " + text;
        }

        String next;
        if ("replace".equals(mode)) {
            next = text;
        } else {
            next = cur.isEmpty() ? text
                    : cur + (cur.trim().endsWith(".") ? " " : ". ") + text;
        }
        if (next.length() > 2000) next = next.substring(next.length() - 2000);
        SettingsStore.setUserInfo(c, next);
        return ("replace".equals(mode) ? "Память обновлена: " : "Запомнил: ") + text;
    }

    private Memory() {}
}
