package pl.prosteradio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements RadioService.Listener {
    private enum Tab { POPULAR, FAVORITES, PODCASTS }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // lista i nagłówek
    private View searchRow, podcastRow, addStation;
    private EditText search;
    private ImageButton clear, podcastSub;
    private ImageView podcastArt;
    private TextView podcastTitle, podcastAuthor;
    private TextView tabPopular, tabFavorites, tabPodcasts;
    private ListView list;
    private LinearLayout emptyBox;
    private ProgressBar progress;
    private TextView emptyText;

    // mały odtwarzacz na dole
    private ImageView playerLogo;
    private TextView playerName, playerInfo;
    private ImageButton playerBtn;
    private ProgressBar bufferProgress;

    // odtwarzacz pełnoekranowy
    private View fullPlayer, fullSeekBox;
    private LinearLayout fullBody, fullInfo;
    private ImageView fullLogo;
    private ImageButton fullStar, fullPlay;
    private TextView fullName, fullSub, fullStatus, fullPos, fullDur;
    private ProgressBar fullBuffer;
    private SeekBar fullSeek;
    private Button fullBack, fullFwd;
    private boolean seeking;

    private RadioApi api;
    private PodcastApi podcastApi;
    private Favorites favorites;
    private PodcastStore store;
    private ImageLoader images, bigImages;
    private StationAdapter stationAdapter;
    private PodcastAdapter podcastAdapter;
    private EpisodeAdapter episodeAdapter;

    private Tab tab = Tab.POPULAR;
    private List<Station> popularCache;
    private List<Podcast> topCache;
    private String topCountry;
    private Podcast openPodcast;
    private List<Episode> episodesCache;
    private String episodesKey;
    private int requestId;
    private boolean lastLoadFailed;
    private String lastError = "";

    private Station lastStation;
    private Episode lastEpisode;
    private String lastKind;
    private RadioService service;
    private String languageAtStart, countryAtStart;

    private final Runnable searchLater = this::refresh;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RadioService.LocalBinder) binder).get();
            service.addListener(MainActivity.this);
            onPlayerChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppSettings.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        languageAtStart = AppSettings.language(this);
        countryAtStart = AppSettings.country(this);
        setContentView(R.layout.activity_main);

        searchRow = findViewById(R.id.search_row);
        podcastRow = findViewById(R.id.podcast_row);
        addStation = findViewById(R.id.add_station);
        search = findViewById(R.id.search);
        clear = findViewById(R.id.clear);
        podcastSub = findViewById(R.id.podcast_sub);
        podcastArt = findViewById(R.id.podcast_art);
        podcastTitle = findViewById(R.id.podcast_title);
        podcastAuthor = findViewById(R.id.podcast_author);
        tabPopular = findViewById(R.id.tab_popular);
        tabFavorites = findViewById(R.id.tab_favorites);
        tabPodcasts = findViewById(R.id.tab_podcasts);
        list = findViewById(R.id.list);
        emptyBox = findViewById(R.id.empty_box);
        progress = findViewById(R.id.progress);
        emptyText = findViewById(R.id.empty_text);
        playerLogo = findViewById(R.id.player_logo);
        playerName = findViewById(R.id.player_name);
        playerInfo = findViewById(R.id.player_info);
        playerBtn = findViewById(R.id.player_btn);
        bufferProgress = findViewById(R.id.buffer_progress);

        fullPlayer = findViewById(R.id.full_player);
        fullBody = findViewById(R.id.full_body);
        fullInfo = findViewById(R.id.full_info);
        fullLogo = findViewById(R.id.full_logo);
        fullStar = findViewById(R.id.full_star);
        fullPlay = findViewById(R.id.full_play);
        fullName = findViewById(R.id.full_name);
        fullSub = findViewById(R.id.full_sub);
        fullStatus = findViewById(R.id.full_status);
        fullBuffer = findViewById(R.id.full_buffer);
        fullSeekBox = findViewById(R.id.full_seek_box);
        fullSeek = findViewById(R.id.full_seek);
        fullPos = findViewById(R.id.full_pos);
        fullDur = findViewById(R.id.full_dur);
        fullBack = findViewById(R.id.full_back);
        fullFwd = findViewById(R.id.full_fwd);

        api = new RadioApi(this);
        podcastApi = new PodcastApi(this);
        favorites = new Favorites(this);
        store = new PodcastStore(this);
        float density = getResources().getDisplayMetrics().density;
        images = new ImageLoader(this, (int) (64 * density));
        bigImages = new ImageLoader(this, (int) (360 * density), "artwork", 16);
        stationAdapter = new StationAdapter(getLayoutInflater(), images, favorites, this::toggleFavorite);
        podcastAdapter = new PodcastAdapter(getLayoutInflater(), images, store, this::toggleSubscription);
        episodeAdapter = new EpisodeAdapter(getLayoutInflater(), store,
                AppSettings.locale(AppSettings.language(this)));

        list.setOnItemClickListener((parent, view, position, id) -> onListClick(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (list.getAdapter() != stationAdapter) return false;
            Station st = stationAdapter.getItem(position);
            if (!st.custom) return false;
            editStation(st);
            return true;
        });
        tabPopular.setOnClickListener(v -> selectTab(Tab.POPULAR));
        tabFavorites.setOnClickListener(v -> selectTab(Tab.FAVORITES));
        tabPodcasts.setOnClickListener(v -> selectTab(Tab.PODCASTS));
        emptyBox.setOnClickListener(v -> { if (lastLoadFailed) refresh(); });
        clear.setOnClickListener(v -> search.setText(""));
        playerBtn.setOnClickListener(v -> togglePlay());
        findViewById(R.id.player_bar).setOnClickListener(v -> showFull(true));
        findViewById(R.id.settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        addStation.setOnClickListener(v -> editStation(null));
        findViewById(R.id.podcast_back).setOnClickListener(v -> closePodcast());
        podcastSub.setOnClickListener(v -> { if (openPodcast != null) toggleSubscription(openPodcast); });

        setupFullPlayer();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable e) {
                clear.setVisibility(e.length() > 0 ? View.VISIBLE : View.GONE);
                main.removeCallbacks(searchLater);
                // ulubione filtrujemy od razu, wyszukiwanie w sieci – po chwili przerwy w pisaniu
                main.postDelayed(searchLater, tab == Tab.FAVORITES ? 0 : 700);
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                main.removeCallbacks(searchLater);
                refresh();
                hideKeyboard();
                return true;
            }
            return false;
        });

        lastStation = Favorites.loadLast(this);
        lastEpisode = PodcastStore.loadLast(this);
        lastKind = Favorites.lastKind(this);
        if (favorites.all().size() > 0) tab = Tab.FAVORITES;
        selectTab(tab);
        onPlayerChanged();

        bindService(new Intent(this, RadioService.class), conn, Context.BIND_AUTO_CREATE);
        showCrashReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // po zmianie języka w ustawieniach przeładuj ekran
        if (!AppSettings.language(this).equals(languageAtStart)) {
            recreate();
            return;
        }
        // po zmianie kraju wczytaj listy od nowa
        String cc = AppSettings.country(this);
        if (!cc.equals(countryAtStart)) {
            countryAtStart = cc;
            popularCache = null;
            topCache = null;
            refresh();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // obrót ekranu nie może przywrócić języka systemu
        Configuration c = new Configuration(getResources().getConfiguration());
        c.locale = AppSettings.locale(AppSettings.language(this));
        getResources().updateConfiguration(c, getResources().getDisplayMetrics());
        layoutFull();
    }

    @Override
    public void onBackPressed() {
        if (fullPlayer.getVisibility() == View.VISIBLE) {
            showFull(false);
        } else if (tab == Tab.PODCASTS && openPodcast != null) {
            closePodcast();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (service != null) service.removeListener(this);
        try { unbindService(conn); } catch (Throwable ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- zadania w tle

    private interface Job<T> { T run() throws Exception; }
    private interface Done<T> { void done(T result); }   // result == null → błąd

    /** Wykonuje zadanie w tle; wynik trafia na ekran tylko, jeśli to wciąż aktualne zapytanie. */
    private <T> void async(final int id, final Job<T> job, final Done<T> done) {
        if (worker.isShutdown()) return;
        try {
            worker.execute(() -> {
                T r = null;
                String err = "";
                try {
                    r = job.run();
                } catch (Throwable e) {
                    CrashLog.event("load failed: " + e);
                    err = PodcastApi.short_(e);
                }
                final T result = r;
                final String error = err;
                main.post(() -> {
                    if (id != requestId || isFinishing()) return;
                    lastError = error;
                    done.done(result);
                });
            });
        } catch (Throwable ignored) {
            // wątek w tle już zamknięty (ekran niszczony) – nic nie rób
        }
    }

    // ---------------------------------------------------------------- zakładki i lista

    private void selectTab(Tab t) {
        boolean switchedKind = (t == Tab.PODCASTS) != (tab == Tab.PODCASTS);
        tab = t;
        int on = getResources().getColor(R.color.accent), off = getResources().getColor(R.color.text_dim);
        tabPopular.setSelected(t == Tab.POPULAR);
        tabFavorites.setSelected(t == Tab.FAVORITES);
        tabPodcasts.setSelected(t == Tab.PODCASTS);
        tabPopular.setTextColor(t == Tab.POPULAR ? on : off);
        tabFavorites.setTextColor(t == Tab.FAVORITES ? on : off);
        tabPodcasts.setTextColor(t == Tab.PODCASTS ? on : off);
        search.setHint(t == Tab.PODCASTS ? R.string.search_hint_podcasts : R.string.search_hint);
        if (switchedKind && search.getText().length() > 0) {
            // zapytanie o stację nie ma sensu jako zapytanie o podcast (i odwrotnie)
            search.setText("");
            main.removeCallbacks(searchLater);
        }
        updateHeader();
        refresh();
    }

    private void updateHeader() {
        boolean episodes = tab == Tab.PODCASTS && openPodcast != null;
        searchRow.setVisibility(episodes ? View.GONE : View.VISIBLE);
        podcastRow.setVisibility(episodes ? View.VISIBLE : View.GONE);
        addStation.setVisibility(tab == Tab.PODCASTS ? View.GONE : View.VISIBLE);
        if (episodes) {
            podcastTitle.setText(openPodcast.name);
            podcastAuthor.setText(openPodcast.author);
            podcastAuthor.setVisibility(openPodcast.author.length() > 0 ? View.VISIBLE : View.GONE);
            images.load(openPodcast.artwork, openPodcast.name, podcastArt);
            podcastSub.setImageResource(store.isSubscribed(openPodcast)
                    ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off);
        }
    }

    private void setAdapter(ListAdapter a) {
        if (list.getAdapter() != a) list.setAdapter(a);
    }

    private void refresh() {
        final String q = search.getText().toString().trim();
        final int id = ++requestId;
        lastLoadFailed = false;

        if (tab == Tab.PODCASTS) {
            if (openPodcast != null) showEpisodes(id);
            else showPodcasts(id, q);
            return;
        }
        setAdapter(stationAdapter);

        if (tab == Tab.FAVORITES) {
            List<Station> out = new ArrayList<>();
            String ql = q.toLowerCase(Locale.getDefault());
            for (Station s : favorites.all()) {
                if (ql.length() == 0 || s.name.toLowerCase(Locale.getDefault()).contains(ql)) out.add(s);
            }
            stationAdapter.setItems(out);
            afterLoad(out.size(), getString(q.length() > 0 ? R.string.no_results : R.string.no_favorites));
            return;
        }

        if (q.length() == 0 && popularCache != null) {
            stationAdapter.setItems(popularCache);
            afterLoad(popularCache.size(), getString(R.string.no_results));
            return;
        }

        stationAdapter.setItems(null);
        showMessage(getString(R.string.loading), true);
        async(id, () -> q.length() == 0 ? api.popular() : api.search(q), r -> {
            if (r == null) {
                lastLoadFailed = true;
                showMessage(withDetail(R.string.network_error), false);
            } else {
                if (q.length() == 0) popularCache = r;
                stationAdapter.setItems(r);
                afterLoad(r.size(), getString(R.string.no_results));
            }
        });
    }

    private void afterLoad(int count, String emptyMsg) {
        list.setSelection(0);
        if (count == 0) showMessage(emptyMsg, false);
        else emptyBox.setVisibility(View.GONE);
    }

    /** Komunikat o błędzie z krótką przyczyną techniczną (pomaga zdiagnozować problem). */
    private String withDetail(int msgRes) {
        String m = getString(msgRes);
        return lastError.length() > 0 ? m + "\n\n(" + lastError + ")" : m;
    }

    private void showMessage(String msg, boolean loading) {
        emptyBox.setVisibility(View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        emptyText.setText(msg);
    }

    private void onListClick(int position) {
        ListAdapter a = list.getAdapter();
        if (a == stationAdapter) {
            play(stationAdapter.getItem(position));
        } else if (a == podcastAdapter) {
            PodcastAdapter.Row r = podcastAdapter.getItem(position);
            if (r.podcast != null) openPodcast(r.podcast);
            else if (r.clickableHeader) refresh();
        } else if (a == episodeAdapter) {
            playEpisode(episodeAdapter.getItem(position));
        }
    }

    // ---------------------------------------------------------------- stacje

    private void toggleFavorite(Station s) {
        if (s.custom && favorites.contains(s)) {
            confirmDelete(s); // własna stacja istnieje tylko w ulubionych – zapytaj przed usunięciem
            return;
        }
        boolean now = favorites.toggle(s);
        Toast.makeText(this, now ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
        if (tab == Tab.FAVORITES) refresh();
        else stationAdapter.notifyDataSetChanged();
        updateFull();
    }

    /** Okno dodawania (existing == null) lub edycji własnej stacji. */
    @SuppressWarnings("deprecation")
    private void editStation(final Station existing) {
        View form = getLayoutInflater().inflate(R.layout.dialog_station, null);
        final EditText name = form.findViewById(R.id.station_name);
        final EditText url = form.findViewById(R.id.station_url);
        final TextView error = form.findViewById(R.id.station_error);
        if (existing != null) {
            name.setText(existing.name);
            url.setText(existing.url);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(existing == null ? R.string.add_station : R.string.edit_station)
                .setView(form)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(android.R.string.cancel, null);
        if (existing != null) b.setNeutralButton(R.string.delete, (d, w) -> confirmDelete(existing));
        final AlertDialog dialog = b.create();
        dialog.show();
        // własny przycisk „Zapisz”, żeby przy błędnym adresie okno się nie zamykało
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = normalizeUrl(url.getText().toString());
            if (u == null) {
                error.setVisibility(View.VISIBLE);
                return;
            }
            String n = name.getText().toString().trim();
            if (n.length() == 0) n = Uri.parse(u).getHost();
            Station st = existing != null ? existing : new Station();
            if (existing == null) {
                st.uuid = "custom-" + UUID.randomUUID();
                st.custom = true;
            }
            st.name = n;
            st.url = u;
            st.codec = "";
            st.hls = false;
            if (existing == null) {
                favorites.add(st);
                Toast.makeText(this, R.string.station_added, Toast.LENGTH_SHORT).show();
            } else {
                favorites.replace(st);
            }
            dialog.dismiss();
            hideKeyboard();
            if (tab == Tab.FAVORITES) refresh();
            else selectTab(Tab.FAVORITES);
        });
    }

    @SuppressWarnings("deprecation")
    private void confirmDelete(final Station s) {
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(s.name)
                .setMessage(R.string.confirm_delete)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    favorites.remove(s);
                    Toast.makeText(this, R.string.fav_removed, Toast.LENGTH_SHORT).show();
                    if (tab == Tab.FAVORITES) refresh();
                    else stationAdapter.notifyDataSetChanged();
                    updateFull();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Dopisuje „http://”, jeśli go brak; zwraca null dla adresu, który na pewno nie zadziała. */
    private static String normalizeUrl(String raw) {
        String u = raw.trim().replace(" ", "");
        if (u.length() == 0) return null;
        if (!u.contains("://")) u = "http://" + u;
        Uri p = Uri.parse(u);
        String scheme = p.getScheme() == null ? "" : p.getScheme().toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String host = p.getHost();
        if (host == null || host.length() == 0) return null;
        return u;
    }

    // ---------------------------------------------------------------- podcasty

    private void showPodcasts(int id, final String q) {
        setAdapter(podcastAdapter);
        final String cc = AppSettings.country(this);
        if (q.length() > 0) {
            podcastAdapter.setRows(null);
            showMessage(getString(R.string.loading), true);
            async(id, () -> podcastApi.search(q, cc), r -> {
                if (r == null) {
                    lastLoadFailed = true;
                    showMessage(withDetail(R.string.podcast_top_error), false);
                    return;
                }
                List<PodcastAdapter.Row> rows = new ArrayList<>();
                for (Podcast p : r) rows.add(PodcastAdapter.Row.of(p));
                podcastAdapter.setRows(rows);
                afterLoad(rows.size(), getString(R.string.no_results));
            });
            return;
        }

        final List<Podcast> subs = store.subscriptions();
        if (topCache != null && cc.equals(topCountry)) {
            podcastAdapter.setRows(homeRows(subs, topCache, false));
            emptyBox.setVisibility(View.GONE);
            return;
        }
        podcastAdapter.setRows(homeRows(subs, null, false));
        if (subs.isEmpty()) showMessage(getString(R.string.loading), true);
        else emptyBox.setVisibility(View.GONE);
        async(id, () -> podcastApi.top(cc), r -> {
            if (r == null) {
                lastLoadFailed = true;
                podcastAdapter.setRows(homeRows(subs, null, true));
                if (subs.isEmpty()) showMessage(withDetail(R.string.podcast_top_error), false);
            } else {
                topCache = r;
                topCountry = cc;
                podcastAdapter.setRows(homeRows(subs, r, false));
                emptyBox.setVisibility(View.GONE);
            }
        });
    }

    private List<PodcastAdapter.Row> homeRows(List<Podcast> subs, List<Podcast> top, boolean failed) {
        List<PodcastAdapter.Row> rows = new ArrayList<>();
        Set<String> subKeys = new HashSet<>();
        if (!subs.isEmpty()) {
            rows.add(PodcastAdapter.Row.header(getString(R.string.podcasts_subscribed)));
            for (Podcast p : subs) {
                rows.add(PodcastAdapter.Row.of(p));
                subKeys.add(p.key());
            }
        }
        String topTitle = getString(R.string.podcasts_top,
                AppSettings.countryName(this, AppSettings.country(this)));
        if (top != null) {
            rows.add(PodcastAdapter.Row.header(topTitle));
            for (Podcast p : top) if (!subKeys.contains(p.key())) rows.add(PodcastAdapter.Row.of(p));
        } else if (failed) {
            if (!subs.isEmpty()) rows.add(PodcastAdapter.Row.action(getString(R.string.podcast_top_error)));
        } else if (!subs.isEmpty()) {
            rows.add(PodcastAdapter.Row.header(getString(R.string.loading)));
        }
        return rows;
    }

    private void openPodcast(Podcast p) {
        openPodcast = p;
        hideKeyboard();
        updateHeader();
        refresh();
    }

    private void closePodcast() {
        openPodcast = null;
        updateHeader();
        refresh();
    }

    private void showEpisodes(int id) {
        setAdapter(episodeAdapter);
        final Podcast p = openPodcast;
        if (episodesCache != null && p.key().equals(episodesKey)) {
            episodeAdapter.setItems(episodesCache);
            afterLoad(episodesCache.size(), getString(R.string.no_episodes));
            return;
        }
        episodeAdapter.setItems(null);
        showMessage(getString(R.string.loading), true);
        final String cc = AppSettings.country(this);
        async(id, () -> podcastApi.episodes(p, cc), r -> {
            if (r == null) {
                lastLoadFailed = true;
                showMessage(withDetail(R.string.podcast_error), false);
                return;
            }
            if (store.isSubscribed(p)) store.update(p); // zapisz znaleziony adres kanału
            if (p.artwork.length() == 0 && r.image.length() > 0) {
                p.artwork = r.image;
                updateHeader();
            }
            episodesCache = r.episodes;
            episodesKey = p.key();
            episodeAdapter.setItems(r.episodes);
            afterLoad(r.episodes.size(), getString(R.string.no_episodes));
        });
    }

    private void toggleSubscription(Podcast p) {
        boolean now = store.toggle(p);
        Toast.makeText(this, now ? R.string.subscribed : R.string.unsubscribed, Toast.LENGTH_SHORT).show();
        updateHeader();
        if (tab == Tab.PODCASTS && openPodcast == null && search.getText().length() == 0) refresh();
        else podcastAdapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- odtwarzanie

    private void play(Station s) {
        if (service == null) return;
        lastStation = s;
        lastKind = "station";
        service.play(s);
        api.countClick(s);
    }

    private void playEpisode(Episode e) {
        if (service == null) return;
        lastEpisode = e;
        lastKind = "episode";
        service.playEpisode(e);
    }

    private Station curStation() {
        if (service != null) {
            if (service.getStation() != null) return service.getStation();
            if (service.getEpisode() != null) return null;
        }
        return "station".equals(lastKind) ? lastStation : null;
    }

    private Episode curEpisode() {
        if (service != null) {
            if (service.getEpisode() != null) return service.getEpisode();
            if (service.getStation() != null) return null;
        }
        return "episode".equals(lastKind) ? lastEpisode : null;
    }

    private void togglePlay() {
        if (service == null) return;
        Episode e = curEpisode();
        if (e != null) {
            if (service.getEpisode() != null) service.togglePlayPause();
            else playEpisode(e);
            return;
        }
        if (service.isActive()) {
            service.stop();
        } else {
            Station s = curStation();
            if (s != null) play(s);
        }
    }

    private RadioService.State state() {
        return service != null ? service.getState() : RadioService.State.IDLE;
    }

    /** Pozycja i długość odcinka (z odtwarzacza albo zapamiętane). */
    private long[] episodeTimes(Episode e) {
        long pos, dur;
        if (service != null && service.getEpisode() != null) {
            pos = service.getPosition();
            dur = service.getDuration();
        } else {
            pos = store.position(e);
            dur = e.durationSec * 1000L;
        }
        return new long[]{pos, dur};
    }

    @Override
    public void onPlayerChanged() {
        updateMini();
        updateFull();
        RadioService.State st = state();
        boolean active = service != null && service.isActive();
        Station s = curStation();
        Episode e = curEpisode();
        stationAdapter.setPlaying(active ? s : null);
        episodeAdapter.setPlaying(e != null && st != RadioService.State.IDLE ? e : null);
        if (st == RadioService.State.ERROR) stationAdapter.notifyDataSetChanged(); // np. nowo wykryta stacja Opus
        if (e != null && st == RadioService.State.IDLE && list.getAdapter() == episodeAdapter) {
            episodeAdapter.notifyDataSetChanged(); // odcinek skończony – odśwież oznaczenia
        }
    }

    private void updateMini() {
        RadioService.State st = state();
        boolean active = service != null && service.isActive();
        boolean error = st == RadioService.State.ERROR;
        Station s = curStation();
        Episode e = curEpisode();
        String info;
        int progressValue = -2; // -2 = ukryty, -1 = kręci się, 0–100 = postęp

        if (e != null) {
            playerName.setText(e.title);
            images.load(e.artwork(), e.podcastName, playerLogo);
            long[] t = episodeTimes(e);
            String times = Episode.formatTime(t[0]) + (t[1] > 0 ? " / " + Episode.formatTime(t[1]) : "");
            if (st == RadioService.State.PLAYING) info = times + "  ·  " + e.podcastName;
            else if (st == RadioService.State.PAUSED) info = service.statusText() + "  ·  " + times;
            else if (st == RadioService.State.IDLE) {
                String done = service != null ? service.statusText() : "";
                info = done.length() > 0 ? done : e.podcastName;
            } else info = service.statusText();
            if ((st == RadioService.State.PLAYING || st == RadioService.State.PAUSED) && t[1] > 0) {
                progressValue = (int) Math.min(100, t[0] * 100 / t[1]);
            }
        } else if (s != null) {
            playerName.setText(s.name);
            images.load(s.favicon, s.name, playerLogo);
            info = service != null ? service.statusText() : "";
            if (info.length() == 0) info = getString(R.string.status_stopped);
        } else {
            playerName.setText(R.string.nothing_playing);
            playerLogo.setImageDrawable(new LetterDrawable(""));
            info = "";
        }
        if (!info.equals(playerInfo.getText().toString())) {
            playerInfo.setText(info);
            playerInfo.setSelected(true); // włącza przewijanie długich tytułów
        }
        playerInfo.setTextColor(getResources().getColor(error ? R.color.error : R.color.accent));
        playerBtn.setImageResource(active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        if (st == RadioService.State.CONNECTING || st == RadioService.State.BUFFERING) {
            progressValue = service.getProgress();
        }
        setBar(bufferProgress, progressValue, View.GONE);
    }

    private static void setBar(ProgressBar bar, int value, int hiddenVisibility) {
        if (value == -2) {
            bar.setVisibility(hiddenVisibility);
        } else {
            bar.setVisibility(View.VISIBLE);
            if (value < 0) {
                bar.setIndeterminate(true);
            } else {
                bar.setIndeterminate(false);
                bar.setProgress(value);
            }
        }
    }

    // ---------------------------------------------------------------- widok pełnoekranowy

    private void setupFullPlayer() {
        findViewById(R.id.full_collapse).setOnClickListener(v -> showFull(false));
        fullPlay.setOnClickListener(v -> togglePlay());
        fullStar.setOnClickListener(v -> {
            Station s = curStation();
            if (s != null) toggleFavorite(s);
        });
        fullBack.setOnClickListener(v -> seekEpisodeBy(-15000));
        fullFwd.setOnClickListener(v -> seekEpisodeBy(30000));
        fullSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (!fromUser) return;
                Episode e = curEpisode();
                if (e == null) return;
                long dur = episodeTimes(e)[1];
                if (dur > 0) fullPos.setText(Episode.formatTime(dur * value / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                Episode e = curEpisode();
                if (e == null) return;
                long dur = episodeTimes(e)[1];
                if (dur > 0) seekEpisodeTo(dur * sb.getProgress() / 1000);
            }
        });
    }

    private void seekEpisodeBy(long delta) {
        Episode e = curEpisode();
        if (e == null) return;
        seekEpisodeTo(episodeTimes(e)[0] + delta);
    }

    private void seekEpisodeTo(long ms) {
        Episode e = curEpisode();
        if (e == null) return;
        if (service != null && service.getEpisode() != null) {
            service.seekTo(ms);
        } else {
            long dur = e.durationSec * 1000L;
            if (dur > 0) ms = Math.min(ms, dur - 1000);
            store.savePosition(e, Math.max(0, ms));
            updateFull();
        }
    }

    private void showFull(boolean show) {
        if (show && curStation() == null && curEpisode() == null) return;
        fullPlayer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            hideKeyboard();
            layoutFull();
            updateFull();
        }
    }

    /** Pionowo: okładka nad opisem. Poziomo: okładka po lewej, opis i przyciski po prawej. */
    private void layoutFull() {
        if (fullBody == null) return;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        fullBody.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        fullLogo.setLayoutParams(landscape
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        fullInfo.setLayoutParams(landscape
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fullInfo.setPadding(landscape ? pad : 0, landscape ? 0 : pad * 5 / 6, 0, 0);
    }

    private void updateFull() {
        if (fullPlayer == null || fullPlayer.getVisibility() != View.VISIBLE) return;
        RadioService.State st = state();
        boolean active = service != null && service.isActive();
        Station s = curStation();
        Episode e = curEpisode();
        String status = service != null ? service.statusText() : "";

        if (e != null) {
            fullName.setText(e.title);
            fullSub.setText(e.podcastName);
            bigImages.load(Podcast.bigArtwork(e.artwork()), e.podcastName, fullLogo);
            fullStar.setVisibility(View.INVISIBLE);
            fullSeekBox.setVisibility(View.VISIBLE);
            fullBack.setVisibility(View.VISIBLE);
            fullFwd.setVisibility(View.VISIBLE);
            if (st == RadioService.State.PLAYING) status = "";
            long[] t = episodeTimes(e);
            fullSeek.setEnabled(t[1] > 0);
            if (!seeking) {
                fullSeek.setProgress(t[1] > 0 ? (int) Math.min(1000, t[0] * 1000 / t[1]) : 0);
                fullPos.setText(Episode.formatTime(t[0]));
            }
            fullDur.setText(t[1] > 0 ? Episode.formatTime(t[1]) : "--:--");
        } else if (s != null) {
            fullName.setText(s.name);
            fullSub.setText(s.custom ? s.shortUrl() : s.details());
            bigImages.load(s.favicon, s.name, fullLogo);
            fullStar.setVisibility(View.VISIBLE);
            fullStar.setImageResource(favorites.contains(s)
                    ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off);
            fullSeekBox.setVisibility(View.GONE);
            fullBack.setVisibility(View.GONE);
            fullFwd.setVisibility(View.GONE);
            if (status.length() == 0) status = getString(R.string.status_stopped);
        } else {
            showFull(false);
            return;
        }
        fullStatus.setText(status);
        fullStatus.setTextColor(getResources().getColor(
                st == RadioService.State.ERROR ? R.color.error : R.color.accent));
        int bar = -2;
        if (st == RadioService.State.CONNECTING || st == RadioService.State.BUFFERING) bar = service.getProgress();
        setBar(fullBuffer, bar, View.INVISIBLE);
        fullPlay.setImageResource(active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    // ---------------------------------------------------------------- raport błędu

    /** Jeśli poprzednio aplikacja się wywróciła – pokaż raport do skopiowania lub wysłania. */
    @SuppressWarnings("deprecation")
    private void showCrashReport() {
        final String report = CrashLog.pending(this);
        if (report == null) return;
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);
        TextView msg = new TextView(this);
        msg.setText(R.string.crash_message);
        msg.setTextSize(15);
        box.addView(msg);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setPadding(0, pad / 2, 0, pad / 2);
        sv.addView(tv);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (240 * getResources().getDisplayMetrics().density)));

        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(R.string.crash_title)
                .setView(box)
                .setPositiveButton(R.string.copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Proste Radio", report));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.share, (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "Proste Radio – crash report");
                    send.putExtra(Intent.EXTRA_TEXT, report);
                    try {
                        startActivity(Intent.createChooser(send, getString(R.string.share)));
                    } catch (Throwable ignored) {}
                })
                .setNegativeButton(R.string.close, null)
                .setOnDismissListener(d -> CrashLog.clear(this))
                .show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }
}
