package com.orphos.mobile.core.capture;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orphos.mobile.data.model.Capture;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable on-device store for Scribbles captures in demo/offline mode. Backed by
 * SharedPreferences (JSON), so notes, handwriting, voice and image captures created in the
 * app survive restarts and reappear in the list — the demo counterpart of the BFF's
 * ArcadeDB capture store used in connected mode.
 */
public final class LocalCaptureStore {

    private static final String PREFS = "orphos_captures";
    private static final String KEY = "list";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public LocalCaptureStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The stored captures, or {@code null} when nothing has been stored yet (never seeded). */
    public synchronized List<Capture> list() {
        String json = prefs.getString(KEY, null);
        if (json == null) return null;
        Type t = new TypeToken<List<Capture>>() {}.getType();
        try {
            List<Capture> l = gson.fromJson(json, t);
            return l != null ? l : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public synchronized void save(List<Capture> list) {
        prefs.edit().putString(KEY, gson.toJson(list != null ? list : new ArrayList<>())).apply();
    }

    /** Prepend a capture (newest first) and persist. */
    public synchronized void add(Capture c) {
        List<Capture> l = list();
        if (l == null) l = new ArrayList<>();
        l.add(0, c);
        save(l);
    }

    public synchronized Capture find(String id) {
        List<Capture> l = list();
        if (l != null && id != null) for (Capture c : l) if (id.equals(c.id)) return c;
        return null;
    }
}
