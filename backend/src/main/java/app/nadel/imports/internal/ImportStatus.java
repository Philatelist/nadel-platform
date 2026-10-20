package app.nadel.imports.internal;

/** The stored state of an import run. "Expired" is not stored: it is a preview past its expiry. */
enum ImportStatus {
    /** Checked without problems; can be committed until it expires. */
    PREVIEW,
    /** Checked with at least one problem; cannot be committed. */
    HAS_ERRORS,
    /** Saved. */
    COMMITTED,
    /** The data of the cooperative changed after the preview; the file must be uploaded again. */
    STALE
}
