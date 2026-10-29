package android.media;

/** Test stub for android.media.MediaDescription: only what vivoBrowseChildren touches. */
public final class MediaDescription {

    private MediaDescription() {}

    public static final class Builder {
        public Builder() {}

        public Builder setMediaId(String mediaId) {
            return this;
        }

        public Builder setTitle(CharSequence title) {
            return this;
        }

        public Builder setSubtitle(CharSequence subtitle) {
            return this;
        }

        public MediaDescription build() {
            return new MediaDescription();
        }
    }
}
