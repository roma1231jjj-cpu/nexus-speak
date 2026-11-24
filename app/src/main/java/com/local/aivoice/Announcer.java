package com.local.aivoice;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads incoming notifications and calls aloud through the assistant voice.
 * Only speaks when the user enabled it; never interrupts the assistant dialog.
 */
public final class Announcer {

    private static TtsSpeaker speaker;
    private static String lastSig = "";
    private static final AtomicLong lastAt = new AtomicLong(0);
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void announcement(Context c, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (!SettingsStore.announceNotifications(c) && !SettingsStore.announceCalls(c)) return;
        if (AssistantActivity.isBusy()) return; // не перебиваем диалог
        long now = System.currentTimeMillis();
        if (now - lastAt.get() < 4000) return;  // антиспам
        lastAt.set(now);

        // объявления всегда идут «в обычном режиме» — иначе звук уходит в трубку
        BeepMuter.ensureNormalMode(c);
        String sig = SettingsStore.ttsEngine(c) + "|" + SettingsStore.voiceVolume(c) + "|"
                + SettingsStore.ttsRate(c) + "|" + SettingsStore.ttsPitch(c) + "|"
                + SettingsStore.ttsVoice(c) + "|" + CloudTts.provider(c);
        if (speaker == null || !sig.equals(lastSig)) {
            if (speaker != null) speaker.shutdown();
            speaker = new TtsSpeaker(c, SettingsStore.ttsEngine(c),
                    SettingsStore.voiceVolume(c), SettingsStore.ttsRate(c),
                    SettingsStore.ttsPitch(c), SettingsStore.ttsVoice(c), () -> { });
            lastSig = sig;
        }
        speaker.speak(text);
    }

    /** 23:00 - 08:00 по умолчанию тихо */
    private static boolean quietNow() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 23 || h < 8;
    }

    public static void onNotification(Context c, String appLabel, String title, String text) {
        if (!SettingsStore.announceNotifications(c)) return;
        if (SettingsStore.announceQuiet(c) && quietNow()) return;
        if ("Нексус".equalsIgnoreCase(appLabel)) return;
        StringBuilder sb = new StringBuilder();
        sb.append(L.s("Уведомление"));
        if (appLabel != null && !appLabel.isEmpty()) sb.append(L.s(" от ")).append(appLabel);
        sb.append(". ");
        if (title != null && !title.isEmpty()) sb.append(title).append(". ");
        if (text != null && !text.isEmpty()) sb.append(text);
        String msg = sb.toString().trim();
        if (msg.length() > 220) msg = msg.substring(0, 220);
        announcement(c, msg);
    }

    public static void onCall(Context c, String who) {
        if (!SettingsStore.announceCalls(c)) return;
        if (SettingsStore.announceQuiet(c) && quietNow()) return;
        String name = who == null || who.trim().isEmpty() ? L.s("неизвестный номер") : who.trim();
        announcement(c, L.s("Звонит ") + name);
    }

    public static void stop() {
        try { if (speaker != null) speaker.stop(); } catch (Throwable ignored) {}
    }

    private Announcer() {}
}
