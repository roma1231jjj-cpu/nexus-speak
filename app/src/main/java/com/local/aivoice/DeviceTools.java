package com.local.aivoice;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Executes on-device actions requested by the model. Must not be called on the main thread. */
public final class DeviceTools {

    public static String execute(Context ctx, String action, org.json.JSONObject args) {
        // геокодер — блокирующий вызов; на главном потоке он вешает интерфейс
        if ("location".equals(action) || "where".equals(action)) {
            try {
                return location(ctx);
            } catch (Throwable t) {
                return "Не удалось получить геопозицию: " + t;
            }
        }
        final String[] result = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result[0] = runOnMain(ctx, action, args);
            } catch (Exception e) {
                result[0] = "Ошибка действия: " + e;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            result[0] = "Таймаут действия";
        }
        return result[0];
    }


    /** последняя известная геопозиция + город (без запросов к сети, если провайдер выключен) */
    private static String location(Context ctx) {
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return "Геолокация недоступна на этом устройстве.";
            boolean fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            boolean coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) {
                return "Нет разрешения на геолокацию: дай его в настройках приложения.";
            }
            android.location.Location best = null;
            try {
                for (String p : new String[]{android.location.LocationManager.GPS_PROVIDER,
                        android.location.LocationManager.NETWORK_PROVIDER,
                        android.location.LocationManager.PASSIVE_PROVIDER}) {
                    android.location.Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            } catch (SecurityException se) {
                return "Нет разрешения на геолокацию.";
            }
            if (best == null) {
                return "Геопозиция неизвестна: выключен GPS или её ещё не определяли. "
                        + "Попроси включить геолокацию и повторить.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Координаты: ").append(String.format(java.util.Locale.US, "%.5f, %.5f",
                    best.getLatitude(), best.getLongitude()));
            long age = (System.currentTimeMillis() - best.getTime()) / 60000;
            if (age > 5) sb.append(" (данные ").append(age).append(" мин назад)");
            try {
                android.location.Geocoder g = new android.location.Geocoder(ctx,
                        java.util.Locale.getDefault());
                java.util.List<android.location.Address> list =
                        g.getFromLocation(best.getLatitude(), best.getLongitude(), 1);
                if (list != null && !list.isEmpty()) {
                    android.location.Address a = list.get(0);
                    StringBuilder place = new StringBuilder();
                    if (a.getLocality() != null) place.append(a.getLocality());
                    if (a.getSubAdminArea() != null && !a.getSubAdminArea().equals(a.getLocality())) {
                        if (place.length() > 0) place.append(", ");
                        place.append(a.getSubAdminArea());
                    }
                    if (a.getCountryName() != null) {
                        if (place.length() > 0) place.append(", ");
                        place.append(a.getCountryName());
                    }
                    if (place.length() > 0) sb.append(". Место: ").append(place);
                    if (a.getThoroughfare() != null) {
                        sb.append(". Улица: ").append(a.getThoroughfare());
                        if (a.getSubThoroughfare() != null) sb.append(" ").append(a.getSubThoroughfare());
                    }
                }
            } catch (Throwable ignored) {
            }
            return sb.toString();
        } catch (Throwable t) {
            return "Не удалось получить геопозицию: " + t.getClass().getSimpleName();
        }
    }
    private static String runOnMain(Context ctx, String action, org.json.JSONObject args) {
        switch (action) {
            case "location":
            case "where": {
                return location(ctx);
            }
            case "time": {
                java.util.Date d = new java.util.Date();
                return "Сейчас " + android.text.format.DateFormat.format("HH:mm, EEEE dd MMMM yyyy", d);
            }
            case "call":
            case "phone": {
                String who = args != null ? args.optString("who", args.optString("to", "")) : "";
                String number = resolveContact(ctx, who);
                if (number == null && who.matches(".*\\d.*")) number = who.replaceAll("[^+0-9*#]", "");
                if (number == null) return "Не нашёл контакт «" + who + "» в телефонной книге.";
                Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(i);
                    return "Звоню " + who + " (" + number + ").";
                } catch (Exception e) {
                    // no CALL_PHONE permission -> dialer
                    try {
                        ctx.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return "Открыл звонилку — подтверди вызов.";
                    } catch (Exception e2) {
                        return "Не смог позвонить: " + e2;
                    }
                }
            }
            case "sms": {
                String to = args != null ? args.optString("to", "") : "";
                String text = args != null ? args.optString("text", "") : "";
                String number = resolveContact(ctx, to);
                if (number == null) number = to.replaceAll("[^+0-9]", "");
                if (number.isEmpty()) return "Не понял, кому отправить сообщение.";
                try {
                    if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS)
                            == PackageManager.PERMISSION_GRANTED) {
                        android.telephony.SmsManager sm = android.os.Build.VERSION.SDK_INT >= 31
                                ? ctx.getSystemService(android.telephony.SmsManager.class)
                                : android.telephony.SmsManager.getDefault();
                        if (sm != null) {
                            sm.sendTextMessage(number, null, text, null, null);
                            return "Сообщение отправлено на " + number + ".";
                        }
                    }
                } catch (Exception ignored) {
                }
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number));
                i.putExtra("sms_body", text);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return "Открыл SMS — подтверди отправку.";
            }
            case "open_app": {
                String name = args != null ? args.optString("name", "").toLowerCase(java.util.Locale.ROOT) : "";
                if (name.isEmpty()) return "Не указано приложение.";
                PackageManager pm = ctx.getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    String label = ri.loadLabel(pm).toString().toLowerCase(java.util.Locale.ROOT);
                    if (label.contains(name) || name.contains(label)) {
                        Intent i = pm.getLaunchIntentForPackage(ri.activityInfo.packageName);
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(i);
                            return "Открываю " + ri.loadLabel(pm) + ".";
                        }
                    }
                }
                return "Не нашёл приложение «" + name + "».";
            }
            case "open_url": {
                String url = args != null ? args.optString("url", "").trim() : "";
                if (url.isEmpty()) return "Не указан адрес.";
                String lowUrl = url.toLowerCase(java.util.Locale.ROOT);
                // только http(s): intent:// и javascript: — это уже запуск чужих компонентов
                if (lowUrl.startsWith("intent:") || lowUrl.startsWith("javascript:")
                        || lowUrl.startsWith("data:") || lowUrl.startsWith("file:")) {
                    return "Такую ссылку открывать не буду.";
                }
                if (!lowUrl.startsWith("http://") && !lowUrl.startsWith("https://")) {
                    url = "https://" + url;
                }
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return "Открываю в браузере.";
                } catch (Exception e) {
                    return "Не смог открыть: " + e;
                }
            }
            case "torch":
            case "flashlight": {
                boolean on = args == null || args.optBoolean("on", true);
                CameraManager cm = ctx.getSystemService(CameraManager.class);
                try {
                    for (String id : cm.getCameraIdList()) {
                        if (Boolean.TRUE.equals(cm.getCameraCharacteristics(id)
                                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                            cm.setTorchMode(id, on);
                            return on ? "Фонарик включён." : "Фонарик выключен.";
                        }
                    }
                } catch (Exception e) {
                    return "Фонарик: " + e.getMessage();
                }
                return "Фонарик недоступен.";
            }
            case "volume": {
                AudioManager am = ctx.getSystemService(AudioManager.class);
                int level = args != null ? args.optInt("level", -1) : -1;
                if (level < 0) return "Не указан уровень громкости (0-100).";
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                if (max <= 0) return "На этом устройстве громкость недоступна.";
                int v = Math.max(0, Math.min(max, (int) Math.round(level / 100.0 * max)));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                return "Громкость мультимедиа " + Math.round(v * 100.0 / max) + "%.";
            }
            case "wifi": {
                boolean on = args == null || args.optBoolean("on", true);
                try {
                    ctx.startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return "Открыл панель Wi-Fi — подтверди переключение.";
                } catch (Exception e) {
                    return "Не смог открыть настройки Wi-Fi.";
                }
            }
            case "bluetooth": {
                try {
                    ctx.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return "Открыл панель Bluetooth — подтверди переключение.";
                } catch (Exception e) {
                    return "Не смог открыть настройки Bluetooth.";
                }
            }
            case "alarm": {
                int hour = args != null ? args.optInt("hour", -1) : -1;
                int minute = args != null ? args.optInt("minute", -1) : -1;
                if (hour < 0 || minute < 0) return "Укажи час и минуты будильника.";
                try {
                    ctx.startActivity(new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, hour)
                            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return "Будильник на " + String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute) + " поставлен.";
                } catch (Exception e) {
                    return "Не смог поставить будильник: " + e;
                }
            }
            case "timer": {
                int seconds = args != null ? args.optInt("seconds", -1) : -1;
                int minutes = args != null ? args.optInt("minutes", -1) : -1;
                int s = seconds >= 0 ? seconds : (minutes >= 0 ? minutes * 60 : -1);
                if (s <= 0) return "Укажи длительность таймера.";
                try {
                    android.content.Intent i = new android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, s)
                            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    String label = s >= 60 ? (s / 60) + " мин" : s + " сек";
                    return "Таймер на " + label + " запущен.";
                } catch (Exception e) {
                    return "Не смог запустить таймер: " + e;
                }
            }
            case "contacts": {
                StringBuilder sb = new StringBuilder();
                try (Cursor c = ctx.getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER},
                        null, null, null)) {
                    int n = 0;
                    while (c != null && c.moveToNext() && n < 30) {
                        sb.append(c.getString(0)).append(" — ").append(c.getString(1)).append("\n");
                        n++;
                    }
                }
                return sb.length() > 0 ? sb.toString() : "Телефонная книга пуста или нет доступа.";
            }
            default:
                return "Неизвестное действие: " + action;
            }
    }

    private static String resolveContact(Context ctx, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String q = name.trim();
        try (Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + q + "%"}, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
        return null;
    }
}
