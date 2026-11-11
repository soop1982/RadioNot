package pl.prosteradio;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.SSLException;

/** Proste pobieranie danych przez HTTP(S) z obsługą przekierowań. */
public final class Http {
    public static final String USER_AGENT = "ProsteRadio/1.0 (Android)";

    private Http() {}

    public static byte[] get(Context ctx, String url, int maxBytes) throws IOException {
        Tls.init(ctx.getApplicationContext());
        try {
            return getFollow(url, maxBytes);
        } catch (SSLException e) {
            // Jeśli TLS zawiódł mimo poprawek, spróbuj tego samego adresu przez zwykłe HTTP.
            if (url.startsWith("https://")) {
                return getFollow("http://" + url.substring(8), maxBytes);
            }
            throw e;
        }
    }

    private static byte[] getFollow(String url, int maxBytes) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept-Encoding", "identity");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("Przekierowanie bez adresu");
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new IOException("Za duży plik");
                    bos.write(buf, 0, n);
                }
                in.close();
                return bos.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Za dużo przekierowań");
    }

    /** Wysyła zapytanie i ignoruje wynik (np. licznik kliknięć). */
    public static void ping(final Context ctx, final String url) {
        new Thread(() -> {
            try { get(ctx, url, 64 * 1024); } catch (Exception ignored) {}
        }, "ping").start();
    }
}
