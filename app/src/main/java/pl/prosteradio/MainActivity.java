package pl.prosteradio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements RadioService.Listener {
    private enum Tab { POPULAR, FAVORITES }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private EditText search;
    private ImageButton clear;
    private TextView tabPopular, tabFavorites;
    private ListView list;
    private LinearLayout emptyBox;
    private ProgressBar progress;
    private TextView emptyText;
    private ImageView playerLogo;
    private TextView playerName, playerInfo;
    private ImageButton playerBtn;
    private ProgressBar bufferProgress;

    private RadioApi api;
    private Favorites favorites;
    private ImageLoader images;
    private StationAdapter adapter;

    private Tab tab = Tab.POPULAR;
    private List<Station> popularCache;
    private int requestId;
    private boolean lastLoadFailed;
    private Station lastStation;
    private RadioService service;
    private String languageAtStart;

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
        setContentView(R.layout.activity_main);

        search = findViewById(R.id.search);
        clear = findViewById(R.id.clear);
        tabPopular = findViewById(R.id.tab_popular);
        tabFavorites = findViewById(R.id.tab_favorites);
        list = findViewById(R.id.list);
        emptyBox = findViewById(R.id.empty_box);
        progress = findViewById(R.id.progress);
        emptyText = findViewById(R.id.empty_text);
        playerLogo = findViewById(R.id.player_logo);
        playerName = findViewById(R.id.player_name);
        playerInfo = findViewById(R.id.player_info);
        playerBtn = findViewById(R.id.player_btn);
        bufferProgress = findViewById(R.id.buffer_progress);

        api = new RadioApi(this);
        favorites = new Favorites(this);
        int logoPx = (int) (64 * getResources().getDisplayMetrics().density);
        images = new ImageLoader(this, logoPx);
        adapter = new StationAdapter(getLayoutInflater(), images, favorites, this::toggleFavorite);
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> play(adapter.getItem(position)));
        tabPopular.setOnClickListener(v -> selectTab(Tab.POPULAR));
        tabFavorites.setOnClickListener(v -> selectTab(Tab.FAVORITES));
        emptyBox.setOnClickListener(v -> { if (lastLoadFailed) refresh(); });
        clear.setOnClickListener(v -> search.setText(""));
        playerBtn.setOnClickListener(v -> togglePlay());
        findViewById(R.id.settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.add_station).setOnClickListener(v -> editStation(null));
        // przytrzymanie własnej stacji – edycja lub usunięcie
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Station st = adapter.getItem(position);
            if (!st.custom) return false;
            editStation(st);
            return true;
        });

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
        if (favorites.all().size() > 0) tab = Tab.FAVORITES;
        selectTab(tab);
        onPlayerChanged();

        bindService(new Intent(this, RadioService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // po zmianie języka w ustawieniach przeładuj ekran
        if (!AppSettings.language(this).equals(languageAtStart)) recreate();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // obrót ekranu nie może przywrócić języka systemu
        Configuration c = new Configuration(getResources().getConfiguration());
        c.locale = AppSettings.locale(AppSettings.language(this));
        getResources().updateConfiguration(c, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        if (service != null) service.removeListener(this);
        unbindService(conn);
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- lista

    private void selectTab(Tab t) {
        tab = t;
        tabPopular.setSelected(t == Tab.POPULAR);
        tabFavorites.setSelected(t == Tab.FAVORITES);
        int on = getResources().getColor(R.color.accent), off = getResources().getColor(R.color.text_dim);
        tabPopular.setTextColor(t == Tab.POPULAR ? on : off);
        tabFavorites.setTextColor(t == Tab.FAVORITES ? on : off);
        refresh();
    }

    private void refresh() {
        final String q = search.getText().toString().trim();
        final int id = ++requestId;
        lastLoadFailed = false;

        if (tab == Tab.FAVORITES) {
            List<Station> out = new ArrayList<>();
            String ql = q.toLowerCase(Locale.getDefault());
            for (Station s : favorites.all()) {
                if (ql.length() == 0 || s.name.toLowerCase(Locale.getDefault()).contains(ql)) out.add(s);
            }
            show(out, getString(q.length() > 0 ? R.string.no_results : R.string.no_favorites));
            return;
        }

        if (q.length() == 0 && popularCache != null) {
            show(popularCache, getString(R.string.no_results));
            return;
        }

        showLoading();
        worker.execute(() -> {
            List<Station> result = null;
            try {
                result = q.length() == 0 ? api.popular() : api.search(q);
            } catch (Exception ignored) {}
            final List<Station> r = result;
            main.post(() -> {
                if (id != requestId || isFinishing()) return;
                if (r == null) {
                    lastLoadFailed = true;
                    adapter.setItems(null);
                    showMessage(getString(R.string.network_error), false);
                } else {
                    if (q.length() == 0) popularCache = r;
                    show(r, getString(R.string.no_results));
                }
            });
        });
    }

    private void show(List<Station> items, String emptyMsg) {
        adapter.setItems(items);
        list.setSelection(0);
        if (items.isEmpty()) showMessage(emptyMsg, false);
        else emptyBox.setVisibility(View.GONE);
    }

    private void showLoading() {
        adapter.setItems(null);
        showMessage(getString(R.string.loading), true);
    }

    private void showMessage(String msg, boolean loading) {
        emptyBox.setVisibility(View.VISIBLE);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        emptyText.setText(msg);
    }

    private void toggleFavorite(Station s) {
        if (s.custom && favorites.contains(s)) {
            confirmDelete(s); // własna stacja istnieje tylko w ulubionych – zapytaj przed usunięciem
            return;
        }
        boolean now = favorites.toggle(s);
        Toast.makeText(this, now ? R.string.fav_added : R.string.fav_removed, Toast.LENGTH_SHORT).show();
        if (tab == Tab.FAVORITES) refresh();
        else adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- własne stacje

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
                    else adapter.notifyDataSetChanged();
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

    // ---------------------------------------------------------------- odtwarzanie

    private void play(Station s) {
        if (service == null) return;
        lastStation = s;
        service.play(s);
        api.countClick(s);
    }

    private void togglePlay() {
        if (service == null) return;
        if (service.isActive()) {
            service.stop();
        } else {
            Station s = service.getStation() != null ? service.getStation() : lastStation;
            if (s != null) play(s);
        }
    }

    @Override
    public void onPlayerChanged() {
        Station s = service != null && service.getStation() != null ? service.getStation() : lastStation;
        boolean active = service != null && service.isActive();
        if (s == null) {
            playerName.setText(R.string.nothing_playing);
            playerInfo.setText("");
            playerLogo.setImageDrawable(new LetterDrawable(""));
        } else {
            playerName.setText(s.name);
            String info = service != null ? service.statusText() : "";
            if (info.length() == 0) info = getString(R.string.status_stopped);
            if (!info.equals(playerInfo.getText().toString())) {
                playerInfo.setText(info);
                playerInfo.setSelected(true); // włącza przewijanie długich tytułów
            }
            images.load(s.favicon, s.name, playerLogo);
        }
        // stan błędu: komunikat na czerwono, lista odświeżona (np. nowo wykryta stacja Opus)
        boolean error = service != null && service.getState() == RadioService.State.ERROR;
        playerInfo.setTextColor(getResources().getColor(error ? R.color.error : R.color.accent));
        if (error) adapter.notifyDataSetChanged();

        playerBtn.setImageResource(active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

        // pasek postępu: kręci się przy łączeniu, pokazuje procent przy buforowaniu
        RadioService.State st = service != null ? service.getState() : RadioService.State.IDLE;
        if (st == RadioService.State.CONNECTING || st == RadioService.State.BUFFERING) {
            int p = service.getProgress();
            bufferProgress.setVisibility(View.VISIBLE);
            if (p < 0) {
                bufferProgress.setIndeterminate(true);
            } else {
                bufferProgress.setIndeterminate(false);
                bufferProgress.setProgress(p);
            }
        } else {
            bufferProgress.setVisibility(View.GONE);
        }
        adapter.setPlaying(active ? s : null);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }
}
