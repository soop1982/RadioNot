package pl.prosteradio;

import org.xmlpull.v1.XmlPullParser;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Czyta kanał RSS podcastu: tytuł, okładkę i listę odcinków z plikami dźwiękowymi.
 * Parser jest bez obsługi przestrzeni nazw, więc „itunes:duration” przychodzi jako
 * pełna nazwa – porównujemy nazwy bez prefiksu.
 */
public final class RssParser {

    public static final class Result {
        public String title = "";
        public String image = "";
        public final List<Episode> episodes = new ArrayList<>();
    }

    private static final String[] DATE_FORMATS = {
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "d MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "EEE, d MMM yyyy",
    };

    private RssParser() {}

    public static Result parse(XmlPullParser p, int maxEpisodes) throws Exception {
        Result r = new Result();
        try {
            loop(p, r, maxEpisodes);
        } catch (Exception e) {
            // część kanałów ma drobne błędy XML (np. &nbsp;) – zostaw odcinki odczytane do tej pory
            if (r.episodes.isEmpty()) throw e;
        }
        for (Episode e : r.episodes) {
            e.podcastName = r.title;
            e.podcastArtwork = r.image;
        }
        return r;
    }

    private static void loop(XmlPullParser p, Result r, int maxEpisodes) throws Exception {
        Episode cur = null;
        boolean inImage = false;      // <image> kanału (ze starym <url>)
        boolean imageFromHref = false;
        int depth = 0, itemDepth = -1;
        String audioType = "";

        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                String name = local(p.getName());
                if (name.equals("item") || name.equals("entry")) {
                    cur = new Episode();
                    itemDepth = depth;
                    audioType = "";
                } else if (cur != null) {
                    switch (name) {
                        case "title":
                            if (depth == itemDepth + 1 && cur.title.length() == 0) {
                                cur.title = text(p);
                                depth--; // text() przeszedł do END_TAG
                            }
                            break;
                        case "guid":
                        case "id":
                            if (cur.guid.length() == 0) cur.guid = text(p);
                            else text(p);
                            depth--;
                            break;
                        case "pubDate":
                        case "published":
                            cur.published = parseDate(text(p));
                            depth--;
                            break;
                        case "duration":
                            cur.durationSec = parseDuration(text(p));
                            depth--;
                            break;
                        case "image": {
                            String href = p.getAttributeValue(null, "href");
                            if (href != null && cur.image.length() == 0) cur.image = href.trim();
                            break;
                        }
                        case "enclosure":
                        case "content":
                        case "link": {
                            String url = p.getAttributeValue(null, "url");
                            if (url == null && name.equals("link")) url = p.getAttributeValue(null, "href");
                            String type = p.getAttributeValue(null, "type");
                            if (type == null) type = "";
                            String rel = p.getAttributeValue(null, "rel");
                            boolean audio = type.startsWith("audio") || name.equals("enclosure")
                                    || "enclosure".equals(rel);
                            if (url != null && audio && !type.startsWith("video")
                                    && (cur.audioUrl.length() == 0 || (!audioType.startsWith("audio") && type.startsWith("audio")))) {
                                cur.audioUrl = url.trim();
                                audioType = type;
                            }
                            break;
                        }
                        default:
                            break;
                    }
                } else {
                    // poziom kanału
                    if (name.equals("title") && r.title.length() == 0 && !inImage) {
                        r.title = text(p);
                        depth--;
                    } else if (name.equals("image")) {
                        String href = p.getAttributeValue(null, "href");
                        if (href != null) {
                            r.image = href.trim(); // itunes:image – zwykle najlepsza jakość
                            imageFromHref = true;
                        } else {
                            inImage = true;
                        }
                    } else if (name.equals("url") && inImage) {
                        String u = text(p);
                        if (!imageFromHref) r.image = u;
                        depth--;
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String name = local(p.getName());
                if (cur != null && depth == itemDepth && (name.equals("item") || name.equals("entry"))) {
                    if (cur.audioUrl.length() > 0) {
                        if (cur.title.length() == 0) cur.title = "—";
                        r.episodes.add(cur);
                        if (r.episodes.size() >= maxEpisodes) break;
                    }
                    cur = null;
                    itemDepth = -1;
                } else if (name.equals("image")) {
                    inImage = false;
                }
                depth--;
            }
            ev = p.next();
        }
    }

    /** Odczytuje tekst elementu i zostawia parser na jego END_TAG. */
    private static String text(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ev = p.next();
        int nested = 0;
        while (!(ev == XmlPullParser.END_TAG && nested == 0)) {
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                String t = p.getText();
                if (t != null) sb.append(t);
            } else if (ev == XmlPullParser.START_TAG) {
                nested++;
            } else if (ev == XmlPullParser.END_TAG) {
                nested--;
            } else if (ev == XmlPullParser.END_DOCUMENT) {
                break;
            }
            ev = p.next();
        }
        return sb.toString().trim();
    }

    static String local(String name) {
        if (name == null) return "";
        int i = name.indexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    /** „1:02:03”, „62:03”, „3723” → sekundy. */
    static int parseDuration(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.length() == 0) return 0;
        try {
            String[] parts = s.split(":");
            int total = 0;
            for (String part : parts) total = total * 60 + (int) Double.parseDouble(part.trim());
            return Math.max(0, total);
        } catch (Exception e) {
            return 0;
        }
    }

    static long parseDate(String s) {
        if (s == null) return 0;
        s = s.trim().replaceAll("\\s+", " ");
        for (String f : DATE_FORMATS) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(f, Locale.US);
                df.setLenient(true);
                Date d = df.parse(s, new ParsePosition(0));
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0;
    }
}
