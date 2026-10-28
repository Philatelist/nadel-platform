package app.nadel.documents.internal.pdf;

import app.nadel.documents.internal.pdf.PdfFonts.Face;
import app.nadel.documents.internal.pdf.Table.Column;
import app.nadel.documents.internal.pdf.Table.Row;
import app.nadel.documents.internal.pdf.TextBlock.Align;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.EnumMap;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * A PDF document drawn top to bottom with a cursor (feature 016, ADR 0017): paragraphs ({@link TextBlock}), tables
 * ({@link Table}) that go on on a new page with their header, rules, a framed mark and a signature line, on A4 or A5
 * pages with fixed margins, in the embedded IBM Plex Sans ({@link PdfFonts}, subsets of the glyphs used).
 * <p>
 * <b>Byte stability:</b> the same calls with the same identity and time give the same bytes. The file identifier
 * ({@code /ID}) is the first 16 bytes of the SHA-256 of the identity the caller passes (the document's content), twice;
 * the creation and modification dates are the given instant (UTC); nothing else depends on the clock or chance (the
 * subset tag of a font is derived from the glyphs it holds). Not thread-safe: one document per call.
 */
public final class PdfDocument implements AutoCloseable {

    /** The paper sizes of the documents. */
    public enum PageSize {
        A4(PDRectangle.A4, mm(15)),
        A5(PDRectangle.A5, mm(12));

        private final PDRectangle rectangle;
        private final float margin;

        PageSize(PDRectangle rectangle, float margin) {
            this.rectangle = rectangle;
            this.margin = margin;
        }

        /** The width of the page in points. */
        public float width() {
            return rectangle.getWidth();
        }

        /** The height of the page in points. */
        public float height() {
            return rectangle.getHeight();
        }
    }

    private static final float LEADING = 1.3f;
    private static final float CELL_PADDING = 2.5f;
    private static final float THIN = 0.5f;

    private final PDDocument document;
    private final PageSize size;
    private final Map<Face, PDType0Font> fonts = new EnumMap<>(Face.class);
    private PDPageContentStream content;
    private float y;
    private boolean closed;

    private PdfDocument(PageSize size, byte[] identity, Instant time) {
        this.size = size;
        this.document = new PDDocument();
        for (Face face : Face.values()) {
            fonts.put(face, PdfFonts.load(document, face));
        }
        byte[] id = Arrays.copyOf(sha256(identity), 16);
        COSArray ids = new COSArray();
        ids.add(new COSString(id));
        ids.add(new COSString(id));
        document.getDocument().setDocumentID(ids);
        PDDocumentInformation info = new PDDocumentInformation();
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(time.toEpochMilli());
        info.setCreationDate(calendar);
        info.setModificationDate(calendar);
        document.setDocumentInformation(info);
        newPage();
    }

    /**
     * A new document with its first page.
     *
     * @param size     the paper size of every page
     * @param identity the bytes the file identifier is derived from: the document's content, so the same content gives
     *                 the same identifier
     * @param time     the creation and modification time written into the file
     */
    public static PdfDocument create(PageSize size, byte[] identity, Instant time) {
        return new PdfDocument(Objects.requireNonNull(size, "size"), Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(time, "time"));
    }

    /** Points per millimetre times the millimetres. */
    public static float mm(float millimetres) {
        return millimetres * 72f / 25.4f;
    }

    /** The width between the margins, in points. */
    public float contentWidth() {
        return size.width() - 2 * size.margin;
    }

    /** The space left on the page below the cursor, in points. */
    public float remaining() {
        return y - size.margin;
    }

    /** The number of pages so far. */
    public int pages() {
        return document.getNumberOfPages();
    }

    /** Starts a new page; the cursor goes to its top margin. */
    public void newPage() {
        io(() -> {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(size.rectangle);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
        });
        y = size.height() - size.margin;
    }

    /** Moves the cursor down; at the end of a page it starts the next one. */
    public void space(float points) {
        if (points >= remaining()) {
            newPage();
        } else {
            y -= points;
        }
    }

    /** A paragraph; its lines go on on the next page when the page is full. */
    public void text(TextBlock block) {
        PDType0Font font = fonts.get(block.face());
        float lineHeight = block.size() * LEADING;
        for (String line : wrap(PdfFonts.printable(block.text()), font, block.size(), contentWidth())) {
            if (lineHeight > remaining()) {
                newPage();
            }
            y -= lineHeight;
            show(line, font, block.size(), size.margin, contentWidth(), block.align(), y + block.size() * 0.28f);
        }
        y -= Math.min(block.spaceAfter(), remaining());
    }

    /** A thin rule across the page under the cursor. */
    public void rule() {
        space(2);
        stroke(size.margin, y, size.margin + contentWidth(), y, THIN);
        space(4);
    }

    /**
     * A mark in a frame: the text in semibold, centred in a box across the page (for example a reversal or an
     * annulment).
     */
    public void mark(String text, float fontSize) {
        PDType0Font font = fonts.get(Face.SEMIBOLD);
        List<String> lines = wrap(PdfFonts.printable(text), font, fontSize, contentWidth() - 4 * CELL_PADDING);
        float lineHeight = fontSize * LEADING;
        float height = lines.size() * lineHeight + 2 * CELL_PADDING;
        if (height > remaining()) {
            newPage();
        }
        float top = y;
        for (String line : lines) {
            y -= lineHeight;
            show(line, font, fontSize, size.margin + CELL_PADDING, contentWidth() - 2 * CELL_PADDING, Align.CENTER,
                    y + fontSize * 0.28f - CELL_PADDING);
        }
        y -= 2 * CELL_PADDING;
        rectangle(size.margin, y, contentWidth(), top - y, 1.2f);
        y -= Math.min(fontSize * 0.6f, remaining());
    }

    /**
     * A signature line: the text on the left, then a line to sign on to the right edge with a small caption under it
     * (for example «Прийняв(ла): Name ______ (підпис)»).
     */
    public void signature(String text, String caption, float fontSize) {
        PDType0Font font = fonts.get(Face.REGULAR);
        float lineHeight = fontSize * LEADING;
        float captionSize = fontSize * 0.75f;
        if (lineHeight * 2 + captionSize * LEADING > remaining()) {
            newPage();
        }
        y -= lineHeight * 1.6f;
        float lineWidth = mm(35);
        float textWidth = contentWidth() - lineWidth - mm(4);
        String printable = fit(PdfFonts.printable(text), font, fontSize, textWidth);
        show(printable, font, fontSize, size.margin, textWidth, Align.LEFT, y);
        float lineStart = size.margin + contentWidth() - lineWidth;
        stroke(lineStart, y - 1.5f, size.margin + contentWidth(), y - 1.5f, THIN);
        y -= captionSize * LEADING;
        show(PdfFonts.printable(caption), font, captionSize, lineStart, lineWidth, Align.CENTER, y);
        y -= Math.min(fontSize * 0.4f, remaining());
    }

    /** A table; a row that does not fit goes to a new page, where the header is repeated. */
    public void table(Table table) {
        float total = (float) table.columns().stream().mapToDouble(Column::weight).sum();
        float[] widths = new float[table.columns().size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = contentWidth() * table.columns().get(i).weight() / total;
        }
        Row header = new Row(table.columns().stream().map(Column::title).toList(), true);
        drawRow(table, widths, header, true);
        for (Row row : table.rows()) {
            if (rowHeight(table, widths, row) > remaining()) {
                newPage();
                drawRow(table, widths, header, true);
            }
            drawRow(table, widths, row, false);
        }
        y -= Math.min(table.size() * 0.6f, remaining());
    }

    /** Saves the document and closes it: the bytes of the PDF file. */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        io(() -> {
            content.close();
            document.save(out);
        });
        close();
        return out.toByteArray();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        io(document::close);
    }

    // ---- drawing

    private void drawRow(Table table, float[] widths, Row row, boolean header) {
        PDType0Font font = fonts.get(row.strong() ? Face.SEMIBOLD : Face.REGULAR);
        float height = rowHeight(table, widths, row);
        if (height > remaining()) {
            newPage();
        }
        float top = y;
        float x = size.margin;
        float lineHeight = table.size() * LEADING;
        for (int i = 0; i < widths.length; i++) {
            float cellWidth = widths[i] - 2 * CELL_PADDING;
            float lineY = top - CELL_PADDING;
            for (String line : wrap(PdfFonts.printable(row.cells().get(i)), font, table.size(), cellWidth)) {
                lineY -= lineHeight;
                show(line, font, table.size(), x + CELL_PADDING, cellWidth, table.columns().get(i).align(),
                        lineY + table.size() * 0.28f);
            }
            x += widths[i];
        }
        y = top - height;
        stroke(size.margin, y, size.margin + contentWidth(), y, header ? 0.9f : THIN * 0.6f);
    }

    private float rowHeight(Table table, float[] widths, Row row) {
        PDType0Font font = fonts.get(row.strong() ? Face.SEMIBOLD : Face.REGULAR);
        int lines = 1;
        for (int i = 0; i < widths.length; i++) {
            lines = Math.max(lines, wrap(PdfFonts.printable(row.cells().get(i)), font, table.size(),
                    widths[i] - 2 * CELL_PADDING).size());
        }
        return lines * table.size() * LEADING + 2 * CELL_PADDING;
    }

    private void show(String line, PDType0Font font, float fontSize, float left, float width, Align align,
            float baseline) {
        if (line.isEmpty()) {
            return;
        }
        float lineWidth = width(line, font, fontSize);
        float x = switch (align) {
            case LEFT -> left;
            case RIGHT -> left + width - lineWidth;
            case CENTER -> left + (width - lineWidth) / 2;
        };
        io(() -> {
            content.beginText();
            content.setFont(font, fontSize);
            content.newLineAtOffset(x, baseline);
            content.showText(line);
            content.endText();
        });
    }

    private void stroke(float x1, float y1, float x2, float y2, float lineWidth) {
        io(() -> {
            content.setLineWidth(lineWidth);
            content.moveTo(x1, y1);
            content.lineTo(x2, y2);
            content.stroke();
        });
    }

    private void rectangle(float x, float bottom, float width, float height, float lineWidth) {
        io(() -> {
            content.setLineWidth(lineWidth);
            content.addRect(x, bottom, width, height);
            content.stroke();
        });
    }

    // ---- text measurement

    /**
     * The lines of a text at most {@code width} wide: broken at plain spaces, a word wider than a line between
     * characters; an empty text is one empty line.
     */
    static List<String> wrap(String text, PDType0Font font, float fontSize, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split(" +")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, font, fontSize) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (width(rest, font, fontSize) > width && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && width(rest.substring(0, cut), font, fontSize) > width) {
                    cut--;
                }
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        lines.add(line.toString());
        return lines;
    }

    /** The text cut with «…» so that it is at most {@code width} wide. */
    static String fit(String text, PDType0Font font, float fontSize, float width) {
        if (width(text, font, fontSize) <= width) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && width(cut + "…", font, fontSize) > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.stripTrailing() + "…";
    }

    static float width(String text, PDType0Font font, float fontSize) {
        float[] units = new float[1];
        io(() -> units[0] = font.getStringWidth(text));
        return units[0] / 1000f * fontSize;
    }

    /** PDFBox work on memory buffers: an {@link IOException} there is a programming error, thrown unchecked. */
    private interface IoWork {
        void run() throws IOException;
    }

    private static void io(IoWork work) {
        try {
            work.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
