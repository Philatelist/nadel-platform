package app.nadel.treasury.internal;

import java.util.Optional;

/**
 * Tells the type of an expense attachment from its first bytes (feature 018 §2.3, technical considerations 2.6), never
 * from its name or the type the client declared: a JPEG starts with {@code FF D8 FF}, a PNG with its eight-byte
 * signature, a PDF with {@code %PDF-}. Anything else is no allowed type. Pure.
 */
final class FileSniffer {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    private FileSniffer() {
    }

    /** An allowed attachment type and the media type it is stored and served with. */
    enum Kind {
        JPEG("image/jpeg"),
        PNG("image/png"),
        PDF("application/pdf");

        private final String mediaType;

        Kind(String mediaType) {
            this.mediaType = mediaType;
        }

        String mediaType() {
            return mediaType;
        }
    }

    /** The type the content starts like; empty for anything that is not a JPEG, PNG or PDF. */
    static Optional<Kind> sniff(byte[] content) {
        if (startsWith(content, JPEG)) {
            return Optional.of(Kind.JPEG);
        }
        if (startsWith(content, PNG)) {
            return Optional.of(Kind.PNG);
        }
        if (startsWith(content, PDF)) {
            return Optional.of(Kind.PDF);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
