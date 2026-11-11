package app.nadel.files;

/**
 * A JPEG or PNG whose structure cannot be read (truncated, a segment or chunk running past the end, a bad checksum, no
 * image data). The message is a fixed English sentence and never quotes the file. Each caller maps it to its own
 * problem.
 */
public class MalformedImageException extends RuntimeException {

    public MalformedImageException(String message) {
        super(message);
    }
}
