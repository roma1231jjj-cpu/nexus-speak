package com.local.aivoice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * User defined macros: named chains of simple actions that the AI can run by
 * name ("Нексус, мы дома"). Each action is one line like:
 *     open_app:youtube
 *     open_url:https://...
 *     torch:on | torch:off
 *     volume:40
 *     timer:600
 *     wait:1500
 */
public final class Macros {

    public static class Macro {
        public String name;
        public final List<String> actions = new ArrayList<>();
    }

    public static List<Macro> list(Context c) {
        List<Macro> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(SettingsStore.p(c).getString("macros", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Macro m = new Macro();
                m.name = o.optString("name", "");
                JSONArray a = o.optJSONArray("actions");
                if (a != null) for (int k = 0; k < a.length(); k++) m.actions.add(a.optString(k));
                if (!m.name.isEmpty()) out.add(m);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context c, String name, List<String> actions) {
        List<Macro> all = list(c);
        all.removeIf(m -> m.name.equalsIgnoreCase(name));
        Macro m = new Macro();
        m.name = name == null ? "" : name.trim();
        m.actions.addAll(actions);
        if (!m.name.isEmpty()) all.add(m);
        persist(c, all);
    }

    public static void delete(Context c, String name) {
        List<Macro> all = list(c);
        all.removeIf(m -> m.name.equalsIgnoreCase(name));
        persist(c, all);
    }

    private static void persist(Context c, List<Macro> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Macro m : all) {
                JSONArray a = new JSONArray();
                for (String s : m.actions) a.put(s);
                arr.put(new JSONObject().put("name", m.name).put("actions", a));
            }
        } catch (Exception ignored) {
        }
        SettingsStore.p(c).edit().putString("macros", arr.toString()).apply();
    }

    /** Runs a macro by name; returns a human-readable result for the model. */
    public static String run(Context c, String name) {
        if (name == null || name.trim().isEmpty()) {
            StringBuilder all = new StringBuilder("Макросы: ");
            for (Macro m : list(c)) all.append(m.name).append("; ");
            return all.toString();
        }
        String want = name.trim().toLowerCase(Locale.ROOT);
        // сначала точное совпадение, иначе «сп» запускал первый попавшийся макрос
        for (Macro m : list(c)) {
            if (m.name.trim().equalsIgnoreCase(want)) return execute(c, m);
        }
        for (Macro m : list(c)) {
            String mn = m.name.toLowerCase(Locale.ROOT);
            if (mn.startsWith(want) && want.length() >= 3) return execute(c, m);
        }
        StringBuilder sb = new StringBuilder("Макрос не найден. Доступные макросы: ");
        for (Macro m : list(c)) sb.append(m.name).append("; ");
        return sb.toString();
    }

    private static String execute(Context c, Macro m) {
        StringBuilder log = new StringBuilder("Макрос «" + m.name + "» выполнен: ");
        for (String raw : m.actions) {
            String action = raw.trim();
            if (action.isEmpty()) continue;
            int idx = action.indexOf(':');
            String type = (idx > 0 ? action.substring(0, idx) : action).trim().toLowerCase(Locale.ROOT);
            String value = idx > 0 ? action.substring(idx + 1).trim() : "";
            try {
                switch (type) {
                    case "open_app": {
                        Intent i = c.getPackageManager().getLaunchIntentForPackage(value);
                        if (i == null) {
                            // try by app label
                            for (android.content.pm.ResolveInfo ri : c.getPackageManager()
                                    .queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                                            .addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                                String label = ri.loadLabel(c.getPackageManager()).toString();
                                if (label.equalsIgnoreCase(value)) {
                                    i = new Intent(Intent.ACTION_MAIN)
                                            .addCategory(Intent.CATEGORY_LAUNCHER)
                                            .setComponent(new android.content.ComponentName(
                                                    ri.activityInfo.packageName, ri.activityInfo.name));
                                    break;
                                }
                            }
                        }
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            c.startActivity(i);
                            log.append("открыл ").append(value).append("; ");
                        } else {
                            log.append("не нашёл приложение ").append(value).append("; ");
                        }
                        break;
                    }
                    case "open_url": {
                        String low = value.toLowerCase(Locale.ROOT);
                        if (low.startsWith("intent:") || low.startsWith("javascript:")
                                || low.startsWith("data:") || low.startsWith("file:")) {
                            log.append("ссылку пропустил; ");
                            break;
                        }
                        if (!low.startsWith("http://") && !low.startsWith("https://")) {
                            value = "https://" + value;
                        }
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(value))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(i);
                        log.append("открыл ссылку; ");
                        break;
                    }
                    case "torch":
                        DeviceTools.execute(c, "torch",
                                new JSONObject().put("on", "on".equalsIgnoreCase(value)));
                        log.append("фонарик ").append(value).append("; ");
                        break;
                    case "volume":
                        DeviceTools.execute(c, "volume",
                                new JSONObject().put("level", Integer.parseInt(value)));
                        log.append("громкость ").append(value).append("; ");
                        break;
                    case "timer":
                        DeviceTools.execute(c, "timer",
                                new JSONObject().put("seconds", Integer.parseInt(value)));
                        log.append("таймер; ");
                        break;
                    case "wait":
                        try {
                            long ms = Math.min(10_000, Math.max(0, Long.parseLong(value)));
                            Thread.sleep(ms);
                        } catch (Exception ignored) { }
                        break;
                    default:
                        log.append("неизвестное действие ").append(type).append("; ");
                }
            } catch (Exception e) {
                log.append("ошибка в «").append(action).append("»; ");
            }
        }
        return log.toString();
    }

    private Macros() {}
}
