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
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Odtwarzanie w tle – radio gra także po wyjściu z aplikacji lub wygaszeniu ekranu.
 *
 * MP3 i AAC/AAC+ gra własny odtwarzacz (StreamPlayer) z buforem ustawianym w sekundach.
 * Inne formaty oraz HLS trafiają awaryjnie do wbudowanego MediaPlayera.
 */
public class RadioService extends Service implements StreamSource.TitleListener {
    private static final String TAG = "RadioService";
    private static final String ACTION_STOP = "pl.prosteradio.STOP";
    private static final String CHANNEL = "playback";
    private static final int NOTIF_ID = 1;
    /** Po tylu sekundach bez dźwięku MediaPlayer uznajemy za zawieszony. */
    private static final long MEDIAPLAYER_TIMEOUT_MS = 30000;

    public enum State { IDLE, CONNECTING, BUFFERING, PLAYING, ERROR }

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
    private IcyProxy proxy;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audio;

    private Station station;
    private State state = State.IDLE;
    private String title = "";
    private long phaseStart;
    private int bufferPercent = -1;
    private long bufferKb;
    private boolean reconnecting;
    private boolean errorOpus;        // błąd: format Opus nieobsługiwany na tym urządzeniu
    private int reconnects;
    private float volume = 1f;

    private boolean usingProxy;
    private boolean triedDirect;

    private boolean foreground;
    private State notifiedState;
    private String notifiedTitle;
    private Station notifiedStation;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // słuchawki odłączone – zatrzymaj, żeby nie zagrało nagle z głośnika
            if (state != State.IDLE) stop();
        }
    };
    private boolean noisyRegistered;

    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            if (state != State.IDLE) stop();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            setVolume(0.2f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            setVolume(1f);
        }
    };

    /** Co sekundę odświeża licznik „Łączenie… N s”. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (state == State.CONNECTING) {
                notifyListeners();
                main.postDelayed(this, 1000);
            }
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
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    str(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stop();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        releaseAll();
        proxy.shutdown();
        if (noisyRegistered) unregisterReceiver(noisy);
        releaseLocks();
        super.onDestroy();
    }

    /** Tekst w aktualnie wybranym języku (język można zmienić w trakcie działania). */
    private String str(int id, Object... args) {
        return AppSettings.wrap(getBaseContext()).getString(id, args);
    }

    // ---------------------------------------------------------------- API dla aktywności

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public Station getStation() { return station; }
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

    public void play(Station s) {
        station = s;
        title = "";
        reconnects = 0;
        errorOpus = false;
        Favorites.saveLast(this, s);
        if (s.isUnsupportedHere()) {
            // Opus na Androidzie < 5.0 – od razu komunikat, bez czekania
            releaseAll();
            errorOpus = true;
            showError();
            return;
        }
        startService(new Intent(this, RadioService.class));
        audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (!noisyRegistered) {
            registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
        reconnecting = false;
        if (s.isHls()) startMediaPlayer(false);
        else startNative();
    }

    public void stop() {
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

    private void setVolume(float v) {
        volume = v;
        if (stream != null) stream.setVolume(v);
        if (player != null) try { player.setVolume(v, v); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- własny odtwarzacz (MP3, AAC, AAC+)

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
                if (p != stream) return;
                stream = null;
                Log.w(TAG, "StreamPlayer failed: reason=" + reason + " wasPlaying=" + wasPlaying);
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
                    main.postDelayed(() -> {
                        if (station != null && state == State.CONNECTING && stream == null && player == null) {
                            startNative();
                        }
                    }, 1500);
                } else {
                    showError();
                }
            });
        }
    };

    // ---------------------------------------------------------------- wbudowany MediaPlayer (awaryjnie)

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
                } catch (Exception e) {
                    usingProxy = false;
                }
            }
            final String source = src;
            main.post(() -> {
                if (station != s || stream != null || state == State.IDLE) return;
                openPlayer(source);
            });
        }, "prepare").start();
    }

    private void openPlayer(String source) {
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setVolume(volume, volume);
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            p.start();
            reconnecting = false;
            reconnects = 0;
            setState(State.PLAYING);
        });
        mp.setOnInfoListener((p, what, extra) -> {
            if (p != player) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                bufferPercent = -1;
                setState(State.BUFFERING);
            } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                setState(State.PLAYING);
            }
            return false;
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (p != player) return true;
            Log.w(TAG, "MediaPlayer error " + what + "/" + extra);
            onMediaPlayerProblem();
            return true;
        });
        mp.setOnCompletionListener(p -> {
            if (p == player) onMediaPlayerProblem();
        });
        // jeśli w ciągu 30 s nic nie zagra – nie czekaj w nieskończoność
        main.postDelayed(() -> {
            if (player == mp && state != State.PLAYING && state != State.IDLE) {
                Log.w(TAG, "MediaPlayer timeout");
                onMediaPlayerProblem();
            }
        }, MEDIAPLAYER_TIMEOUT_MS);
        try {
            mp.setDataSource(source);
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Cannot open " + source, e);
            onMediaPlayerProblem();
        }
    }

    private void onMediaPlayerProblem() {
        if (station == null) return;
        boolean wasPlaying = state == State.PLAYING;
        if (wasPlaying && reconnects++ < 3) {
            reconnecting = true;
            final boolean viaProxy = usingProxy;
            releasePlayer();
            setState(State.CONNECTING);
            main.postDelayed(() -> {
                if (station != null && state == State.CONNECTING && player == null && stream == null) {
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

    // ---------------------------------------------------------------- wspólne

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
    }

    private void releasePlayer() {
        if (player != null) {
            MediaPlayer p = player;
            player = null;
            try { p.reset(); } catch (Exception ignored) {}
            try { p.release(); } catch (Exception ignored) {}
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
            if (player != null) setTitle(t);
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
            main.removeCallbacks(ticker);
            main.postDelayed(ticker, 1000);
        }
        state = s;
        notifyListeners();
        if (s == State.ERROR) {
            // powiadomienie o błędzie tylko, jeśli wcześniej coś już było widać
            if (foreground) {
                stopForeground(false);
                foreground = false;
                updateNotification();
            }
        } else if (s != State.IDLE) {
            updateNotification();
        }
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onPlayerChanged();
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
                return title.length() > 0 ? title : str(R.string.status_playing);
            case ERROR:
                return str(errorOpus ? R.string.status_error_opus : R.string.status_error);
            default:
                return "";
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
        if (station == null) return;
        // przy buforowaniu procenty zmieniają się często – powiadomienie odświeżamy tylko przy zmianie stanu
        if (state == notifiedState && title.equals(notifiedTitle) && station == notifiedStation
                && foreground == isActive()) return;
        notifiedState = state;
        notifiedTitle = title;
        notifiedStation = station;

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, piFlags);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, RadioService.class).setAction(ACTION_STOP), piFlags);

        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(station.name)
                .setContentText(notificationText())
                .setContentIntent(openPi)
                .setOngoing(isActive())
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, str(R.string.stop), stopPi);
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
