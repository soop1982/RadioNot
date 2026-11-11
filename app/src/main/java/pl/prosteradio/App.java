package pl.prosteradio;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Wczytanie certyfikatów trwa chwilę – robimy to w tle.
        new Thread(() -> Tls.init(App.this), "tls-init").start();
    }
}
