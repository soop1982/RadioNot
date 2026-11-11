# Proste Radio

Prosta aplikacja do słuchania radia internetowego, bez reklam, działająca na **Androidzie 4.4 (KitKat)** i nowszych.

- lista stacji z logotypami (domyślnie najpopularniejsze polskie),
- wyszukiwarka po nazwie (stacje z całego świata),
- ulubione stacje (gwiazdka; zapisane na tablecie, działają też po restarcie),
- tytuł utworu / komunikat nadawany przez stację, przewijany pod nazwą stacji,
- obsługa strumieni MP3, AAC / AAC+ (HE-AAC) i Ogg Vorbis; stacje Ogg Opus (niedostępne na Androidzie 4.4) są wyszarzone na liście,
- przycisk „+” do ręcznego dodania stacji (nazwa + adres strumienia); przytrzymaj własną stację, aby ją edytować lub usunąć,
- widoczny postęp łączenia i buforowania (licznik sekund, procent, pobrane kB),
- ustawienia: 15 języków (domyślnie angielski) i czas buforowania w sekundach,
- granie w tle, z powiadomieniem i przyciskiem „Stop”.

Katalog stacji pochodzi z darmowego, otwartego serwisu radio-browser.info.

## Jak zrobić z tego plik APK

Potrzebny jest komputer – sam tablet nie zbuduje aplikacji. Do wyboru dwie drogi:

### Droga A: Android Studio (Windows / Mac / Linux)

1. Zainstaluj Android Studio: https://developer.android.com/studio
2. Rozpakuj ten ZIP, w Android Studio wybierz **File → Open** i wskaż folder `ProsteRadio`.
3. Poczekaj, aż Android Studio pobierze potrzebne składniki (pierwszy raz trwa to kilka–kilkanaście minut).
4. Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. Gotowy plik: `app/build/outputs/apk/debug/app-debug.apk`.

### Droga B: GitHub (nic nie instalujesz)

1. Załóż darmowe konto na https://github.com i utwórz nowe repozytorium.
2. Wgraj do niego zawartość folderu `ProsteRadio` (przycisk „uploading an existing file”; ważne, żeby trafił też ukryty folder `.github`).
3. Otwórz zakładkę **Actions** – budowanie ruszy samo. Po ok. 5 minutach w sekcji **Artifacts** pojawi się `ProsteRadio-apk` do pobrania.

## Instalacja na tablecie

1. Na tablecie: **Ustawienia → Zabezpieczenia → Nieznane źródła** – włącz.
2. Skopiuj `app-debug.apk` na tablet (kabel USB, karta pamięci lub e-mail) i otwórz go z menedżera plików.
3. Po instalacji możesz z powrotem wyłączyć „Nieznane źródła”.

## Uwagi techniczne

- Android 4.4 domyślnie nie obsługuje nowoczesnych zabezpieczeń stron (TLS 1.2, nowe certyfikaty jak Let's Encrypt). Aplikacja sama je włącza i ma dołączony aktualny zestaw certyfikatów głównych (`assets/roots.pem`), więc większość stacji i logotypów działa mimo starego systemu.
- MP3, AAC/AAC+ i Ogg Vorbis odtwarza własny odtwarzacz (`StreamPlayer.java`): dzieli strumień na ramki (`FrameParser.java`, `OggParser.java`), liczy bufor w sekundach, dekoduje przez MediaCodec i gra przez AudioTrack. Wbudowany MediaPlayer Androida 4.4 zawieszał się na AAC+.
- Tytuły utworów idą w kolejce razem z dźwiękiem, więc zmieniają się dokładnie wtedy, gdy zaczyna grać nowy utwór – nawet przy długim buforze.
- Inne formaty (np. FLAC) i HLS trafiają awaryjnie do wbudowanego MediaPlayera; jeśli w ciągu 30 s nic nie zagra, aplikacja pokazuje błąd zamiast czekać bez końca.
- Tytuły utworów dla MediaPlayera są odczytywane przez pośrednika (`IcyProxy.java`) z danych ICY wysyłanych przez stacje Icecast/Shoutcast. Nie każda stacja je wysyła. Polskie znaki są obsługiwane także przy starszym kodowaniu Windows-1250.
- Jeśli stacja nie zagra przez pośrednika, aplikacja próbuje połączyć się bezpośrednio (wtedy bez tytułów).
- Stacje HLS (`.m3u8`) są odtwarzane bezpośrednio; Android 4.4 obsługuje je tylko częściowo.
- Brak zewnętrznych bibliotek – wyłącznie standardowe API Androida.
