package pl.prosteradio;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        try {
            CrashLog.BuildInfo.versionName =
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        // Wczytanie certyfikatów trwa chwilę – robimy to w tle.
        new Thread(() -> Tls.init(App.this), "tls-init").start();
    }
}
