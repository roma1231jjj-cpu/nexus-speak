package com.local.aivoice;

/**
 * Один владелец микрофона на процесс.
 *
 * Android отдаёт микрофон только одному SpeechRecognizer. Когда панель
 * ассистента на экране, wake-служба обязана молчать, иначе её распознаватель
 * забирает звук себе: панель «слушает», но не слышит ни слова, а через
 * таймауты бесконечно перезапускается.
 */
public final class MicOwner {

    private static volatile boolean panelActive = false;
    private static volatile long until = 0;

    /** панель на экране: служба не имеет права начинать прослушивание */
    public static boolean isPanelActive() {
        // если панель умерла, не сняв флаг, прослушка не должна молчать вечно
        return panelActive && android.os.SystemClock.uptimeMillis() < until;
    }

    public static void setPanelActive(boolean active) {
        panelActive = active;
        until = android.os.SystemClock.uptimeMillis() + 5 * 60 * 1000L;
    }

    private MicOwner() { }
}
