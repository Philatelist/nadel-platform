package app.nadel.documents.internal.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;

/**
 * The embedded fonts of the documents: IBM Plex Sans Regular and SemiBold (OFL-1.1, {@code documents/fonts/}, source in
 * {@code documents/README.md}). The TTF bytes are read once; every document parses its own copy and embeds a subset of
 * the glyphs it uses ({@link PDType0Font}, Identity-H), so documents never share a mutable font object. The Unicode
 * maps of the fonts, parsed once and only read, tell which characters have a glyph: {@link #printable} replaces any
 * other character, so user content (a name, a note) can never break a document.
 */
public final class PdfFonts {

    /** The two faces of the documents. */
    public enum Face {
        REGULAR("documents/fonts/IBMPlexSans-Regular.ttf"),
        SEMIBOLD("documents/fonts/IBMPlexSans-SemiBold.ttf");

        private final String resource;

        Face(String resource) {
            this.resource = resource;
        }
    }

    /** What stands for a character the fonts have no glyph for. */
    static final char REPLACEMENT = '?';

    private static final Map<Face, byte[]> BYTES = new EnumMap<>(Face.class);
    private static final Map<Face, CmapLookup> CMAPS = new EnumMap<>(Face.class);

    static {
        for (Face face : Face.values()) {
            byte[] bytes = read(face.resource);
            BYTES.put(face, bytes);
            CMAPS.put(face, cmap(bytes));
        }
    }

    private PdfFonts() {
    }

    /** Loads the face into the document as a subset font. */
    static PDType0Font load(PDDocument document, Face face) {
        try {
            return PDType0Font.load(document, new RandomAccessReadBuffer(BYTES.get(face)), true, false);
        } catch (IOException e) {
            throw new UncheckedIOException("The embedded font cannot be loaded", e);
        }
    }

    /** Whether both faces have a glyph for the character. */
    public static boolean covers(int codePoint) {
        return CMAPS.values().stream().allMatch(cmap -> cmap.getGlyphId(codePoint) != 0);
    }

    /**
     * The text as the documents can print it: line breaks, tabs and other white space become a plain space (the
     * no-break space stays), control characters are dropped and a character without a glyph becomes {@code ?}.
     */
    public static String printable(String text) {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == ' ') {
                out.append(' ');
            } else if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                out.append(' ');
            } else if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) {
                return;
            } else if (covers(codePoint)) {
                out.appendCodePoint(codePoint);
            } else {
                out.append(REPLACEMENT);
            }
        });
        return out.toString();
    }

    private static byte[] read(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("The embedded font " + resource + " is missing", e);
        }
    }

    private static CmapLookup cmap(byte[] bytes) {
        try {
            TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(bytes));
            return font.getUnicodeCmapLookup();
        } catch (IOException e) {
            throw new UncheckedIOException("The embedded font cannot be parsed", e);
        }
    }
}
