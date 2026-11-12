package pl.prosteradio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Subskrybowane podcasty i miejsce, w którym skończono słuchać każdego odcinka.
 * Wszystko zapisane na urządzeniu.
 */
public class PodcastStore {
    private static final String PREFS = "podcasts";
    private static final String KEY_SUBS = "subscriptions";
    private static final String POS = "pos:";     // pozycja w ms
    private static final String DONE = "done:";   // odcinek wysłuchany do końca

    private final SharedPreferences prefs;
    private final List<Podcast> subs = new ArrayList<>();

    public PodcastStore(Context ctx) {
        Context app = ctx.getApplicationContext();
        prefs = (app != null ? app : ctx).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_SUBS, "[]"));
            for (int i = 0; i < arr.length(); i++) subs.add(Podcast.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- subskrypcje

    public List<Podcast> subscriptions() {
        return new ArrayList<>(subs);
    }

    public boolean isSubscribed(Podcast p) {
        return indexOf(p) >= 0;
    }

    /** Zwraca true, jeśli podcast jest teraz subskrybowany. */
    public boolean toggle(Podcast p) {
        int i = indexOf(p);
        boolean now;
        if (i >= 0) {
            subs.remove(i);
            now = false;
        } else {
            subs.add(0, p);
            now = true;
        }
        saveSubs();
        return now;
    }

    /** Uzupełnia zapisaną subskrypcję (np. o adres kanału znaleziony później). */
    public void update(Podcast p) {
        int i = indexOf(p);
        if (i >= 0) {
            subs.set(i, p);
            saveSubs();
        }
    }

    private int indexOf(Podcast p) {
        for (int i = 0; i < subs.size(); i++) if (subs.get(i).key().equals(p.key())) return i;
        return -1;
    }

    private void saveSubs() {
        JSONArray arr = new JSONArray();
        for (Podcast p : subs) arr.put(p.toJson());
        prefs.edit().putString(KEY_SUBS, arr.toString()).apply();
    }

    // ---------------------------------------------------------------- postęp słuchania

    public long position(Episode e) {
        return prefs.getLong(POS + e.key(), 0);
    }

    public boolean isDone(Episode e) {
        return prefs.getBoolean(DONE + e.key(), false);
    }

    public void savePosition(Episode e, long ms) {
        prefs.edit().putLong(POS + e.key(), ms).apply();
    }

    public void markDone(Episode e) {
        prefs.edit().putBoolean(DONE + e.key(), true).remove(POS + e.key()).apply();
    }

    public void clearDone(Episode e) {
        prefs.edit().remove(DONE + e.key()).apply();
    }

    // ---------------------------------------------------------------- ostatni odcinek

    public static void saveLast(Context ctx, Episode e) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last", e.toJson().toString()).apply();
    }

    public static Episode loadLast(Context ctx) {
        String js = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null);
        if (js == null) return null;
        try {
            return Episode.fromJson(new JSONObject(js));
        } catch (Exception e) {
            return null;
        }
    }
}
