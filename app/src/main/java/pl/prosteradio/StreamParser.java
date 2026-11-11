package pl.prosteradio;

/** Wspólny interfejs dzielenia strumienia na ramki (MP3/AAC lub Ogg). */
public interface StreamParser {
    void feed(byte[] b, int off, int n);
    FrameParser.Format format();
    /** Format nierozpoznany – trzeba spróbować wbudowanym odtwarzaczem. */
    boolean isUnsupported();
    /** Strumień Ogg Opus. */
    boolean isOpus();
}
