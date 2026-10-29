package app.nadel.documents.internal.nbu;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.springframework.core.io.ClassPathResource;

/**
 * The hryvnia sign as the outline of the glyph U+20B4 of the embedded IBM Plex Sans SemiBold (the font the documents
 * print with, OFL-1.1), read once. The glyph's coordinates (font units, y up) are flipped so that y points down.
 */
final class GlyphHryvniaSign implements HryvniaSign {

    static final GlyphHryvniaSign INSTANCE = new GlyphHryvniaSign();

    /** The face of {@code PdfFonts.Face.SEMIBOLD}. */
    static final String FONT = "documents/fonts/IBMPlexSans-SemiBold.ttf";
    static final int HRYVNIA_SIGN = 0x20B4;

    private final Shape outline;

    private GlyphHryvniaSign() {
        this.outline = read();
    }

    @Override
    public Shape outline() {
        return outline;
    }

    private static Shape read() {
        try (InputStream in = new ClassPathResource(FONT).getInputStream();
                TrueTypeFont font = new TTFParser().parse(new RandomAccessReadBuffer(in.readAllBytes()))) {
            int glyphId = font.getUnicodeCmapLookup().getGlyphId(HRYVNIA_SIGN);
            if (glyphId == 0) {
                throw new IllegalStateException("The embedded font has no hryvnia sign");
            }
            GlyphData glyph = font.getGlyph().getGlyph(glyphId);
            GeneralPath path = glyph.getPath();
            return AffineTransform.getScaleInstance(1, -1).createTransformedShape(path);
        } catch (IOException e) {
            throw new UncheckedIOException("The embedded font " + FONT + " cannot be read", e);
        }
    }
}
