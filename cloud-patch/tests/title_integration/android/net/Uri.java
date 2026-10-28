package android.net;

/** Test stub for android.net.Uri: only what VivoCarLyrics.coverUri touches. */
public final class Uri {
    private final String text;

    private Uri(String text) {
        this.text = text;
    }

    public static Uri parse(String text) {
        return text == null ? null : new Uri(text);
    }

    @Override
    public String toString() {
        return text;
    }
}
