package pl.prosteradio;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Raport błędów: gdy aplikacja się wywróci, zapisuje szczegóły do pliku.
 * Przy następnym uruchomieniu MainActivity pokazuje je użytkownikowi do skopiowania.
 * Trzyma też krótki dziennik ostatnich zdarzeń (zmiany stacji, formaty, błędy),
 * który trafia do raportu – ułatwia odtworzenie sytuacji.
 */
public final class CrashLog {
    private static final String FILE = "crash.txt";
    private static final int MAX_EVENTS = 60;
    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static File dir;

    private CrashLog() {}

    public static void install(Context ctx) {
        dir = ctx.getFilesDir();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                write(thread, ex);
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, ex);
        });
    }

    /** Zapisuje zdarzenie do dziennika (i do logcat). */
    public static void event(String msg) {
        Log.i("ProsteRadio", msg);
        synchronized (events) {
            events.add(TIME.format(new Date()) + "  " + msg);
            while (events.size() > MAX_EVENTS) events.removeFirst();
        }
    }

    private static void write(Thread thread, Throwable ex) throws Exception {
        if (dir == null) return;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Proste Radio " + BuildInfo.versionName);
        pw.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("Thread: " + thread.getName());
        pw.println();
        ex.printStackTrace(pw);
        pw.println();
        pw.println("--- last events ---");
        synchronized (events) {
            for (String e : events) pw.println(e);
        }
        pw.flush();
        FileOutputStream out = new FileOutputStream(new File(dir, FILE));
        out.write(sw.toString().getBytes("UTF-8"));
        out.close();
    }

    /** Raport z poprzedniego uruchomienia albo null. */
    public static String pending(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), FILE).delete();
    }

    /** Uzupełniane w App.onCreate – żeby raport zawierał numer wersji. */
    public static final class BuildInfo {
        public static String versionName = "?";
    }
}
