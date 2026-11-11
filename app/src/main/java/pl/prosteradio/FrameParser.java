package pl.prosteradio;

/**
 * Wyszukuje w strumieniu kolejne ramki dźwięku MP3 lub AAC/AAC+ (ADTS)
 * i podaje je dalej razem z czasem trwania – dzięki temu bufor można liczyć
 * w sekundach, a nie w bajtach.
 */
public class FrameParser implements StreamParser {

    public static final String MIME_MP3 = "audio/mpeg";
    public static final String MIME_AAC = "audio/mp4a-latm";
    public static final String MIME_VORBIS = "audio/vorbis";

    public static final class Format {
        public String mime;
        public int sampleRate;
        public int channels;
        public byte[] csd;      // konfiguracja dekodera (AAC: AudioSpecificConfig, Vorbis: nagłówek 1)
        public byte[] csd1;     // Vorbis: nagłówek 5 (setup)
        public String label;    // „MP3” / „AAC” / „Vorbis” – do logów
    }

    /**
     * Ramka dźwięku albo znacznik w kolejce: nowy tytuł utworu lub nowy format
     * (np. kolejny utwór w strumieniu Ogg). Znaczniki idą w kolejce razem z dźwiękiem,
     * więc tytuł pojawia się dokładnie wtedy, gdy ten utwór zaczyna grać – nawet przy
     * długim buforze.
     */
    public static final class Frame {
        public final byte[] data;
        public final double seconds;
        public final String title;
        public final Format newFormat;

        Frame(byte[] data, double seconds) {
            this(data, seconds, null, null);
        }

        private Frame(byte[] data, double seconds, String title, Format newFormat) {
            this.data = data;
            this.seconds = seconds;
            this.title = title;
            this.newFormat = newFormat;
        }

        static Frame title(String t) { return new Frame(new byte[0], 0, t, null); }
        static Frame formatChange(Format f) { return new Frame(new byte[0], 0, null, f); }
    }

    public interface Sink {
        void onFrame(Frame f);
    }

