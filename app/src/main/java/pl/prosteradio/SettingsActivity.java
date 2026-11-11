package pl.prosteradio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;

/** Ekran ustawień: język i długość bufora. */
public class SettingsActivity extends Activity {
    private TextView languageValue, bufferValue;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppSettings.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        languageValue = findViewById(R.id.language_value);
        bufferValue = findViewById(R.id.buffer_value);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.language_row).setOnClickListener(v -> chooseLanguage());
        findViewById(R.id.buffer_row).setOnClickListener(v -> chooseBuffer());
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.version)).setText("Proste Radio " + v);
        } catch (Exception ignored) {}
        refresh();
    }

    private void refresh() {
        languageValue.setText(AppSettings.LANG_NAMES[AppSettings.languageIndex(this)]);
        bufferValue.setText(getString(R.string.seconds, AppSettings.bufferSeconds(this)));
    }

    @SuppressWarnings("deprecation")
    private void chooseLanguage() {
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(AppSettings.LANG_NAMES, AppSettings.languageIndex(this), (d, which) -> {
                    d.dismiss();
                    String code = AppSettings.LANG_CODES[which];
                    if (!code.equals(AppSettings.language(this))) {
                        AppSettings.setLanguage(this, code);
                        recreate(); // przeładuj ekran w nowym języku
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void chooseBuffer() {
        final int[] choices = AppSettings.BUFFER_CHOICES;
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) labels[i] = getString(R.string.seconds, choices[i]);
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(R.string.settings_buffer)
                .setSingleChoiceItems(labels, AppSettings.bufferIndex(this), (d, which) -> {
                    d.dismiss();
                    AppSettings.setBufferSeconds(this, choices[which]);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
