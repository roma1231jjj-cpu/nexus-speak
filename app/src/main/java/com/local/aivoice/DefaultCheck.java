package com.local.aivoice;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.service.voice.VoiceInteractionService;
import android.provider.Settings;

import java.util.List;

public final class DefaultCheck {

    public static ComponentName me(Context ctx) {
        return new ComponentName(ctx, AssistantVoiceInteractionService.class);
    }

    /**
     * Система хранит компонент в разных форматах: com.pkg/.Cls, com.pkg/com.pkg.Cls,
     * иногда с суффиксом. Сравниваем по пакету и имени класса, а не по строке.
     */
    public static boolean isDefaultAssistant(Context ctx) {
        ComponentName mine = me(ctx);
        for (String key : new String[]{"voice_interaction_service", "assistant"}) {
            String cur = null;
            try {
                cur = Settings.Secure.getString(ctx.getContentResolver(), key);
            } catch (Throwable ignored) {
            }
            if (cur == null || cur.trim().isEmpty()) continue;
            ComponentName them = ComponentName.unflattenFromString(cur.trim());
            if (them != null) {
                if (mine.getPackageName().equals(them.getPackageName())
                        && mine.getClassName().equals(them.getClassName())) return true;
                continue;
            }
            if (cur.contains(ctx.getPackageName())
                    && cur.contains(AssistantVoiceInteractionService.class.getSimpleName())) {
                return true;
            }
        }
        return false;
    }
}
