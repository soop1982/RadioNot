package pl.prosteradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Odtwarzanie w tle – radio i podcasty grają także po wyjściu z aplikacji
 * lub wygaszeniu ekranu. Zamknięcie aplikacji z listy ostatnich zatrzymuje dźwięk.
 *
 * Radio: MP3, AAC/AAC+ i Ogg Vorbis gra własny odtwarzacz (StreamPlayer);
 *        inne formaty oraz HLS – awaryjnie wbudowany MediaPlayer.
 * Podcasty: MediaPlayer przez lokalnego pośrednika (MediaProxy) – z pauzą,
 *        przewijaniem i zapamiętywaniem miejsca.
 */
public class RadioService extends Service implements StreamSource.TitleListener {
    private static final String ACTION_STOP = "pl.prosteradio.STOP";
    private static final String ACTION_TOGGLE = "pl.prosteradio.TOGGLE";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;
    /** Po tylu ms bez dźwięku MediaPlayer uznajemy za zawieszony. */
    private static final long RADIO_TIMEOUT_MS = 30000;
    private static final long EPISODE_TIMEOUT_MS = 45000;

    public enum State { IDLE, CONNECTING, BUFFERING, PLAYING, PAUSED, ERROR }

    public interface Listener {
        void onPlayerChanged();
    }

