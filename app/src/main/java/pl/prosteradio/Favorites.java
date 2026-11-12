package pl.prosteradio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Ulubione stacje zapisane w pamięci telefonu (działają też bez internetu). */
public class Favorites {
    private static final String PREFS = "favorites";
    private static final String KEY = "list";
    private final SharedPreferences prefs;
    private final List<Station> list = new ArrayList<>();

    public Favorites(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                list.add(Station.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {}
    }

    public List<Station> all() {
        return new ArrayList<>(list);
    }

    public boolean contains(Station s) {
        return indexOf(s) >= 0;
    }

    /** Dodaje lub usuwa. Zwraca true, jeśli stacja jest teraz ulubiona. */
    public boolean toggle(Station s) {
        int i = indexOf(s);
        boolean nowFav;
        if (i >= 0) {
            list.remove(i);
            nowFav = false;
        } else {
            list.add(s);
            nowFav = true;
        }
        save();
        return nowFav;
    }

    /** Dodaje stację na koniec listy (np. własną stację wpisaną ręcznie). */
    public void add(Station s) {
        if (indexOf(s) < 0) list.add(s);
        save();
    }

    /** Podmienia stację o tym samym kluczu (edycja własnej stacji). */
    public void replace(Station s) {
        int i = indexOf(s);
        if (i >= 0) list.set(i, s);
        else list.add(s);
        save();
    }

    public void remove(Station s) {
        int i = indexOf(s);
        if (i >= 0) {
            list.remove(i);
            save();
        }
    }

    private int indexOf(Station s) {
        String k = s.key();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key().equals(k)) return i;
        }
        return -1;
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (Station s : list) arr.put(s.toJson());
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    // ---- ostatnio słuchana stacja ----

    public static void saveLast(Context ctx, Station s) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last", s.toJson().toString()).putString("last_kind", "station").apply();
    }

    /** Co było grane ostatnio: „station” albo „episode”. */
    public static String lastKind(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_kind", "station");
    }

    public static void setLastKind(Context ctx, String kind) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_kind", kind).apply();
    }

    public static Station loadLast(Context ctx) {
        String js = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null);
        if (js == null) return null;
        try {
            return Station.fromJson(new JSONObject(js));
        } catch (Exception e) {
            return null;
        }
    }
}
