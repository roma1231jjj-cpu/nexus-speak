package com.local.aivoice;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/** Reads active notifications so the assistant can tell the user what arrived. */
public class NotificationReaderService extends NotificationListenerService {

    private static volatile StatusBarNotification[] snapshot = new StatusBarNotification[0];

    @Override
    public void onListenerConnected() {
        snapshot = getActiveNotifications();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // озвучиваем входящее, если пользователь включил чтение уведомлений
        try {
            if (sbn != null && sbn.getNotification() != null && !sbn.isOngoing()
                    && (sbn.getNotification().flags
                        & android.app.Notification.FLAG_GROUP_SUMMARY) == 0) {
                BundleEx ex = new BundleEx(sbn.getNotification().extras);
                String pkg = sbn.getPackageName();
                String app = pkg;
                try {
                    app = getPackageManager()
                            .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                            .toString();
                } catch (Exception ignored) {
                }
                if (!pkg.equals(getPackageName())) {
                    Announcer.onNotification(this, app, ex.title(), ex.text());
                }
            }
        } catch (Throwable ignored) {
        }
        String newKey = sbn.getKey() == null ? "" : sbn.getKey();
        StatusBarNotification[] old = snapshot;
        // не дублируем одно и то же уведомление при повторной публикации
        List<StatusBarNotification> fresh = new ArrayList<>();
        fresh.add(sbn);
        for (StatusBarNotification s : old) {
            String k = s == null || s.getKey() == null ? "" : s.getKey();
            if (s != null && !k.equals(newKey) && fresh.size() < 20) fresh.add(s);
        }
        snapshot = fresh.toArray(new StatusBarNotification[0]);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        List<StatusBarNotification> l = new ArrayList<>();
        String gone = sbn.getKey() == null ? "" : sbn.getKey();
        for (StatusBarNotification s : snapshot) {
            // объекты приходят новые, поэтому сравниваем стабильный key
            String k = s == null || s.getKey() == null ? "" : s.getKey();
            if (s != null && !k.equals(gone)) l.add(s);
        }
        snapshot = l.toArray(new StatusBarNotification[0]);
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StatusBarNotification s : snapshot) {
            if (s == null || s.isOngoing()) continue;
            BundleEx ex = new BundleEx(s.getNotification().extras);
            String title = ex.title();
            String text = ex.text();
            if (title == null && text == null) continue;
            sb.append(title != null ? title : "?")
              .append(" — ").append(text != null ? text : "").append("\n");
            if (++n >= 12) break;
        }
        return sb.length() > 0 ? sb.toString().trim() : "Активных уведомлений нет.";
    }

    /** avoids android.app.Notification.Action references in API surface */
    private static final class BundleEx {
        private final android.os.Bundle b;
        BundleEx(android.os.Bundle b) { this.b = b; }
        String title() { return str(android.app.Notification.EXTRA_TITLE); }
        String text() { return str(android.app.Notification.EXTRA_TEXT); }
        private String str(String key) {
            Object o = b != null ? b.get(key) : null;
            return o instanceof CharSequence ? o.toString() : null;
        }
    }
}
