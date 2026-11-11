package pl.prosteradio;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Katalog stacji: darmowe, otwarte API radio-browser.info (bez reklam, bez klucza).
 * Próbuje kolejnych serwerów lustrzanych, gdy któryś nie odpowiada.
 */
public class RadioApi {
    private static final String[] SERVERS = {
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "fi1.api.radio-browser.info",
            "at1.api.radio-browser.info",
            "nl1.api.radio-browser.info",
            "all.api.radio-browser.info",
    };
    private static volatile int preferred = 0;

    private final Context ctx;

    public RadioApi(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Najpopularniejsze polskie stacje. */
    public List<Station> popular() throws IOException {
        return query("/json/stations/search?countrycode=PL&hidebroken=true"
                + "&order=clickcount&reverse=true&limit=200");
    }

    /** Wyszukiwanie po nazwie (cały świat), od najpopularniejszych. */
    public List<Station> search(String name) throws IOException {
        return query("/json/stations/search?name=" + Uri.encode(name)
                + "&hidebroken=true&order=clickcount&reverse=true&limit=150");
    }

    /** Informuje katalog, że stacja została włączona (pomaga w rankingu popularności). */
    public void countClick(Station s) {
        if (s.uuid.length() == 0) return;
        Http.ping(ctx, "https://" + SERVERS[preferred] + "/json/url/" + Uri.encode(s.uuid));
    }

    private List<Station> query(String path) throws IOException {
        IOException last = null;
        for (int i = 0; i < SERVERS.length; i++) {
            int idx = (preferred + i) % SERVERS.length;
            String host = SERVERS[idx];
            for (String scheme : new String[]{"https://", "http://"}) {
                try {
                    byte[] data = Http.get(ctx, scheme + host + path, 8 * 1024 * 1024);
                    List<Station> r = parse(new String(data, "UTF-8"));
                    preferred = idx;
                    return r;
                } catch (IOException e) {
                    last = e;
                } catch (Exception e) {
                    last = new IOException(e.toString());
                }
            }
        }
        throw last != null ? last : new IOException("Brak serwera");
    }

    private static List<Station> parse(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Station> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Station s = Station.fromApi(o);
            if (s.url.length() == 0 || s.name.length() == 0) continue;
            // pomiń duplikaty (ta sama nazwa i ten sam strumień)
            String dedupe = s.name.toLowerCase() + "|" + s.url;
            if (!seen.add(dedupe)) continue;
            out.add(s);
        }
        return out;
    }
}
