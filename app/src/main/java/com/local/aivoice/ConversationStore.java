package com.local.aivoice;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Thin compatibility layer on top of {@link SessionStore} (current session). */
public final class ConversationStore {

    public static ArrayList<String[]> get(Context c) {
        return new ArrayList<>(SessionStore.messages(c));
    }

    public static void add(Context c, String role, String content) {
        SessionStore.add(c, role, content);
    }

    public static void trim(Context c) {
        // SessionStore trims on add
    }

    public static void clear(Context c) {
        SessionStore.clearCurrent(c);
    }

    public static List<SessionStore.Session> sessions(Context c) {
        return SessionStore.list(c);
    }

    private ConversationStore() {}
}
