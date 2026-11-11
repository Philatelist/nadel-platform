package app.nadel.files;

/** An allowed uploaded file type and the media type it is stored and served with. */
public enum FileKind {
    JPEG("image/jpeg"),
    PNG("image/png"),
    PDF("application/pdf");

    private final String mediaType;

    FileKind(String mediaType) {
        this.mediaType = mediaType;
    }

    /** The media type the file is stored and served with. */
    public String mediaType() {
        return mediaType;
    }
}
