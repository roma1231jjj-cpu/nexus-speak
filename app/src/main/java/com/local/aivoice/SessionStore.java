package com.local.aivoice;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Multi-session conversation history. Each session has an id, a title (auto
 * taken from the first user message), a timestamp and its messages.
 * Backed by SharedPreferences so it survives restarts.
 */
public final class SessionStore {

    public static class Session {
        public String id;
        public String title;
        public long updatedAt;
        public final List<String[]> messages = new ArrayList<>();
    }

    private static final int MAX_SESSIONS = 40;
    private static final int MAX_MESSAGES = 40;
    /** суммарный лимит текста в диалоге (~8k токенов) — иначе провайдер отвечает 400 */
    private static final int MAX_CHARS = 24000;

    private static List<Session> cache;
    private static String currentId;

    private static synchronized void load(Context c) {
        if (cache != null) return;
        cache = new ArrayList<>();
        currentId = SettingsStore.p(c).getString("currentSession", null);
        try {
            JSONArray arr = new JSONArray(SettingsStore.p(c).getString("sessions", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Session s = new Session();
                s.id = o.optString("id", String.valueOf(System.currentTimeMillis()));
                s.title = o.optString("title", "Диалог");
                s.updatedAt = o.optLong("ts", 0);
                JSONArray m = o.optJSONArray("msgs");
                if (m != null) {
                    for (int k = 0; k < m.length(); k++) {
                        JSONArray pair = m.getJSONArray(k);
                        String role = pair.getString(0);
                        String text = clean(pair.getString(1));
                        if (!text.isEmpty()) s.messages.add(new String[]{role, text});
                    }
                }
                cache.add(s);
            }
        } catch (Exception ignored) {
        }
        if (currentId == null && !cache.isEmpty()) currentId = cache.get(0).id;
    }

    /**
     * Старая история иногда содержала служебные "null" куски от стрима.
     * Убираем только их, не трогая слова и ссылки: раньше replace() вырезал
     * "null" из любого текста и портил сообщения безвозвратно.
     */
    private static String clean(String text) {
        if (text == null) return "";
        String out = text.trim();
        while (out.startsWith("null")) out = out.substring(4).trim();
        while (out.endsWith("null")) out = out.substring(0, out.length() - 4).trim();
        if ("null".equals(out)) return "";
        return out;
    }

    private static String newId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** одна сессия не должна весить больше, чем модель способна переварить */
    private static void trimMessages(Session s) {
        while (s.messages.size() > MAX_MESSAGES) s.messages.remove(0);
        int total = 0;
        for (int i = s.messages.size() - 1; i >= 0; i--) {
            total += s.messages.get(i)[1].length();
            if (total > MAX_CHARS && i > 0) {
                s.messages.subList(0, i).clear();
                return;
            }
        }
    }

    private static void save(Context c) {
        JSONArray arr = new JSONArray();
        try {
            for (Session s : cache) {
                JSONArray msgs = new JSONArray();
                for (String[] m : s.messages) msgs.put(new JSONArray().put(m[0]).put(m[1]));
                arr.put(new JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("ts", s.updatedAt)
                        .put("msgs", msgs));
            }
        } catch (Exception ignored) {
        }
        SettingsStore.p(c).edit()
                .putString("sessions", arr.toString())
                .putString("currentSession", currentId)
                .apply();
    }

    public static synchronized List<Session> list(Context c) {
        load(c);
        List<Session> out = new ArrayList<>(cache);
        out.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    public static synchronized Session current(Context c) {
        load(c);
        for (Session s : cache) if (s.id.equals(currentId)) return s;
        return null;
    }

    public static synchronized Session currentOrCreate(Context c) {
        load(c);
        Session s = current(c);
        if (s == null) {
            s = new Session();
            s.id = newId();
            s.title = "Новый диалог";
            s.updatedAt = System.currentTimeMillis();
            cache.add(0, s);
            currentId = s.id;
            save(c);
        }
        return s;
    }

    public static synchronized Session newSession(Context c) {
        load(c);
        // пустой текущий диалог переиспользуем: иначе каждое нажатие «Новый диалог»
        // добавляло в историю ещё одну пустую строку
        Session cur = current(c);
        if (cur != null && cur.messages.isEmpty()) return cur;
        Session s = new Session();
        s.id = newId();
        s.title = "Новый диалог";
        s.updatedAt = System.currentTimeMillis();
        cache.add(0, s);
        currentId = s.id;
        while (cache.size() > MAX_SESSIONS) cache.remove(cache.size() - 1);
        save(c);
        return s;
    }

    public static synchronized void select(Context c, String id) {
        load(c);
        currentId = id;
        save(c);
    }

    public static synchronized void delete(Context c, String id) {
        load(c);
        cache.removeIf(s -> s.id.equals(id));
        if (id.equals(currentId)) currentId = cache.isEmpty() ? null : cache.get(0).id;
        save(c);
    }

    public static synchronized void add(Context c, String role, String content) {
        appendTo(c, null, role, content);
    }

    /** добавляет сообщение в конкретный диалог, даже если пользователь уже переключился */
    public static synchronized void appendTo(Context c, String sessionId, String role, String content) {
        load(c);
        Session s;
        if (sessionId == null) {
            s = currentOrCreate(c);
        } else {
            s = null;
            for (Session x : cache) if (x.id.equals(sessionId)) { s = x; break; }
            if (s == null) s = currentOrCreate(c);
        }
        s.messages.add(new String[]{role, content});
        trimMessages(s);
        if ("user".equals(role) && ("Новый диалог".equals(s.title) || s.title.isEmpty())) {
            s.title = content.length() > 42 ? content.substring(0, 42) + "…" : content;
        }
        s.updatedAt = System.currentTimeMillis();
        // move to top
        cache.remove(s);
        cache.add(0, s);
        save(c);
    }

    public static synchronized List<String[]> messages(Context c) {
        Session s = current(c);
        return s == null ? new ArrayList<>() : new ArrayList<>(s.messages);
    }

    public static synchronized void clearCurrent(Context c) {
        Session s = current(c);
        if (s != null) {
            s.messages.clear();
            s.title = "Новый диалог";
            s.updatedAt = System.currentTimeMillis();
            save(c);
        }
    }

    public static String prettyTime(long ts) {
        if (ts <= 0) return "";
        long now = System.currentTimeMillis();
        if (now - ts < 60_000) return L.s("только что");
        if (now - ts < 3_600_000) return (now - ts) / 60_000 + L.s(" мин назад");
        if (now - ts < 86_400_000) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
        }
        return new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private SessionStore() {}
}
