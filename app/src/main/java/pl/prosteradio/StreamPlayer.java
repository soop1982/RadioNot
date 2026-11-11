package pl.prosteradio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Własny odtwarzacz strumieni MP3 i AAC/AAC+ (HE-AAC).
 *
 * Wbudowany MediaPlayer Androida 4.4 zawiesza się na strumieniach AAC+
 * („Łączenie…” bez końca) i nie pozwala ustawić długości bufora. Tutaj:
 *  - wątek sieciowy pobiera dane i dzieli je na ramki (FrameParser),
 *  - bufor jest liczony w sekundach dźwięku, z raportowaniem postępu,
 *  - wątek dekodera zamienia ramki na dźwięk (MediaCodec) i gra je (AudioTrack).
 */
public class StreamPlayer {
    private static final String TAG = "StreamPlayer";

    public static final int FAIL_NETWORK = 1;      // brak połączenia / zerwane
    public static final int FAIL_UNSUPPORTED = 2;  // format inny niż MP3/AAC
    public static final int FAIL_DECODER = 3;      // urządzenie nie umie zdekodować
    public static final int FAIL_OPUS = 4;         // Ogg Opus – Android 4.4 go nie obsługuje

    public interface Callback {
        void onConnecting(StreamPlayer p);
        void onBuffering(StreamPlayer p, int percent, long kb);
        void onPlaying(StreamPlayer p);
        void onTitle(StreamPlayer p, String title);
        void onFailed(StreamPlayer p, int reason, boolean wasPlaying);
    }

    /** Ile sekund ponad docelowy bufor wolno trzymać w pamięci. */
    private static final double MAX_AHEAD = 20;

    private final String url;
    private final double target;
    private final Callback cb;

    private final Object lock = new Object();
    private final ArrayDeque<FrameParser.Frame> queue = new ArrayDeque<>();
    private double queued;           // sekundy dźwięku w kolejce
    private boolean netDone;
    private boolean buffering = true;
    private volatile FrameParser.Format format;

    private volatile boolean stopped;
    private volatile Socket socket;
    private volatile AudioTrack track;
    private volatile float volume = 1f;
    private volatile boolean everPlayed;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile long bytes;
    private long lastReport;

    public StreamPlayer(String url, int bufferSeconds, Callback cb) {
        this.url = url;
        this.target = Math.max(0.5, bufferSeconds);
        this.cb = cb;
    }

    public void start() {
        Thread net = new Thread(this::netLoop, "stream-net");
        Thread dec = new Thread(this::decodeLoop, "stream-dec");
        net.setDaemon(true);
        dec.setDaemon(true);
        net.start();
        dec.start();
    }

