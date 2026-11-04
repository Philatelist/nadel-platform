package app.nadel.treasury.internal;

/**
 * A JPEG or PNG attachment whose structure cannot be read (truncated, a segment or chunk running past the end, a bad
 * checksum, no image data). The message is a fixed English sentence and never quotes the file.
 */
class MalformedImageException extends RuntimeException {

    MalformedImageException(String message) {
        super(message);
    }
}
