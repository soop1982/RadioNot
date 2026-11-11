package pl.prosteradio;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Otwiera strumień stacji radiowej (HTTP/HTTPS, przekierowania, listy .pls/.m3u,
 * stare serwery Shoutcast odpowiadające „ICY 200 OK”) i prosi o komunikaty ICY.
 */
public final class StreamSource {

    public interface TitleListener {
        void onTitle(String title);
    }

    public static final class Connection {
        public Socket socket;
        public InputStream in;
        public String contentType = "";
        public int metaInt;
    }

    private StreamSource() {}

    public static Connection open(String url) throws IOException {
        return open(url, 0);
    }

    private static Connection open(String url, int depth) throws IOException {
        if (depth > 6) throw new IOException("Too many redirects");
        URL u = new URL(url);
        String proto = u.getProtocol().toLowerCase(Locale.US);
        boolean https = proto.equals("https");
        if (!https && !proto.equals("http")) throw new IOException("Unsupported URL: " + url);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
        String path = u.getFile();
        if (path == null || path.length() == 0) path = "/";

        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 12000);
            s.setSoTimeout(20000);
            if (https) s = Tls.wrap(s, host, port);
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }

        boolean defPort = u.getPort() <= 0 || u.getPort() == (https ? 443 : 80);
        String req = "GET " + path + " HTTP/1.0\r\n"
                + "Host: " + host + (defPort ? "" : ":" + port) + "\r\n"
                + "User-Agent: " + Http.USER_AGENT + "\r\n"
                + "Accept: */*\r\n"
                + "Icy-MetaData: 1\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(req.getBytes("ISO-8859-1"));
        os.flush();

        InputStream in = new BufferedInputStream(s.getInputStream(), 32 * 1024);
        String status = readLine(in);
        if (status == null) { closeQuietly(s); throw new IOException("No response"); }
        String[] parts = status.split(" ");
        int code = parts.length > 1 ? parseInt(parts[1], 0) : 0;
        Map<String, String> h = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            int c = line.indexOf(':');
            if (c > 0) h.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
        }

        if (code >= 300 && code < 400 && h.containsKey("location")) {
            closeQuietly(s);
            return open(new URL(u, h.get("location")).toString(), depth + 1);
        }
        if (code != 200) {
            closeQuietly(s);
            throw new IOException("Server said: " + status);
        }

        String ct = h.containsKey("content-type") ? h.get("content-type") : "";
        String lowerPath = u.getPath().toLowerCase(Locale.US);
        String lowerCt = ct.toLowerCase(Locale.US);
        boolean hls = lowerCt.contains("mpegurl") && lowerPath.endsWith(".m3u8");
        boolean playlist = !hls && (lowerCt.contains("mpegurl") || lowerCt.contains("scpls")
                || lowerCt.contains("x-pls") || lowerPath.endsWith(".pls")
                || (lowerPath.endsWith(".m3u") && !lowerCt.startsWith("audio/mpeg")));
        if (playlist) {
            byte[] body = readUpTo(in, 64 * 1024);
            closeQuietly(s);
            String next = firstUrlIn(new String(body, "UTF-8"));
            if (next == null) throw new IOException("Empty playlist");
            return open(next, depth + 1);
        }

        Connection c = new Connection();
        c.socket = s;
        c.in = in;
        c.contentType = ct;
        c.metaInt = h.containsKey("icy-metaint") ? parseInt(h.get("icy-metaint"), 0) : 0;
        s.setSoTimeout(30000);
        return c;
    }

    private static String firstUrlIn(String text) {
        for (String raw : text.split("\n")) {
            String l = raw.trim();
            int eq = l.indexOf('=');
            if (l.toLowerCase(Locale.US).startsWith("file") && eq > 0) l = l.substring(eq + 1).trim();
            if (l.startsWith("http://") || l.startsWith("https://")) return l;
        }
        return null;
    }

    // ---------------------------------------------------------------- komunikaty ICY

    /** Strumień, który usuwa wplecione komunikaty ICY i przekazuje tytuły do słuchacza. */
    public static final class IcyInputStream extends InputStream {
        private final InputStream in;
        private final int metaInt;
        private final TitleListener listener;
        private int remaining;

        public IcyInputStream(InputStream in, int metaInt, TitleListener listener) {
            this.in = in;
            this.metaInt = metaInt;
            this.listener = listener;
            this.remaining = metaInt;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : (b[0] & 0xff);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (metaInt <= 0) return in.read(b, off, len);
            if (remaining == 0) {
                int ml = in.read();
                if (ml < 0) return -1;
                ml *= 16;
                if (ml > 0) {
                    String t = parseTitle(readFully(in, ml));
                    if (t != null && listener != null) listener.onTitle(t);
                }
                remaining = metaInt;
            }
            int n = in.read(b, off, Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    static String parseTitle(byte[] meta) {
        int end = meta.length;
        while (end > 0 && meta[end - 1] == 0) end--;
        String s = decode(meta, end);
        String key = "StreamTitle='";
        int i = s.indexOf(key);
        if (i < 0) return null;
        i += key.length();
        int j = s.indexOf("';", i);
        if (j < 0) j = s.lastIndexOf('\'');
        if (j < i) j = s.length();
        return s.substring(i, j).trim();
    }

    /** Najpierw UTF-8; jeśli bajty nie pasują – Windows-1250 (polskie znaki). */
    private static String decode(byte[] b, int len) {
        try {
            CharsetDecoder d = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return d.decode(ByteBuffer.wrap(b, 0, len)).toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(b, 0, len, "windows-1250");
            } catch (Exception e2) {
                try { return new String(b, 0, len, "ISO-8859-1"); } catch (Exception e3) { return ""; }
            }
        }
    }

    // ---------------------------------------------------------------- narzędzia

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
            if (bos.size() > 8192) throw new IOException("Header too long");
        }
        if (c < 0 && bos.size() == 0) return null;
        return bos.toString("ISO-8859-1");
    }

    static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new IOException("End of stream");
            off += n;
        }
        return b;
    }

    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (bos.size() < max && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    public static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }
}
