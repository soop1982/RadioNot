package pl.prosteradio;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Jeden odcinek podcastu (z kanału RSS). */
public class Episode {
    public String guid = "";
    public String title = "";
    public String audioUrl = "";
    public String image = "";
    public long published;        // ms od 1970, 0 = nieznana
    public int durationSec;       // z kanału RSS, 0 = nieznany
    public String podcastName = "";
    public String podcastArtwork = "";

    public String key() {
        return guid.length() > 0 ? guid : audioUrl;
    }

    /** Okładka odcinka albo okładka całego podcastu. */
    public String artwork() {
        return image.length() > 0 ? image : podcastArtwork;
    }

    public static Episode fromJson(JSONObject o) {
        Episode e = new Episode();
        e.guid = o.optString("guid", "");
        e.title = o.optString("title", "");
        e.audioUrl = o.optString("audioUrl", "");
        e.image = o.optString("image", "");
        e.published = o.optLong("published", 0);
        e.durationSec = o.optInt("durationSec", 0);
        e.podcastName = o.optString("podcastName", "");
        e.podcastArtwork = o.optString("podcastArtwork", "");
        return e;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("guid", guid);
            o.put("title", title);
            o.put("audioUrl", audioUrl);
            o.put("image", image);
            o.put("published", published);
            o.put("durationSec", durationSec);
            o.put("podcastName", podcastName);
            o.put("podcastArtwork", podcastArtwork);
        } catch (JSONException ignored) {}
        return o;
    }

    /** 3725 s → „1:02:05”, 125 s → „2:05”. */
    public static String formatTime(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }
}
