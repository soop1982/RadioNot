package pl.prosteradio;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/** Ustawienia aplikacji: język interfejsu i długość bufora. */
public final class AppSettings {
    private static final String PREFS = "settings";
    private static final String KEY_LANG = "language";
    private static final String KEY_BUFFER = "buffer_seconds";
    private static final String KEY_COUNTRY = "country";

    /** 15 języków. Kod „in” to indonezyjski (tak nazywa go Android). */
    public static final String[] LANG_CODES = {
            "en", "zh", "hi", "es", "fr", "ar", "pt", "ru", "in", "de", "ja", "tr", "ko", "it", "pl",
    };
    /** Nazwy języków w ich własnym języku. */
    public static final String[] LANG_NAMES = {
            "English", "中文 (简体)", "हिन्दी", "Español", "Français", "العربية", "Português",
            "Русский", "Bahasa Indonesia", "Deutsch", "日本語", "Türkçe", "한국어", "Italiano", "Polski",
    };

    public static final int[] BUFFER_CHOICES = {1, 2, 3, 5, 8, 10, 15, 20, 30};
    public static final int DEFAULT_BUFFER = 3;

    private AppSettings() {}

    private static SharedPreferences prefs(Context ctx) {
        Context app = ctx.getApplicationContext();
        return (app != null ? app : ctx).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Domyślnie angielski – niezależnie od języka systemu. */
    public static String language(Context ctx) {
        return prefs(ctx).getString(KEY_LANG, "en");
    }

    public static void setLanguage(Context ctx, String code) {
        prefs(ctx).edit().putString(KEY_LANG, code).apply();
    }

    public static int languageIndex(Context ctx) {
        String l = language(ctx);
        for (int i = 0; i < LANG_CODES.length; i++) if (LANG_CODES[i].equals(l)) return i;
        return 0;
    }

    public static int bufferSeconds(Context ctx) {
        return prefs(ctx).getInt(KEY_BUFFER, DEFAULT_BUFFER);
    }

    public static void setBufferSeconds(Context ctx, int s) {
        prefs(ctx).edit().putInt(KEY_BUFFER, s).apply();
    }

    public static int bufferIndex(Context ctx) {
        int s = bufferSeconds(ctx);
        for (int i = 0; i < BUFFER_CHOICES.length; i++) if (BUFFER_CHOICES[i] == s) return i;
        return 2;
    }

    /** Kraj (kod ISO, np. „PL”) dla listy popularnych stacji i rankingu podcastów. */
    public static String country(Context ctx) {
        return prefs(ctx).getString(KEY_COUNTRY, "PL");
    }

    public static void setCountry(Context ctx, String code) {
        prefs(ctx).edit().putString(KEY_COUNTRY, code).apply();
    }

    /** Nazwa kraju w języku aplikacji. */
    public static String countryName(Context ctx, String code) {
        String n = new Locale("", code).getDisplayCountry(locale(language(ctx)));
        return n == null || n.length() == 0 ? code : n;
    }

    public static Locale locale(String code) {
        if (code.equals("zh")) return new Locale("zh", "CN");
        if (code.equals("pt")) return new Locale("pt", "BR");
        return new Locale(code);
    }

    /** Zwraca kontekst z teksami w wybranym języku. */
    public static Context wrap(Context base) {
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(locale(language(base)));
        return base.createConfigurationContext(c);
    }
}