    public void stop() {
        stopped = true;
        synchronized (lock) { lock.notifyAll(); }
        StreamSource.closeQuietly(socket);
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (Exception ignored) {}
        }
    }

    public void setVolume(float v) {
        volume = v;
        AudioTrack t = track;
        if (t != null) {
            try { t.setStereoVolume(v, v); } catch (Exception ignored) {}
        }
    }

    private void fail(int reason) {
        if (stopped || !finished.compareAndSet(false, true)) return;
        boolean wasPlaying = everPlayed;
        stop();
        cb.onFailed(this, reason, wasPlaying);
    }

    // ---------------------------------------------------------------- sieć

    private void netLoop() {
        StreamSource.Connection c = null;
        try {
            cb.onConnecting(this);
            c = StreamSource.open(url);
            socket = c.socket;
            if (stopped) return;

            String ct = c.contentType.toLowerCase(Locale.US);
            if (ct.contains("flac") || ct.contains("mpegurl") || ct.startsWith("video")) {
                fail(FAIL_UNSUPPORTED);
                return;
            }
            reportBuffering(true);

            // tytuł z komunikatu ICY idzie do kolejki – pokaże się, gdy ta muzyka zacznie grać
            InputStream in = new StreamSource.IcyInputStream(c.in, c.metaInt, t -> {
                if (!stopped) addFrame(FrameParser.Frame.title(t));
            });
            byte[] b = new byte[8192];
            StreamParser parser = null;
            while (!stopped) {
                int n = in.read(b);
                if (n < 0) break;
                bytes += n;
                if (parser == null) {
                    boolean ogg = ct.contains("ogg") || ct.contains("opus")
                            || (n >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S');
                    parser = ogg ? new OggParser(this::addFrame) : new FrameParser(this::addFrame);
                }
                parser.feed(b, 0, n);
                if (format == null && parser.format() != null) setFormat(parser.format());
                if (parser.isOpus()) {
                    fail(FAIL_OPUS);
                    return;
                }
                if (parser.isUnsupported()) {
                    Log.w(TAG, "Nieznany format, content-type=" + c.contentType);
                    fail(FAIL_UNSUPPORTED);
                    return;
                }
                reportBuffering(false);
                synchronized (lock) {
                    // nie trzymaj w pamięci więcej niż potrzeba
                    while (!stopped && queued > target + MAX_AHEAD) lock.wait(500);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            Log.d(TAG, "Sieć: " + e);
        } finally {
            if (c != null) StreamSource.closeQuietly(c.socket);
            synchronized (lock) {
                netDone = true;
                lock.notifyAll();
            }
        }
    }

    private void addFrame(FrameParser.Frame f) {
        synchronized (lock) {
            queue.add(f);
            queued += f.seconds;
            lock.notifyAll();
        }
    }

    private void setFormat(FrameParser.Format f) {
        synchronized (lock) { format = f; }
    }

    private synchronized void reportBuffering(boolean force) {
        boolean buf;
        double q;
        synchronized (lock) {
            buf = buffering;
            q = queued;
        }
        if (!buf || stopped) return;
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastReport < 200) return;
        lastReport = now;
        int pct = (int) Math.min(99, q * 100 / target);
        cb.onBuffering(this, pct, bytes / 1024);
    }

    // ---------------------------------------------------------------- dekodowanie

    private void decodeLoop() {
        MediaCodec codec = null;
        AudioTrack at = null;
        boolean announce = true;
        try {
            FrameParser.Format fmt = waitForPrebuffer();
            if (fmt == null) {
                if (!stopped) fail(FAIL_NETWORK);
                return;
            }
            try {
                codec = openCodec(fmt);
            } catch (Exception e) {
                Log.w(TAG, "Brak dekodera " + fmt.mime, e);
                fail(FAIL_DECODER);
                return;
            }

            @SuppressWarnings("deprecation") ByteBuffer[] ins = codec.getInputBuffers();
            @SuppressWarnings("deprecation") ByteBuffer[] outs = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat outFmt = null;
            long ptsUs = 0;
            byte[] pcm = new byte[8192];

            while (!stopped) {
                int ii = codec.dequeueInputBuffer(5000);
                if (ii >= 0) {
                    // weź następną ramkę dźwięku; po drodze obsłuż tytuły i wznowienia
                    FrameParser.Frame f;
                    while (true) {
                        f = nextFrame(at);
                        if (f == RESUMED) { announce = true; continue; }
                        if (f != null && f.title != null) { cb.onTitle(this, f.title); continue; }
                        break;
                    }
                    if (f == null) {
                        if (!stopped) fail(FAIL_NETWORK);
                        break;
                    }
                    if (f.newFormat != null) {
                        // nowy utwór w strumieniu Ogg z innymi ustawieniami – przestaw dekoder
                        releaseCodec(codec);
                        codec = null;
                        fmt = f.newFormat;
                        codec = openCodec(fmt);
                        //noinspection deprecation
                        ins = codec.getInputBuffers();
                        //noinspection deprecation
                        outs = codec.getOutputBuffers();
                        outFmt = null;
                        if (at != null && (at.getSampleRate() != fmt.sampleRate
                                || at.getChannelCount() != channelsOf(null, fmt))) {
                            releaseTrack(at);
                            at = null;
                            track = null;
                        }
                        continue;
                    }
                    ByteBuffer ib = ins[ii];
                    ib.clear();
                    int n = Math.min(f.data.length, ib.capacity());
                    ib.put(f.data, 0, n);
                    codec.queueInputBuffer(ii, 0, n, ptsUs, 0);
                    ptsUs += (long) (f.seconds * 1e6);
                }

                while (!stopped) {
                    int oi = codec.dequeueOutputBuffer(info, 0);
                    if (oi >= 0) {
                        if (info.size > 0) {
                            if (at == null) {
                                at = createTrack(outFmt != null ? outFmt : codec.getOutputFormat(), fmt);
                                track = at;
                                at.play();
                            }
                            if (pcm.length < info.size) pcm = new byte[info.size];
                            ByteBuffer ob = outs[oi];
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.get(pcm, 0, info.size);
                            ob.clear();
                            codec.releaseOutputBuffer(oi, false);
                            writeFully(at, pcm, info.size);
                            if (announce && !stopped) {
                                announce = false;
                                everPlayed = true;
                                cb.onPlaying(this);
                            }
                        } else {
                            codec.releaseOutputBuffer(oi, false);
                        }
                    } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        //noinspection deprecation
                        outs = codec.getOutputBuffers();
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFmt = codec.getOutputFormat();
                        // AAC+ zwykle zmienia tu częstotliwość (np. 22050 → 44100 Hz)
                        if (at != null && (at.getSampleRate() != rateOf(outFmt, fmt)
                                || at.getChannelCount() != channelsOf(outFmt, fmt))) {
                            releaseTrack(at);
                            at = null;
                            track = null;
                        }
                    } else {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Dekodowanie przerwane", e);
            if (!stopped) fail(everPlayed ? FAIL_NETWORK : FAIL_DECODER);
        } finally {
            track = null;
            if (at != null) releaseTrack(at);
            if (codec != null) releaseCodec(codec);
        }
    }

    private static MediaCodec openCodec(FrameParser.Format fmt) throws IOException {
        Log.i(TAG, "Format " + fmt.label + " " + fmt.sampleRate + " Hz, kanały: " + fmt.channels);
        MediaFormat mf = MediaFormat.createAudioFormat(fmt.mime, fmt.sampleRate, fmt.channels);
        if (fmt.csd != null) mf.setByteBuffer("csd-0", ByteBuffer.wrap(fmt.csd));
        if (fmt.csd1 != null) mf.setByteBuffer("csd-1", ByteBuffer.wrap(fmt.csd1));
        mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.mime);
        try {
            codec.configure(mf, null, null, 0);
            codec.start();
        } catch (RuntimeException e) {
            releaseCodec(codec);
            throw e;
        }
        return codec;
    }

    private static void releaseCodec(MediaCodec c) {
        try { c.stop(); } catch (Exception ignored) {}
        try { c.release(); } catch (Exception ignored) {}
    }

    /** Czeka, aż w buforze będzie zadana liczba sekund. Zwraca format albo null, gdy nic nie przyszło. */
    private FrameParser.Format waitForPrebuffer() throws InterruptedException {
        synchronized (lock) {
            buffering = true;
            while (!stopped && !netDone && (format == null || queued < target)) {
                lock.wait(250);
            }
            if (stopped || queue.isEmpty() || format == null) return null;
            buffering = false;
            return format;
        }
    }

    /** Znacznik: bufor był pusty, odtwarzanie wznowione po ponownym buforowaniu. */
    private static final FrameParser.Frame RESUMED = new FrameParser.Frame(new byte[0], 0);

    private FrameParser.Frame nextFrame(AudioTrack at) throws InterruptedException {
        synchronized (lock) {
            if (queue.isEmpty() && !netDone && !stopped) {
                // bufor się wyczerpał (słabe połączenie) – pauza i ponowne buforowanie
                buffering = true;
                if (at != null) try { at.pause(); } catch (Exception ignored) {}
                lock.notifyAll();
                while (!stopped && !netDone && queued < target) {
                    reportBufferingLocked();
                    lock.wait(250);
                }
                buffering = false;
                if (stopped) return null;
                if (at != null) try { at.play(); } catch (Exception ignored) {}
                if (!queue.isEmpty()) return RESUMED;
            }
            if (stopped || queue.isEmpty()) return null;
            FrameParser.Frame f = queue.poll();
            queued -= f.seconds;
            if (queued < 0) queued = 0;
            lock.notifyAll();
            return f;
        }
    }

    private void reportBufferingLocked() {
        int pct = (int) Math.min(99, queued * 100 / target);
        cb.onBuffering(this, pct, bytes / 1024);
    }

    private static int rateOf(MediaFormat f, FrameParser.Format def) {
        return f != null && f.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? f.getInteger(MediaFormat.KEY_SAMPLE_RATE) : def.sampleRate;
    }

    private static int channelsOf(MediaFormat f, FrameParser.Format def) {
        int ch = f != null && f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : def.channels;
        return ch == 1 ? 1 : 2;
    }

    @SuppressWarnings("deprecation")
    private AudioTrack createTrack(MediaFormat f, FrameParser.Format def) {
        int rate = rateOf(f, def);
        int ch = channelsOf(f, def);
        int mask = ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min * 2, rate * ch); // ok. pół sekundy
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, mask,
                AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
        float v = volume;
        t.setStereoVolume(v, v);
        return t;
    }

    private void writeFully(AudioTrack at, byte[] data, int len) throws IOException {
        int off = 0;
        while (off < len && !stopped) {
            int n = at.write(data, off, len - off);
            if (n < 0) throw new IOException("AudioTrack.write " + n);
            if (n == 0) {
                SystemClock.sleep(10);
                continue;
            }
            off += n;
        }
    }

    private static void releaseTrack(AudioTrack t) {
        try { t.pause(); } catch (Exception ignored) {}
        try { t.flush(); } catch (Exception ignored) {}
        try { t.release(); } catch (Exception ignored) {}
    }
}
