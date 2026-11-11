package pl.prosteradio;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Rozbiera strumień Ogg na pakiety Vorbis dla dekodera.
 *
 * Stacje Ogg (Icecast) przy każdym nowym utworze zaczynają nowy „łańcuch”
 * z nagłówkami i komentarzami – stąd bierzemy tytuł i wykrywamy, czy trzeba
 * przestawić dekoder. Rozpoznaje też Ogg Opus (zgłasza isOpus()).
 */
public class OggParser implements StreamParser {
    private static final int GIVE_UP_BYTES = 96 * 1024;

    private final FrameParser.Sink sink;
    private byte[] buf = new byte[64 * 1024];
    private int len;

    private int serial;
    private boolean haveSerial;
    private int headerStage;              // 0 = czekam na nagłówek 1, 1 = komentarze, 2 = setup, 3 = dźwięk
    private byte[] newId;
    private final ByteArrayOutputStream packet = new ByteArrayOutputStream();
    private final List<byte[]> pagePackets = new ArrayList<>();
    private final List<byte[]> audio = new ArrayList<>();

    private FrameParser.Format format;
    private int rate;
    private long prevGranule = -1;
    private double estPacketSec = 0.02;
    private boolean opus;
    private boolean unsupported;
    private int garbage;

    public OggParser(FrameParser.Sink sink) {
        this.sink = sink;
    }

    @Override public FrameParser.Format format() { return format; }
    @Override public boolean isUnsupported() { return unsupported; }
    @Override public boolean isOpus() { return opus; }

