package pl.prosteradio;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Katalog podcastów: darmowe, publiczne usługi Apple (bez klucza i bez reklam):
 *  - ranking najpopularniejszych podcastów w danym kraju,
 *  - wyszukiwarka iTunes Search API,
 *  - „lookup” – adres kanału RSS dla podcastu z rankingu.
 * Same odcinki pochodzą bezpośrednio z kanału RSS twórców.
 */
public class PodcastApi {
    private final Context ctx;

    public PodcastApi(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Najpopularniejsze podcasty w kraju (kod np. „pl”). Próbuje kilku adresów rankingu. */
    public List<Podcast> top(String country) throws IOException {
        String cc = country.toLowerCase(Locale.US);
        String[] urls = {
                "https://rss.marketingtools.apple.com/api/v2/" + cc + "/podcasts/top/100/podcasts.json",
                "https://rss.applemarketingtools.com/api/v2/" + cc + "/podcasts/top/100/podcasts.json",
                "https://itunes.apple.com/" + cc + "/rss/toppodcasts/limit=100/json",
        };
        IOException last = null;
        for (String u : urls) {
            try {
                List<Podcast> r = parseTop(new String(Http.get(ctx, u, 4 * 1024 * 1024), "UTF-8"));
                if (!r.isEmpty()) return r;
            } catch (IOException e) {
                last = e;
            } catch (Exception e) {
                last = new IOException(e.toString());
            }
        }
        // ostatnia deska ratunku: wyszukiwanie popularnego słowa w sklepie danego kraju
        try {
            List<Podcast> r = search("podcast", country);
            if (!r.isEmpty()) return r;
        } catch (IOException e) {
            last = e;
        }
        throw last != null ? last : new IOException("No chart");
    }

    public List<Podcast> search(String term, String country) throws IOException {
        String u = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=60"
                + "&country=" + country.toLowerCase(Locale.US) + "&term=" + Uri.encode(term);
        try {
            JSONObject o = new JSONObject(new String(Http.get(ctx, u, 4 * 1024 * 1024), "UTF-8"));
            return parseItunes(o.optJSONArray("results"));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.toString());
        }
    }

    /** Uzupełnia adres kanału RSS (podcasty z rankingu go nie mają). */
    public void resolveFeed(Podcast p) throws IOException {
        if (p.feedUrl.length() > 0) return;
        if (p.id.length() == 0) throw new IOException("No id");
        try {
            JSONObject o = new JSONObject(new String(
                    Http.get(ctx, "https://itunes.apple.com/lookup?entity=podcast&id=" + Uri.encode(p.id),
                            1024 * 1024), "UTF-8"));
            List<Podcast> r = parseItunes(o.optJSONArray("results"));
            for (Podcast x : r) {
                if (x.feedUrl.length() > 0) {
                    p.feedUrl = x.feedUrl;
                    if (p.artwork.length() == 0) p.artwork = x.artwork;
                    if (p.author.length() == 0) p.author = x.author;
                    return;
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.toString());
        }
        throw new IOException("No feed");
    }

    /** Pobiera i czyta kanał RSS – bez wczytywania całego (czasem wielomegabajtowego) pliku do pamięci. */
    public RssParser.Result episodes(Podcast p) throws IOException {
        resolveFeed(p);
        HttpURLConnection c = Http.open(ctx, p.feedUrl);
        try {
            InputStream in = c.getInputStream();
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(in, null);
            RssParser.Result r = RssParser.parse(xp, 300);
            if (r.image.length() == 0) r.image = p.bigArtwork();
            for (Episode e : r.episodes) {
                if (e.podcastName.length() == 0) e.podcastName = p.name;
                if (e.podcastArtwork.length() == 0) e.podcastArtwork = r.image;
            }
            return r;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.toString());
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- formaty odpowiedzi

    private static List<Podcast> parseTop(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject feed = root.optJSONObject("feed");
        List<Podcast> out = new ArrayList<>();
        if (feed == null) return out;

        JSONArray results = feed.optJSONArray("results");      // nowy format (api/v2)
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject o = results.getJSONObject(i);
                Podcast p = new Podcast();
                p.id = o.optString("id", "");
                p.name = o.optString("name", "");
                p.author = o.optString("artistName", "");
                p.artwork = sized(o.optString("artworkUrl100", ""), 200);
                if (p.id.length() > 0 && p.name.length() > 0) out.add(p);
            }
            return out;
        }

        JSONArray entries = feed.optJSONArray("entry");        // stary format (itunes.apple.com/rss)
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject o = entries.getJSONObject(i);
                Podcast p = new Podcast();
                p.name = label(o.optJSONObject("im:name"));
                p.author = label(o.optJSONObject("im:artist"));
                JSONObject id = o.optJSONObject("id");
                JSONObject attrs = id != null ? id.optJSONObject("attributes") : null;
                p.id = attrs != null ? attrs.optString("im:id", "") : "";
                JSONArray imgs = o.optJSONArray("im:image");
                if (imgs != null && imgs.length() > 0) {
                    p.artwork = sized(label(imgs.optJSONObject(imgs.length() - 1)), 200);
                }
                if (p.id.length() > 0 && p.name.length() > 0) out.add(p);
            }
        }
        return out;
    }

    private static List<Podcast> parseItunes(JSONArray arr) throws Exception {
        List<Podcast> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Podcast p = new Podcast();
            long id = o.optLong("collectionId", 0);
            p.id = id > 0 ? String.valueOf(id) : "";
            p.name = o.optString("collectionName", o.optString("trackName", ""));
            p.author = o.optString("artistName", "");
            String art = o.optString("artworkUrl100", "");
            if (art.length() == 0) art = o.optString("artworkUrl60", "");
            p.artwork = sized(art, 200);
            p.feedUrl = o.optString("feedUrl", "");
            if (p.name.length() > 0 && (p.feedUrl.length() > 0 || p.id.length() > 0)) out.add(p);
        }
        return out;
    }

    private static String label(JSONObject o) {
        return o != null ? o.optString("label", "") : "";
    }

    /** Adresy okładek Apple zawierają rozmiar, np. …/100x100bb.jpg – prosimy o większy. */
    private static String sized(String url, int px) {
        if (url == null) return "";
        return url.replaceAll("/\\d+x\\d+(bb|cc)?\\.(jpg|png|webp)$", "/" + px + "x" + px + "bb.jpg");
    }
}
