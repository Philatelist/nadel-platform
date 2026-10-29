package app.nadel.documents.internal.pdf;

import app.nadel.documents.internal.pdf.PdfFonts.Face;
import java.util.Objects;

/**
 * A paragraph of a document: the text wraps at spaces to the width of the page (a word longer than a line is broken
 * between characters; a no-break space never breaks), in one face and size, aligned left, right or centred.
 *
 * @param text       the text; line breaks in it become spaces ({@link PdfFonts#printable})
 * @param face       the face
 * @param size       the font size in points, above zero
 * @param align      the alignment of every line
 * @param spaceAfter the space below the paragraph in points, at least zero
 */
public record TextBlock(String text, Face face, float size, Align align, float spaceAfter) {

    /** How the lines of a paragraph or a cell are aligned. */
    public enum Align {
        LEFT,
        RIGHT,
        CENTER
    }

    public TextBlock {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(align, "align");
        if (size <= 0 || spaceAfter < 0) {
            throw new IllegalArgumentException("size must be above zero and spaceAfter at least zero");
        }
    }

    /** A regular left-aligned paragraph with a small gap after it. */
    public static TextBlock plain(String text, float size) {
        return new TextBlock(text, Face.REGULAR, size, Align.LEFT, size * 0.4f);
    }

    /** A semibold paragraph. */
    public static TextBlock strong(String text, float size, Align align) {
        return new TextBlock(text, Face.SEMIBOLD, size, align, size * 0.4f);
    }
}
