package pl.prosteradio;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Jedna stacja radiowa (dane z radio-browser.info). */
public class Station {
    public String uuid = "";
    public String name = "";
    public String url = "";        // adres strumienia
    public String favicon = "";    // adres logo
    public String tags = "";
    public String country = "";
    public String codec = "";
    public int bitrate;
    public boolean hls;
    public boolean custom;         // stacja dodana ręcznie przez użytkownika

    /** Stacje, które w trakcie działania okazały się być w formacie Opus. */
    private static final Set<String> detectedOpus = Collections.synchronizedSet(new HashSet<String>());

    public static Station fromApi(JSONObject o) {
        Station s = new Station();
        s.uuid = o.optString("stationuuid", "");
        s.name = o.optString("name", "").trim();
        String resolved = o.optString("url_resolved", "");
        s.url = resolved.length() > 0 ? resolved : o.optString("url", "");
        s.favicon = o.optString("favicon", "").trim();
        s.tags = o.optString("tags", "");
        s.country = o.optString("country", "");
        s.codec = o.optString("codec", "");
        s.bitrate = o.optInt("bitrate", 0);
        s.hls = o.optInt("hls", 0) == 1;
        return s;
    }

    public static Station fromJson(JSONObject o) {
        Station s = new Station();
        s.uuid = o.optString("uuid", "");
        s.name = o.optString("name", "");
        s.url = o.optString("url", "");
        s.favicon = o.optString("favicon", "");
        s.tags = o.optString("tags", "");
        s.country = o.optString("country", "");
        s.codec = o.optString("codec", "");
        s.bitrate = o.optInt("bitrate", 0);
        s.hls = o.optBoolean("hls", false);
        s.custom = o.optBoolean("custom", false);
        return s;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("uuid", uuid);
            o.put("name", name);
            o.put("url", url);
            o.put("favicon", favicon);
            o.put("tags", tags);
            o.put("country", country);
            o.put("codec", codec);
            o.put("bitrate", bitrate);
            o.put("hls", hls);
            o.put("custom", custom);
        } catch (JSONException ignored) {}
        return o;
    }

    public String key() {
        return uuid.length() > 0 ? uuid : url;
    }

    /** Ogg Opus – Android przed wersją 5.0 nie ma dekodera tego formatu. */
    public boolean isOpus() {
        return codec.toUpperCase(Locale.US).contains("OPUS") || detectedOpus.contains(key());
    }

    public boolean isUnsupportedHere() {
        return isOpus() && Build.VERSION.SDK_INT < 21;
    }

    public void markOpus() {
        detectedOpus.add(key());
    }

    /** Adres bez „http://” – do opisu własnych stacji. */
    public String shortUrl() {
        String u = url;
        int i = u.indexOf("://");
        if (i >= 0) u = u.substring(i + 3);
        return u;
    }

    public boolean isHls() {
        String u = url.toLowerCase();
        return hls || u.contains(".m3u8");
    }

    /** Krótki opis pod nazwą: gatunki · kraj · jakość. */
    public String details() {
        StringBuilder sb = new StringBuilder();
        if (tags.length() > 0) {
            String[] parts = tags.split(",");
            int n = 0;
            for (String p : parts) {
                p = p.trim();
                if (p.length() == 0) continue;
                if (n > 0) sb.append(", ");
                sb.append(p);
                if (++n == 3) break;
            }
        }
        if (country.length() > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(country);
        }
        if (bitrate > 0 || codec.length() > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            if (codec.length() > 0) sb.append(codec);
            if (bitrate > 0) sb.append(codec.length() > 0 ? " " : "").append(bitrate).append(" kbps");
        }
        return sb.toString();
    }
}