    private static final int[] MP3_BR_V1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] MP3_BR_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[][] MP3_SR = {
            {11025, 12000, 8000},   // MPEG 2.5
            null,                   // zarezerwowane
            {22050, 24000, 16000},  // MPEG 2
            {44100, 48000, 32000},  // MPEG 1
    };
    private static final int[] AAC_SR = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350};

    /** Po tylu bajtach bez rozpoznanej ramki uznajemy format za nieobsługiwany. */
    private static final int GIVE_UP_BYTES = 96 * 1024;

    private final Sink sink;
    private byte[] buf = new byte[64 * 1024];
    private int len;
    private Format format;
    private boolean synced;
    private int garbage;
    private boolean unsupported;

    // wynik ostatniego headerAt()
    private int hLen, hHeader, hRate, hChannels, hSamples;
    private boolean hAac;
    private int hProfile, hFreqIdx, hChanCfg;

    public FrameParser(Sink sink) {
        this.sink = sink;
    }

    @Override public Format format() { return format; }
    @Override public boolean isUnsupported() { return unsupported; }
    @Override public boolean isOpus() { return false; }

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
        while (len - pos >= 10) {
            if (!headerAt(pos)) {
                pos++;
                lostByte();
                continue;
            }
            int frameLen = hLen;
            if (!synced) {
                // potwierdź synchronizację: zaraz za ramką musi być kolejny zgodny nagłówek
                if (len - pos < frameLen + 10) break;
                boolean aac = hAac;
                int rate = hRate;
                int profile = hProfile, freqIdx = hFreqIdx, chanCfg = hChanCfg, channels = hChannels;
                if (!headerAt(pos + frameLen) || hAac != aac || hRate != rate) {
                    pos++;
                    lostByte();
                    continue;
                }
                headerAt(pos); // przywróć dane bieżącej ramki
                if (format == null) {
                    format = makeFormat(aac, rate, channels, profile, freqIdx, chanCfg);
                } else if (!format.mime.equals(aac ? MIME_AAC : MIME_MP3) || format.sampleRate != rate) {
                    // inny format w środku strumienia – nie próbujemy go przełączać
                    pos++;
                    lostByte();
                    continue;
                }
                synced = true;
                garbage = 0;
            } else if (hAac != MIME_AAC.equals(format.mime) || hRate != format.sampleRate) {
                synced = false;
                pos++;
                continue;
            }
            if (len - pos < frameLen) break;

            int start = pos + hHeader;
            int payload = frameLen - hHeader;
            byte[] data = new byte[payload];
            System.arraycopy(buf, start, data, 0, payload);
            sink.onFrame(new Frame(data, hSamples / (double) hRate));
            pos += frameLen;
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
        }
    }

    private void lostByte() {
        if (synced) synced = false;
        if (++garbage > GIVE_UP_BYTES && format == null) unsupported = true;
    }

    /** Czy w buf[p] zaczyna się poprawny nagłówek ramki MP3 (Layer III) lub ADTS. */
    private boolean headerAt(int p) {
        if (p + 7 > len) return false;
        int b0 = buf[p] & 0xff, b1 = buf[p + 1] & 0xff, b2 = buf[p + 2] & 0xff, b3 = buf[p + 3] & 0xff;
        if (b0 != 0xFF) return false;

        if ((b1 & 0xF6) == 0xF0) {               // ADTS (AAC / AAC+)
            int b4 = buf[p + 4] & 0xff, b5 = buf[p + 5] & 0xff, b6 = buf[p + 6] & 0xff;
            boolean protAbsent = (b1 & 1) == 1;
            int profile = (b2 >> 6) & 3;
            int freqIdx = (b2 >> 2) & 0xF;
            if (freqIdx >= AAC_SR.length) return false;
            int chanCfg = ((b2 & 1) << 2) | ((b3 >> 6) & 3);
            int frameLen = ((b3 & 3) << 11) | (b4 << 3) | ((b5 >> 5) & 7);
            int header = protAbsent ? 7 : 9;
            if (frameLen <= header || frameLen > 8192) return false;
            int blocks = (b6 & 3) + 1;
            hAac = true;
            hLen = frameLen;
            hHeader = header;
            hRate = AAC_SR[freqIdx];
            hChannels = chanCfg == 1 ? 1 : 2;
            hSamples = 1024 * blocks;
            hProfile = profile;
            hFreqIdx = freqIdx;
            hChanCfg = chanCfg == 0 ? 2 : chanCfg;
            return true;
        }

        if ((b1 & 0xE0) == 0xE0) {               // MPEG audio
            int version = (b1 >> 3) & 3;
            int layer = (b1 >> 1) & 3;
            if (version == 1 || layer != 1) return false; // tylko Layer III (MP3)
            int brIdx = (b2 >> 4) & 0xF;
            int srIdx = (b2 >> 2) & 3;
            if (brIdx == 0 || brIdx == 15 || srIdx == 3) return false;
            int pad = (b2 >> 1) & 1;
            boolean v1 = version == 3;
            int br = (v1 ? MP3_BR_V1 : MP3_BR_V2)[brIdx] * 1000;
            int sr = MP3_SR[version][srIdx];
            int frameLen = (v1 ? 144 : 72) * br / sr + pad;
            if (frameLen < 24) return false;
            hAac = false;
            hLen = frameLen;
            hHeader = 0;                       // dekoder MP3 dostaje całą ramkę
            hRate = sr;
            hChannels = ((b3 >> 6) & 3) == 3 ? 1 : 2;
            hSamples = v1 ? 1152 : 576;
            return true;
        }
        return false;
    }

    private static Format makeFormat(boolean aac, int rate, int channels, int profile, int freqIdx, int chanCfg) {
        Format f = new Format();
        f.sampleRate = rate;
        f.channels = channels;
        if (aac) {
            f.mime = MIME_AAC;
            f.label = "AAC";
            int objectType = profile + 1;  // 2 = AAC-LC; AAC+ (SBR/PS) dekoder wykryje sam
            f.csd = new byte[]{
                    (byte) ((objectType << 3) | (freqIdx >> 1)),
                    (byte) (((freqIdx & 1) << 7) | (chanCfg << 3)),
            };
        } else {
            f.mime = MIME_MP3;
            f.label = "MP3";
        }
        return f;
    }
}
