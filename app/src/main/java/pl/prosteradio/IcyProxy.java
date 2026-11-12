package pl.prosteradio;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Lokalny pośrednik dla wbudowanego odtwarzacza Androida (MediaPlayer).
 * Używany tylko awaryjnie – dla formatów, których nie dekoduje StreamPlayer.
 * Usuwa komunikaty ICY ze strumienia i przekazuje tytuły dalej.
 */
public class IcyProxy {
    private static final String TAG = "IcyProxy";

    private final StreamSource.TitleListener listener;
    private volatile ServerSocket server;
    private volatile String target;
    private volatile int generation;
    private volatile Socket activeClient;
    private volatile StreamSource.Holder activeUpstream;
    private int counter;

    public IcyProxy(StreamSource.TitleListener listener) {
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        final ServerSocket ss = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        server = ss;
        Thread t = new Thread(() -> acceptLoop(ss), "icy-accept");
        t.setDaemon(true);
        t.start();
    }

    /** Ustawia stację i zwraca lokalny adres, który należy podać odtwarzaczowi. */
    public synchronized String urlFor(String streamUrl) throws IOException {
        ServerSocket ss = server;
        if (ss == null) throw new IOException("Proxy not running");
        target = streamUrl;
        generation++;
        abortActive();
        return "http://127.0.0.1:" + ss.getLocalPort() + "/stream/" + (++counter);
    }

    public synchronized void stopStream() {
        target = null;
        generation++;
        abortActive();
    }

    public synchronized void shutdown() {
        stopStream();
        ServerSocket ss = server;
        server = null;
        if (ss != null) try { ss.close(); } catch (Throwable ignored) {}
    }

    /** Przerywa bieżące połączenia bez zamykania gniazd z obcego wątku. */
    private void abortActive() {
        StreamSource.shutdown(activeClient);
        StreamSource.Holder h = activeUpstream;
        if (h != null) h.abort();
        activeClient = null;
        activeUpstream = null;
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                final Socket client = ss.accept();
                Thread t = new Thread(() -> handle(client), "icy-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable e) {
                if (ss.isClosed()) return;
            }
        }
    }

    private void handle(Socket client) {
        StreamSource.Connection up = null;
        StreamSource.Holder holder = new StreamSource.Holder();
        try {
            final int myGen;
            String url;
            synchronized (this) {
                myGen = generation;
                url = target;
                // nowe połączenie od odtwarzacza zastępuje poprzednie
                abortActive();
                activeClient = client;
                activeUpstream = holder;
            }
            InputStream cin = client.getInputStream();
            String requestLine = StreamSource.readLine(cin);
            String line;
            do { line = StreamSource.readLine(cin); } while (line != null && line.length() > 0);
            OutputStream cout = client.getOutputStream();

            if (url == null) {
                cout.write("HTTP/1.0 404 Not Found\r\n\r\n".getBytes("ISO-8859-1"));
                return;
            }

            up = StreamSource.open(url, holder);
            if (myGen != generation) return;

            String ct = up.contentType.length() > 0 ? up.contentType : "audio/mpeg";
            String head = "HTTP/1.0 200 OK\r\n"
                    + "Content-Type: " + ct + "\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            cout.write(head.getBytes("ISO-8859-1"));
            cout.flush();
            if (requestLine != null && requestLine.startsWith("HEAD")) return;

            InputStream in = new StreamSource.IcyInputStream(up.in, up.metaInt, t -> {
                if (myGen == generation) listener.onTitle(t);
            });
            byte[] buf = new byte[16 * 1024];
            int n;
            while (myGen == generation && (n = in.read(buf)) >= 0) {
                cout.write(buf, 0, n);
            }
        } catch (Throwable e) {
            Log.d(TAG, "Connection ended: " + e);
        } finally {
            if (up != null) StreamSource.closeQuietly(up.socket);
            StreamSource.closeQuietly(client);
        }
    }
}
