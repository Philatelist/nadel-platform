package app.nadel.files;

import java.util.Optional;

/**
 * Tells the type of an uploaded file from its first bytes (feature 018 §2.3, technical considerations 2.6), never from
 * its name or the type the client declared: a JPEG starts with {@code FF D8 FF}, a PNG with its eight-byte signature, a
 * PDF with {@code %PDF-}. Anything else is no allowed type. Pure.
 */
public final class FileSniffer {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    private FileSniffer() {
    }

    /** The type the content starts like; empty for anything that is not a JPEG, PNG or PDF. */
    public static Optional<FileKind> sniff(byte[] content) {
        if (startsWith(content, JPEG)) {
            return Optional.of(FileKind.JPEG);
        }
        if (startsWith(content, PNG)) {
            return Optional.of(FileKind.PNG);
        }
        if (startsWith(content, PDF)) {
            return Optional.of(FileKind.PDF);
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