    @Override
    public void feed(byte[] b, int off, int n) {
        if (len + n > buf.length) {
            byte[] nb = new byte[Math.max(buf.length * 2, len + n)];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
        System.arraycopy(b, off, buf, len, n);
        len += n;
        parse();
    }

    private void parse() {
        int pos = 0;
        while (len - pos >= 27) {
            if (buf[pos] != 'O' || buf[pos + 1] != 'g' || buf[pos + 2] != 'g' || buf[pos + 3] != 'S'
                    || buf[pos + 4] != 0) {
                pos++;
                if (++garbage > GIVE_UP_BYTES && format == null) unsupported = true;
                continue;
            }
            int nseg = buf[pos + 26] & 0xff;
            if (len - pos < 27 + nseg) break;
            int body = 0;
            for (int i = 0; i < nseg; i++) body += buf[pos + 27 + i] & 0xff;
            int total = 27 + nseg + body;
            if (len - pos < total) break;
            page(pos, nseg);
            pos += total;
            garbage = 0;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
        }
    }

    private void page(int p, int nseg) {
        int flags = buf[p + 5] & 0xff;
        long granule = le64(buf, p + 6);
        int ser = le32(buf, p + 14);

        if ((flags & 0x02) != 0) {            // początek nowego strumienia (np. nowy utwór)
            serial = ser;
            haveSerial = true;
            headerStage = 0;
            packet.reset();
            prevGranule = -1;
        } else if (!haveSerial || ser != serial) {
            return;                           // inny strumień w tym samym pliku – pomijamy
        }
        if ((flags & 0x01) == 0) packet.reset(); // brak kontynuacji – resztka po zgubionej stronie

        pagePackets.clear();
        int data = p + 27 + nseg;
        for (int i = 0; i < nseg; i++) {
            int l = buf[p + 27 + i] & 0xff;
            packet.write(buf, data, l);
            data += l;
            if (l < 255) {
                pagePackets.add(packet.toByteArray());
                packet.reset();
            }
        }

        audio.clear();
        for (byte[] pkt : pagePackets) {
            if (headerStage < 3) header(pkt);
            else if (pkt.length > 0 && (pkt[0] & 1) == 0) audio.add(pkt); // pakiety dźwięku mają bit 0 = 0
        }
        if (!audio.isEmpty() && format != null) {
            double each = estPacketSec;
            if (granule >= 0 && prevGranule >= 0 && granule > prevGranule
                    && granule - prevGranule < (long) rate * 10) {
                each = (granule - prevGranule) / (double) rate / audio.size();
            }
            for (byte[] pkt : audio) {
                // dekoder Vorbis w Androidzie oczekuje na końcu 4 bajtów z liczbą próbek;
                // -1 oznacza „nieznana” i dekoder po prostu oddaje wszystko
                byte[] d = Arrays.copyOf(pkt, pkt.length + 4);
                d[pkt.length] = d[pkt.length + 1] = d[pkt.length + 2] = d[pkt.length + 3] = (byte) 0xFF;
                sink.onFrame(new FrameParser.Frame(d, each));
            }
        }
        if (granule >= 0) prevGranule = granule;
    }

    private void header(byte[] pkt) {
        if (opus) return;
        if (headerStage == 0) {
            if (isVorbis(pkt, 1) && pkt.length >= 30) {
                newId = pkt;
                headerStage = 1;
            } else if (startsWith(pkt, "OpusHead")) {
                opus = true;
            } else if (format == null) {
                unsupported = true;           // np. FLAC lub Speex w Ogg
            }
        } else if (headerStage == 1) {
            if (isVorbis(pkt, 3)) {
                String t = comments(pkt);
                if (t != null) sink.onFrame(FrameParser.Frame.title(t));
                headerStage = 2;
            }
        } else if (headerStage == 2) {
            if (isVorbis(pkt, 5)) {
                headerStage = 3;
                setup(newId, pkt);
            }
        }
    }

    private void setup(byte[] id, byte[] setupHdr) {
        int channels = id[11] & 0xff;
        int r = le32(id, 12);
        int blocks = id[28] & 0xff;
        int longBlock = 1 << ((blocks >> 4) & 0xF);
        if (r <= 0 || channels <= 0) { unsupported = format == null; return; }
        rate = r;
        estPacketSec = (longBlock / 2.0) / r;

        if (format != null && format.sampleRate == r && format.channels == channels
                && Arrays.equals(format.csd1, setupHdr)
                && format.csd != null && format.csd[28] == id[28]) {
            return;                           // ten sam format – dekoder może grać dalej
        }
        FrameParser.Format f = new FrameParser.Format();
        f.mime = FrameParser.MIME_VORBIS;
        f.label = "Vorbis";
        f.sampleRate = r;
        f.channels = channels;
        f.csd = id;
        f.csd1 = setupHdr;
        boolean first = format == null;
        format = f;
        if (!first) sink.onFrame(FrameParser.Frame.formatChange(f));
    }

    /** Wyciąga „WYKONAWCA - TYTUŁ” z komentarzy Vorbis. */
    private static String comments(byte[] p) {
        try {
            int i = 7;
            int vendor = le32(p, i);
            i += 4 + vendor;
            int n = le32(p, i);
            i += 4;
            String artist = null, title = null;
            for (int k = 0; k < n && i + 4 <= p.length; k++) {
                int l = le32(p, i);
                i += 4;
                if (l < 0 || i + l > p.length) break;
                String c = new String(p, i, l, "UTF-8");
                i += l;
                int eq = c.indexOf('=');
                if (eq <= 0) continue;
                String key = c.substring(0, eq).toUpperCase(Locale.US);
                String val = c.substring(eq + 1).trim();
                if (val.length() == 0) continue;
                if (key.equals("TITLE")) title = val;
                else if (key.equals("ARTIST")) artist = val;
            }
            if (artist != null && title != null) return artist + " - " + title;
            return title != null ? title : artist;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isVorbis(byte[] p, int type) {
        return p.length >= 7 && (p[0] & 0xff) == type && p[1] == 'v' && p[2] == 'o' && p[3] == 'r'
                && p[4] == 'b' && p[5] == 'i' && p[6] == 's';
    }

    private static boolean startsWith(byte[] p, String s) {
        if (p.length < s.length()) return false;
        for (int i = 0; i < s.length(); i++) if (p[i] != s.charAt(i)) return false;
        return true;
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24);
    }

    private static long le64(byte[] b, int p) {
        return (le32(b, p) & 0xffffffffL) | ((long) le32(b, p + 4) << 32);
    }
}
