package pl.prosteradio;

import org.json.JSONException;
import org.json.JSONObject;

/** Podcast (audycja) z katalogu Apple Podcasts. */
public class Podcast {
    public String id = "";        // identyfikator w katalogu Apple
    public String name = "";
    public String author = "";
    public String artwork = "";   // okładka (mała, do listy)
    public String feedUrl = "";   // kanał RSS z odcinkami – może być pusty do czasu „lookup”

    public String key() {
        return id.length() > 0 ? id : feedUrl;
    }

    /** Większa okładka do widoku pełnoekranowego (adresy Apple mają rozmiar w nazwie). */
    public String bigArtwork() {
        return bigArtwork(artwork);
    }

    public static String bigArtwork(String url) {
        if (url == null) return "";
        return url.replaceAll("/\\d+x\\d+(bb|cc)?\\.(jpg|png|webp)$", "/600x600bb.jpg");
    }

    public static Podcast fromJson(JSONObject o) {
        Podcast p = new Podcast();
        p.id = o.optString("id", "");
        p.name = o.optString("name", "");
        p.author = o.optString("author", "");
        p.artwork = o.optString("artwork", "");
        p.feedUrl = o.optString("feedUrl", "");
        return p;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("author", author);
            o.put("artwork", artwork);
            o.put("feedUrl", feedUrl);
        } catch (JSONException ignored) {}
        return o;
    }
}
