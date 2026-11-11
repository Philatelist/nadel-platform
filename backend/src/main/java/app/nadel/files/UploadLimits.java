package app.nadel.files;

/** The size limits shared by the modules that store uploaded files. */
public final class UploadLimits {

    /** 5 MiB: the largest stored file (the {@code size_bytes} CHECK of the tables that store one). */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private UploadLimits() {
    }
}
