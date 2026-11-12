package pl.prosteradio;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lokalny pośrednik dla odcinków podcastów odtwarzanych przez MediaPlayer.
 *
 * Odcinki to zwykłe pliki (MP3/M4A) – MediaPlayer przy przewijaniu pyta o fragmenty
 * pliku (nagłówek Range). Android 4.4 nie łączy się sam z wieloma współczesnymi
 * serwerami HTTPS i nie podąża za przekierowaniami http→https (typowe u hostingów
 * podcastów), więc pobieramy dane sami (z poprawionym TLS) i podajemy je lokalnie.
 */
public class MediaProxy {
    private static final String TAG = "MediaProxy";

    private volatile ServerSocket server;
    private volatile String target;
    private volatile int generation;
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
    private int counter;

    public synchronized void start() throws IOException {
        if (server != null) return;
        final ServerSocket ss = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        server = ss;
        Thread t = new Thread(() -> acceptLoop(ss), "media-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized String urlFor(String url) throws IOException {
        ServerSocket ss = server;
        if (ss == null) throw new IOException("Proxy not running");
        stopAll();
        target = url;
        // rozszerzenie w adresie pomaga MediaPlayerowi rozpoznać format
        String ext = url.toLowerCase(Locale.US).contains(".m4a") ? ".m4a" : ".mp3";
        return "http://127.0.0.1:" + ss.getLocalPort() + "/e/" + (++counter) + ext;
    }

    public synchronized void stop() {
        target = null;
        stopAll();
    }

    public synchronized void shutdown() {
        stop();
        ServerSocket ss = server;
        server = null;
        if (ss != null) try { ss.close(); } catch (Throwable ignored) {}
    }

    private void stopAll() {
        generation++;
        synchronized (clients) {
            for (Socket c : clients) StreamSource.shutdown(c);
            clients.clear();
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                final Socket client = ss.accept();
                Thread t = new Thread(() -> handle(client), "media-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable e) {
                if (ss.isClosed()) return;
            }
        }
    }

    private void handle(Socket client) {
        HttpURLConnection c = null;
        clients.add(client);
        try {
            final int myGen = generation;
            final String url = target;
            InputStream cin = client.getInputStream();
            String requestLine = StreamSource.readLine(cin);
            String range = null;
            String line;
            while ((line = StreamSource.readLine(cin)) != null && line.length() > 0) {
                int i = line.indexOf(':');
                if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("range")) {
                    range = line.substring(i + 1).trim();
                }
            }
            OutputStream out = client.getOutputStream();
            if (url == null || requestLine == null) {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes("ISO-8859-1"));
                return;
            }
            boolean head = requestLine.startsWith("HEAD");

            // pobieranie z serwera podcastu, z ręczną obsługą przekierowań (także http→https)
            String u = url;
            int code = 0;
            for (int hop = 0; hop < 10; hop++) {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", Http.USER_AGENT);
                c.setRequestProperty("Accept-Encoding", "identity");
                if (range != null) c.setRequestProperty("Range", range);
                code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (loc == null) throw new IOException("Redirect without location");
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                break;
            }
            if (c == null) throw new IOException("Too many redirects");
            if (myGen != generation) return;

            StringBuilder h = new StringBuilder();
            String reason = code == 206 ? "Partial Content" : code == 200 ? "OK" : "Error";
            h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
            String ct = c.getContentType();
            if (ct == null || ct.length() == 0 || ct.startsWith("application/octet")) {
                ct = u.toLowerCase(Locale.US).contains(".m4a") ? "audio/mp4" : "audio/mpeg";
            }
            h.append("Content-Type: ").append(ct).append("\r\n");
            String len = c.getHeaderField("Content-Length");
            if (len != null) h.append("Content-Length: ").append(len).append("\r\n");
            String cr = c.getHeaderField("Content-Range");
            if (cr != null) h.append("Content-Range: ").append(cr).append("\r\n");
            h.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes("ISO-8859-1"));
            out.flush();
            if (head || code >= 400) return;

            InputStream in = c.getInputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while (myGen == generation && (n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Throwable e) {
            Log.d(TAG, "Connection ended: " + e);
        } finally {
            clients.remove(client);
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
            StreamSource.closeQuietly(client);
        }
    }
}
