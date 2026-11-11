package pl.prosteradio;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Naprawia HTTPS na Androidzie 4.4:
 *  1. włącza TLS 1.1 / 1.2 (na 4.4 są obsługiwane, ale domyślnie wyłączone),
 *  2. dokłada nowsze certyfikaty główne (np. Let's Encrypt ISRG Root X1),
 *     których stary system nie zna – zapisane w assets/roots.pem.
 * Certyfikaty systemowe nadal są używane; weryfikacja nie jest wyłączana.
 */
public final class Tls {
    private static final String TAG = "Tls";
    private static SSLSocketFactory factory;

    private Tls() {}

    public static synchronized void init(Context ctx) {
        if (factory != null) return;
        try {
            X509TrustManager system = trustManagerFor(null);
            X509TrustManager bundled = trustManagerFor(loadBundled(ctx));
            X509TrustManager combined = new CombinedTrustManager(system, bundled);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{combined}, null);
            factory = new ModernSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultSSLSocketFactory(factory);
        } catch (Exception e) {
            Log.w(TAG, "Nie udało się skonfigurować TLS", e);
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    public static SSLSocketFactory factory() {
        return factory != null ? factory : (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    /** Owija zwykłe połączenie TCP w TLS (z SNI i sprawdzeniem nazwy hosta). */
    public static SSLSocket wrap(Socket plain, String host, int port) throws IOException {
        SSLSocket s = (SSLSocket) factory().createSocket(plain, host, port, true);
        try { // SNI – na starszych Androidach dostępne tylko przez refleksję
            Method m = s.getClass().getMethod("setHostname", String.class);
            m.invoke(s, host);
        } catch (Throwable ignored) {}
        s.startHandshake();
        HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
        if (!hv.verify(host, s.getSession())) {
            try { s.close(); } catch (IOException ignored) {}
            throw new SSLPeerUnverifiedException("Certyfikat nie pasuje do " + host);
        }
        return s;
    }

    private static KeyStore loadBundled(Context ctx) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        InputStream in = ctx.getAssets().open("roots.pem");
        String pem;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            pem = bos.toString("US-ASCII");
        } finally {
            in.close();
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        String begin = "-----BEGIN CERTIFICATE-----";
        String end = "-----END CERTIFICATE-----";
        int idx = 0, count = 0;
        while (true) {
            int b = pem.indexOf(begin, idx);
            if (b < 0) break;
            int e = pem.indexOf(end, b);
            if (e < 0) break;
            String body = pem.substring(b + begin.length(), e);
            idx = e + end.length();
            try {
                byte[] der = Base64.decode(body, Base64.DEFAULT);
                X509Certificate c = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
                ks.setCertificateEntry("root" + (count++), c);
            } catch (Exception ex) {
                Log.w(TAG, "Pominięto certyfikat: " + ex);
            }
        }
        return ks;
    }

    private static X509TrustManager trustManagerFor(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new IllegalStateException("Brak X509TrustManager");
    }

    /** Ufa certyfikatowi, jeśli zaakceptuje go system ALBO dołączony zestaw. */
    private static final class CombinedTrustManager implements X509TrustManager {
        private final X509TrustManager a, b;

        CombinedTrustManager(X509TrustManager a, X509TrustManager b) { this.a = a; this.b = b; }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            a.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                a.checkServerTrusted(chain, authType);
            } catch (CertificateException first) {
                b.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] x = a.getAcceptedIssuers(), y = b.getAcceptedIssuers();
            X509Certificate[] r = Arrays.copyOf(x, x.length + y.length);
            System.arraycopy(y, 0, r, x.length, y.length);
            return r;
        }
    }

    /** Włącza TLS 1.2/1.1 i bezpieczne szyfry na każdym tworzonym gnieździe. */
    private static final class ModernSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory d;

        ModernSocketFactory(SSLSocketFactory d) { this.d = d; }

        private Socket tune(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> protos = new ArrayList<>();
                for (String p : ss.getSupportedProtocols()) {
                    if (p.equals("TLSv1.3") || p.equals("TLSv1.2") || p.equals("TLSv1.1") || p.equals("TLSv1")) {
                        protos.add(p);
                    }
                }
                if (!protos.isEmpty()) ss.setEnabledProtocols(protos.toArray(new String[0]));
                List<String> ciphers = new ArrayList<>();
                for (String c : ss.getSupportedCipherSuites()) {
                    String u = c.toUpperCase();
                    if (u.contains("NULL") || u.contains("ANON") || u.contains("EXPORT")
                            || u.contains("_DES_") || u.contains("RC4") || u.contains("MD5")) continue;
                    ciphers.add(c);
                }
                if (!ciphers.isEmpty()) ss.setEnabledCipherSuites(ciphers.toArray(new String[0]));
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }

        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return tune(d.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return tune(d.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return tune(d.createSocket(host, port, localHost, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return tune(d.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return tune(d.createSocket(address, port, localAddress, localPort));
        }
    }
}