    public class LocalBinder extends Binder {
        public RadioService get() { return RadioService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    private StreamPlayer stream;
    private MediaPlayer player;
    private boolean prepared;               // player gotowy (można pytać o pozycję, pauzować)
    private IcyProxy proxy;
    private MediaProxy mediaProxy;
    private PodcastStore podcasts;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;

    // to, co gra: albo stacja, albo odcinek
    private Station station;
    private Episode episode;

    private State state = State.IDLE;
    private String title = "";
    private long phaseStart;
    private int bufferPercent = -1;
    private long bufferKb;
    private boolean reconnecting;
    private boolean errorOpus;              // błąd: format Opus nieobsługiwany na tym urządzeniu
    private boolean episodeFinished;
    private int reconnects;
    private float volume = 1f;
    private boolean usingProxy;
    private boolean triedDirect;

    // podcast: pozycja i długość
    private long lastPos;
    private long durationMs;
    private int ticks;

    private boolean foreground;
    private State notifiedState;
    private String notifiedTitle;
    private Object notifiedItem;

    private Context langCtx;
    private String langCode;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // słuchawki odłączone – przerwij, żeby nie zagrało nagle z głośnika
            if (isPodcast()) pause();
            else if (state != State.IDLE) stop();
        }
    };
    private boolean noisyRegistered;

    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            if (isPodcast()) pause();
            else if (state != State.IDLE) stop();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            setVolume(0.2f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            setVolume(1f);
        }
    };

    /** Co sekundę: licznik „Łączenie… N s” oraz pozycja odcinka podcastu. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            boolean again = false;
            if (state == State.CONNECTING) {
                notifyListeners();
                again = true;
            } else if (isPodcast() && (state == State.PLAYING || state == State.BUFFERING)) {
                updatePosition();
                if (++ticks % 10 == 0) savePosition();
                notifyListeners();
                again = true;
            }
            if (again) main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppSettings.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "ProsteRadio");
            wifiLock.setReferenceCounted(false);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProsteRadio:play");
            wakeLock.setReferenceCounted(false);
        }
        proxy = new IcyProxy(this);
        mediaProxy = new MediaProxy();
        podcasts = new PodcastStore(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    str(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (ACTION_STOP.equals(intent.getAction())) stop();
            else if (ACTION_TOGGLE.equals(intent.getAction())) togglePlayPause();
        }
        return START_NOT_STICKY;
    }

    /** Aplikacja zamknięta z listy ostatnich (przesunięcie palcem) – koniec grania. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        CrashLog.event("task removed – stopping");
        stop();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        savePosition();
        releaseAll();
        proxy.shutdown();
        mediaProxy.shutdown();
        if (noisyRegistered) {
            try { unregisterReceiver(noisy); } catch (Throwable ignored) {}
            noisyRegistered = false;
        }
        releaseLocks();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Tekst w aktualnie wybranym języku (język można zmienić w trakcie działania). */
    private String str(int id, Object... args) {
        String l = AppSettings.language(this);
        if (langCtx == null || !l.equals(langCode)) {
            langCtx = AppSettings.wrap(getBaseContext());
            langCode = l;
        }
        return langCtx.getString(id, args);
    }

    // ---------------------------------------------------------------- API dla aktywności

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public Station getStation() { return station; }
    public Episode getEpisode() { return episode; }
    public boolean isPodcast() { return episode != null; }
    public State getState() { return state; }

    public boolean isActive() {
        return state == State.CONNECTING || state == State.BUFFERING || state == State.PLAYING;
    }

    /** Postęp dla paska: -1 = nieokreślony (kręci się), 0–100 = procent bufora. */
    public int getProgress() {
        if (state == State.CONNECTING) return -1;
        if (state == State.BUFFERING) return bufferPercent;
        return 100;
    }

    /** Pozycja odcinka w ms. */
    public long getPosition() {
        if (episode == null) return 0;
        if (state == State.PLAYING) updatePosition();
        return lastPos;
    }

    /** Długość odcinka w ms (0 = nieznana). */
    public long getDuration() {
        if (episode == null) return 0;
        if (durationMs > 0) return durationMs;
        return episode.durationSec * 1000L;
    }

    // ---------------------------------------------------------------- radio

    public void play(Station s) {
        savePosition();
        station = s;
        episode = null;
        title = "";
        reconnects = 0;
        errorOpus = false;
        episodeFinished = false;
        Favorites.saveLast(this, s);
        CrashLog.event("play station: " + s.name + " | " + s.url + " | " + s.codec);
        if (s.isUnsupportedHere()) {
            // Opus na Androidzie < 5.0 – od razu komunikat, bez czekania
            releaseAll();
            errorOpus = true;
            showError();
            return;
        }
        beginPlayback();
        reconnecting = false;
        if (s.isHls()) startMediaPlayer(false);
        else startNative();
    }

    public void stop() {
        savePosition();
        releaseAll();
        title = "";
        reconnecting = false;
        setState(State.IDLE);
        audio.abandonAudioFocus(focus);
        releaseLocks();
        stopForeground(true);
        foreground = false;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        stopSelf();
    }

    private void beginPlayback() {
        startService(new Intent(this, RadioService.class));
        audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (!noisyRegistered) {
            registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
    }

    private void setVolume(float v) {
        volume = v;
        if (stream != null) stream.setVolume(v);
        if (player != null) try { player.setVolume(v, v); } catch (Throwable ignored) {}
    }

    private void startNative() {
        releaseAll();
        acquireLocks();
        phaseStart = SystemClock.uptimeMillis();
        setState(State.CONNECTING);
        StreamPlayer sp = new StreamPlayer(station.url, AppSettings.bufferSeconds(this), nativeCallback);
        stream = sp;
        sp.setVolume(volume);
        sp.start();
    }

    private final StreamPlayer.Callback nativeCallback = new StreamPlayer.Callback() {
        @Override
        public void onConnecting(StreamPlayer p) {
            // stan CONNECTING ustawiony już przy starcie
        }

        @Override
        public void onBuffering(final StreamPlayer p, final int percent, final long kb) {
            main.post(() -> {
                if (p != stream) return;
                bufferPercent = percent;
                bufferKb = kb;
                setState(State.BUFFERING);
            });
        }

        @Override
        public void onPlaying(final StreamPlayer p) {
            main.post(() -> {
                if (p != stream) return;
                reconnecting = false;
                reconnects = 0;
                setState(State.PLAYING);
            });
        }

        @Override
        public void onTitle(final StreamPlayer p, final String t) {
            main.post(() -> {
                if (p == stream) setTitle(t);
            });
        }

        @Override
        public void onFailed(final StreamPlayer p, final int reason, final boolean wasPlaying) {
            main.post(() -> {
                if (p != stream || station == null) return;
                stream = null;
                CrashLog.event("stream failed: reason=" + reason + " wasPlaying=" + wasPlaying);
                if (reason == StreamPlayer.FAIL_OPUS) {
                    station.markOpus();
                    if (Build.VERSION.SDK_INT >= 21) {
                        startMediaPlayer(true); // nowsze Androidy mają dekoder Opus
                    } else {
                        errorOpus = true;
                        showError();
                    }
                } else if (reason != StreamPlayer.FAIL_NETWORK) {
                    // inny format lub brak dekodera – spróbuj wbudowanym odtwarzaczem
                    startMediaPlayer(true);
                } else if (wasPlaying && reconnects++ < 3) {
                    // grało i połączenie się zerwało – połącz ponownie
                    reconnecting = true;
                    setState(State.CONNECTING);
                    final Station s = station;
                    main.postDelayed(() -> {
                        if (station == s && state == State.CONNECTING && stream == null && player == null) {
                            startNative();
                        }
                    }, 1500);
                } else {
                    showError();
                }
            });
        }
    };

    private void startMediaPlayer(final boolean viaProxy) {
        releaseAll();
        acquireLocks();
        triedDirect = !viaProxy;
        usingProxy = viaProxy;
        bufferPercent = -1;
        phaseStart = SystemClock.uptimeMillis();
        setState(State.CONNECTING);
        final Station s = station;
        new Thread(() -> {
            String src = s.url;
            if (viaProxy) {
                try {
                    proxy.start();
                    src = proxy.urlFor(s.url);
                } catch (Throwable e) {
                    usingProxy = false;
                }
            }
            final String source = src;
            main.post(() -> {
                if (station != s || stream != null || player != null || state == State.IDLE) return;
                openRadioPlayer(source);
            });
        }, "prepare").start();
    }

    private void openRadioPlayer(String source) {
        final MediaPlayer mp = newPlayer();
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            prepared = true;
            p.start();
            reconnecting = false;
            reconnects = 0;
            setState(State.PLAYING);
        });
        mp.setOnCompletionListener(p -> {
            if (p == player) onRadioPlayerProblem();
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (p != player) return true;
            CrashLog.event("MediaPlayer error " + what + "/" + extra);
            onRadioPlayerProblem();
            return true;
        });
        // jeśli w ciągu 30 s nic nie zagra – nie czekaj w nieskończoność
        main.postDelayed(() -> {
            if (player == mp && state != State.PLAYING && state != State.IDLE) {
                CrashLog.event("MediaPlayer timeout");
                onRadioPlayerProblem();
            }
        }, RADIO_TIMEOUT_MS);
        try {
            mp.setDataSource(source);
            mp.prepareAsync();
        } catch (Throwable e) {
            CrashLog.event("cannot open " + source + ": " + e);
            onRadioPlayerProblem();
        }
    }

    private void onRadioPlayerProblem() {
        if (station == null) return;
        boolean wasPlaying = state == State.PLAYING;
        if (wasPlaying && reconnects++ < 3) {
            reconnecting = true;
            final boolean viaProxy = usingProxy;
            final Station s = station;
            releasePlayer();
            setState(State.CONNECTING);
            main.postDelayed(() -> {
                if (station == s && state == State.CONNECTING && player == null && stream == null) {
                    startMediaPlayer(viaProxy);
                }
            }, 1500);
        } else if (!wasPlaying && usingProxy && !triedDirect) {
            // przez pośrednika się nie udało – spróbuj bezpośrednio
            startMediaPlayer(false);
        } else {
            showError();
        }
    }

    // ---------------------------------------------------------------- podcasty

    public void playEpisode(Episode e) {
        savePosition();
        boolean same = episode != null && episode.key().equals(e.key());
        if (same && (state == State.PLAYING || state == State.BUFFERING || state == State.CONNECTING)) return;
        if (same && state == State.PAUSED) {
            resume();
            return;
        }
        episode = e;
        station = null;
        title = "";
        reconnects = 0;
        errorOpus = false;
        episodeFinished = false;
        durationMs = 0;
        PodcastStore.saveLast(this, e);
        Favorites.setLastKind(this, "episode");
        // wysłuchany do końca – zacznij od początku
        if (podcasts.isDone(e)) {
            podcasts.clearDone(e);
            podcasts.savePosition(e, 0);
        }
        lastPos = podcasts.position(e);
        CrashLog.event("play episode: " + e.title + " | " + e.audioUrl + " @" + lastPos);
        beginPlayback();
        reconnecting = false;
        startEpisode(lastPos);
    }

    public void pause() {
        if (episode == null) return;
        if (player != null && prepared && state == State.PLAYING) {
            updatePosition();
            try { player.pause(); } catch (Throwable ignored) {}
        } else if (state != State.PAUSED) {
            // jeszcze się łączy – po prostu przerwij, miejsce zostaje zapamiętane
            releaseAll();
        }
        savePosition();
        releaseLocks();
        setState(State.PAUSED);
    }

    public void resume() {
        if (episode != null) {
            if (player != null && prepared && state == State.PAUSED) {
                beginPlayback();
                acquireLocks();
                try {
                    player.start();
                    setState(State.PLAYING);
                    startTicker();
                    return;
                } catch (Throwable ignored) {}
            }
            Episode e = episode;
            beginPlayback();
            reconnects = 0;
            episodeFinished = false;
            if (podcasts.isDone(e)) {
                podcasts.clearDone(e);
                lastPos = 0;
            }
            startEpisode(lastPos);
        } else if (station != null) {
            play(station);
        }
    }

    public void togglePlayPause() {
        if (episode != null) {
            if (state == State.PLAYING || state == State.BUFFERING || state == State.CONNECTING) pause();
            else resume();
        } else if (station != null) {
            if (isActive()) stop();
            else play(station);
        }
    }

    public void seekTo(long ms) {
        if (episode == null) return;
        long d = getDuration();
        if (d > 0) ms = Math.min(ms, Math.max(0, d - 1000));
        ms = Math.max(0, ms);
        lastPos = ms;
        episodeFinished = false;
        if (player != null && prepared) {
            try { player.seekTo((int) ms); } catch (Throwable ignored) {}
        }
        savePosition();
        notifyListeners();
    }

    public void seekBy(long deltaMs) {
        seekTo(getPosition() + deltaMs);
    }

    private void startEpisode(final long startMs) {
        releaseAll();
        acquireLocks();
        phaseStart = SystemClock.uptimeMillis();
        setState(State.CONNECTING);
        final Episode e = episode;
        new Thread(() -> {
            String src = e.audioUrl;
            try {
                mediaProxy.start();
                src = mediaProxy.urlFor(e.audioUrl);
            } catch (Throwable ignored) {}
            final String source = src;
            main.post(() -> {
                if (episode != e || player != null || state != State.CONNECTING) return;
                openEpisodePlayer(source, startMs);
            });
        }, "prepare-episode").start();
    }

    private void openEpisodePlayer(String source, final long startMs) {
        final MediaPlayer mp = newPlayer();
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            prepared = true;
            try {
                int d = p.getDuration();
                if (d > 0) durationMs = d;
            } catch (Throwable ignored) {}
            if (startMs > 1000) {
                try { p.seekTo((int) startMs); } catch (Throwable ignored) {}
            }
            p.start();
            reconnecting = false;
            setState(State.PLAYING);
            startTicker();
        });
        mp.setOnCompletionListener(p -> {
            if (p != player) return;
            updatePosition();
            long d = getDuration();
            if (d <= 0 || lastPos >= d - 15000) {
                // koniec odcinka
                podcasts.markDone(episode);
                lastPos = 0;
                episodeFinished = true;
                releaseAll();
                releaseLocks();
                setState(State.IDLE);
                stopForeground(true);
                foreground = false;
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
            } else {
                onEpisodeProblem(); // urwane połączenie w środku odcinka
            }
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (p != player) return true;
            CrashLog.event("episode MediaPlayer error " + what + "/" + extra);
            onEpisodeProblem();
            return true;
        });
        main.postDelayed(() -> {
            if (player == mp && !prepared && state != State.IDLE && state != State.PAUSED) {
                CrashLog.event("episode timeout");
                onEpisodeProblem();
            }
        }, EPISODE_TIMEOUT_MS);
        try {
            mp.setDataSource(source);
            mp.prepareAsync();
        } catch (Throwable e) {
            CrashLog.event("cannot open episode: " + e);
            onEpisodeProblem();
        }
    }

    private void onEpisodeProblem() {
        if (episode == null) return;
        boolean wasPlaying = prepared && (state == State.PLAYING || state == State.BUFFERING);
        if (wasPlaying) updatePosition();
        savePosition();
        if (wasPlaying && reconnects++ < 3) {
            reconnecting = true;
            final Episode e = episode;
            releaseAll();
            setState(State.CONNECTING);
            main.postDelayed(() -> {
                if (episode == e && state == State.CONNECTING && player == null) startEpisode(lastPos);
            }, 1500);
        } else {
            showError();
        }
    }

    private void updatePosition() {
        if (player != null && prepared) {
            try {
                int p = player.getCurrentPosition();
                if (p >= 0) lastPos = p;
            } catch (Throwable ignored) {}
        }
    }

    private void savePosition() {
        if (episode != null && !episodeFinished) podcasts.savePosition(episode, lastPos);
    }

    private void startTicker() {
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 1000);
    }

    // ---------------------------------------------------------------- wspólne

    private MediaPlayer newPlayer() {
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        prepared = false;
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setVolume(volume, volume);
        mp.setOnInfoListener((p, what, extra) -> {
            if (p != player) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                bufferPercent = -1;
                setState(State.BUFFERING);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                if (state == State.BUFFERING) setState(State.PLAYING);
            }
            return false;
        });
        return mp;
    }

    private void showError() {
        releaseAll();
        reconnecting = false;
        releaseLocks();
        setState(State.ERROR);
    }

    private void releaseAll() {
        if (stream != null) {
            StreamPlayer s = stream;
            stream = null;
            s.stop();
        }
        releasePlayer();
        proxy.stopStream();
        mediaProxy.stop();
    }

    /**
     * Zwalnia MediaPlayer w osobnym wątku – reset() potrafi chwilę trwać,
     * gdy odtwarzacz jeszcze się łączy, a w głównym wątku zamroziłoby to ekran.
     */
    private void releasePlayer() {
        if (player != null) {
            final MediaPlayer p = player;
            player = null;
            prepared = false;
            new Thread(() -> {
                try { p.reset(); } catch (Throwable ignored) {}
                try { p.release(); } catch (Throwable ignored) {}
            }, "mp-release").start();
        }
    }

    private void acquireLocks() {
        if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    /** Tytuł z MediaPlayera (przez pośrednika) – wywoływane z innego wątku. */
    @Override
    public void onTitle(final String t) {
        main.post(() -> {
            if (player != null && station != null) setTitle(t);
        });
    }

    private void setTitle(String t) {
        if (t.equals(title)) return;
        title = t;
        notifyListeners();
        updateNotification();
    }

    private void setState(State s) {
        if (s == State.CONNECTING && state != State.CONNECTING) {
            phaseStart = SystemClock.uptimeMillis();
            startTicker();
        }
        state = s;
        notifyListeners();
        if (s == State.ERROR || s == State.PAUSED) {
            // powiadomienie zostaje (z przyciskiem), ale można je odsunąć
            if (foreground) {
                stopForeground(false);
                foreground = false;
            }
            if (s == State.PAUSED || notifiedState != null) updateNotification();
        } else if (s != State.IDLE) {
            updateNotification();
        }
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onPlayerChanged();
            } catch (Throwable e) {
                CrashLog.event("listener error: " + e);
            }
        }
    }

    public String statusText() {
        switch (state) {
            case CONNECTING: {
                int secs = (int) ((SystemClock.uptimeMillis() - phaseStart) / 1000);
                return str(reconnecting ? R.string.status_reconnecting : R.string.status_connecting, secs);
            }
            case BUFFERING:
                return bufferPercent >= 0
                        ? str(R.string.status_buffering_progress, bufferPercent, (int) bufferKb)
                        : str(R.string.status_buffering);
            case PLAYING:
                if (episode != null) return episode.podcastName;
                return title.length() > 0 ? title : str(R.string.status_playing);
            case PAUSED:
                return str(R.string.status_paused);
            case ERROR:
                return str(errorOpus ? R.string.status_error_opus : R.string.status_error);
            default:
                return episodeFinished ? str(R.string.status_finished) : "";
        }
    }

    /** Krótszy opis do powiadomienia (bez licznika sekund, żeby nie odświeżać co chwilę). */
    private String notificationText() {
        switch (state) {
            case CONNECTING:
                return str(reconnecting ? R.string.status_reconnecting : R.string.status_connecting, 0)
                        .replaceAll("\\s*\\d+\\s*\\S*$", "");
            case BUFFERING:
                return str(R.string.status_buffering);
            default:
                return statusText();
        }
    }

    @SuppressWarnings("deprecation")
    private void updateNotification() {
        Object item = episode != null ? episode : station;
        if (item == null) return;
        // przy buforowaniu procenty zmieniają się często – powiadomienie odświeżamy tylko przy zmianie stanu
        if (state == notifiedState && title.equals(notifiedTitle) && item == notifiedItem
                && foreground == isActive()) return;
        notifiedState = state;
        notifiedTitle = title;
        notifiedItem = item;

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, piFlags);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, RadioService.class).setAction(ACTION_STOP), piFlags);
        PendingIntent togglePi = PendingIntent.getService(this, 2,
                new Intent(this, RadioService.class).setAction(ACTION_TOGGLE), piFlags);

        String heading = episode != null ? episode.title : station.name;
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(heading)
                .setContentText(notificationText())
                .setContentIntent(openPi)
                .setOngoing(isActive())
                .setOnlyAlertOnce(true);
        if (episode != null) {
            if (state == State.PAUSED || state == State.ERROR) {
                b.addAction(android.R.drawable.ic_media_play, str(R.string.play), togglePi);
            } else {
                b.addAction(android.R.drawable.ic_media_pause, str(R.string.pause), togglePi);
            }
            b.setDeleteIntent(stopPi);
        } else {
            b.addAction(android.R.drawable.ic_media_pause, str(R.string.stop), stopPi);
        }
        Notification n = b.build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (isActive()) {
            if (!foreground) {
                startForeground(NOTIF_ID, n);
                foreground = true;
            } else {
                nm.notify(NOTIF_ID, n);
            }
        } else {
            nm.notify(NOTIF_ID, n);
        }
    }
}
