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
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.jspecify.annotations.Nullable;

/**
 * A PDF document drawn top to bottom with a cursor (feature 016, ADR 0017): paragraphs ({@link TextBlock}), tables
 * ({@link Table}) that go on on a new page with their header, rules, a framed mark and a signature line, on A4 or A5
 * pages with fixed margins, in the embedded IBM Plex Sans ({@link PdfFonts}, subsets of the glyphs used).
 * <p>
 * <b>Layout helpers:</b> {@link #measure} gives the height a sequence of calls would take without drawing anything;
 * {@link #keepTogether} moves such a sequence to a new page when it does not fit below the cursor; {@link #beside}
 * draws two columns side by side; {@link #continuation} sets a header that every page started by a break gets (the
 * continuation of a long document); {@link #link} is a line of text that opens a URI.
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
    /** The cursor of a measuring pass: far above any page, so nothing breaks. */
    private static final float MEASURE_TOP = 1_000_000f;
    /** The colour of a link's text and underline (a dark blue that prints as a dark grey). */
    private static final float[] LINK_COLOUR = {0.05f, 0.27f, 0.62f};

    private final PDDocument document;
    private final PageSize size;
    private final Map<Face, PDType0Font> fonts = new EnumMap<>(Face.class);
    private PDPageContentStream content;
    private PDPage page;
    private float y;
    /** The left edge and the width of the column drawn in: the width between the margins outside {@link #beside}. */
    private float left;
    private float width;
    private boolean measuring;
    private @Nullable Runnable continuation;
    private boolean closed;

    private PdfDocument(PageSize size, byte[] identity, Instant time) {
        this.size = size;
        this.left = size.margin;
        this.width = size.width() - 2 * size.margin;
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

    /** The width of the column drawn in, in points: the width between the margins outside {@link #beside}. */
    public float contentWidth() {
        return width;
    }

    /** The height between the margins, in points: what one page holds. */
    public float pageHeight() {
        return size.height() - 2 * size.margin;
    }

    /** The space left on the page below the cursor, in points. */
    public float remaining() {
        return y - size.margin;
    }

    /** The number of pages so far. */
    public int pages() {
        return document.getNumberOfPages();
    }

    /**
     * Starts a new page; the cursor goes to its top margin, below the {@link #continuation} header when one is set.
     * Nothing happens while measuring.
     */
    public void newPage() {
        if (measuring) {
            return;
        }
        io(() -> {
            if (content != null) {
                content.close();
            }
            page = new PDPage(size.rectangle);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
        });
        y = size.height() - size.margin;
        Runnable header = continuation;
        if (header != null) {
            continuation = null;
            try {
                header.run();
            } finally {
                continuation = header;
            }
        }
    }

    /**
     * The header every page started from now on gets at its top (for example «Рахунок № … (продовження)»), drawn by
     * the caller's calls on this document; null for none. The page the cursor is on keeps what it has.
     */
    public void continuation(@Nullable Runnable header) {
        this.continuation = header;
    }

    /**
     * The height the calls of {@code draw} on this document take, in points, without drawing anything, adding a
     * page or moving the cursor (as if the page were endless).
     */
    public float measure(Runnable draw) {
        Objects.requireNonNull(draw, "draw");
        float savedY = y;
        boolean saved = measuring;
        measuring = true;
        y = MEASURE_TOP;
        try {
            draw.run();
            return MEASURE_TOP - y;
        } finally {
            y = savedY;
            measuring = saved;
        }
    }

    /**
     * Draws the calls of {@code draw} on one page: when they do not fit below the cursor, a new page is started first.
     * A sequence higher than a page still breaks where it must.
     */
    public void keepTogether(Runnable draw) {
        if (measure(draw) > remaining()) {
            newPage();
        }
        draw.run();
    }

    /**
     * Two columns side by side from the cursor, kept on one page ({@link #keepTogether}): the calls of {@code first}
     * in a column {@code firstWidth} wide at the left of the current column, then the calls of {@code second} in the
     * rest after a gap of {@code gap}, from the same top; the cursor ends below the taller one. A column draws with
     * the same calls as the page ({@link #contentWidth} is the column's width); neither may start a page itself.
     *
     * @throws IllegalArgumentException when the columns and the gap are wider than the current column
     */
    public void beside(float firstWidth, float gap, Runnable first, Runnable second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (firstWidth <= 0 || gap < 0 || firstWidth + gap >= width) {
            throw new IllegalArgumentException("The first column and the gap leave no room for the second");
        }
        keepTogether(() -> {
            float top = y;
            float savedLeft = left;
            float savedWidth = width;
            try {
                width = firstWidth;
                first.run();
                float firstBottom = y;
                y = top;
                left = savedLeft + firstWidth + gap;
                width = savedWidth - firstWidth - gap;
                second.run();
                y = Math.min(firstBottom, y);
            } finally {
                left = savedLeft;
                width = savedWidth;
            }
        });
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
        for (String line : wrap(PdfFonts.printable(block.text()), font, block.size(), width)) {
            if (lineHeight > remaining()) {
                newPage();
            }
            y -= lineHeight;
            show(line, font, block.size(), left, width, block.align(), y + block.size() * 0.28f);
        }
        y -= Math.min(block.spaceAfter(), remaining());
    }

    /**
     * A paragraph that opens {@code uri} when clicked (a PDF link annotation with a URI action over each of its
     * lines), underlined in a link colour. The URI itself is not printed.
     */
    public void link(TextBlock block, String uri) {
        Objects.requireNonNull(uri, "uri");
        PDType0Font font = fonts.get(block.face());
        float lineHeight = block.size() * LEADING;
        for (String line : wrap(PdfFonts.printable(block.text()), font, block.size(), width)) {
            if (lineHeight > remaining()) {
                newPage();
            }
            y -= lineHeight;
            float baseline = y + block.size() * 0.28f;
            float lineWidth = width(line, font, block.size());
            float x = switch (block.align()) {
                case LEFT -> left;
                case RIGHT -> left + width - lineWidth;
                case CENTER -> left + (width - lineWidth) / 2;
            };
            if (!measuring && !line.isEmpty()) {
                io(() -> {
                    content.setNonStrokingColor(LINK_COLOUR[0], LINK_COLOUR[1], LINK_COLOUR[2]);
                    content.setStrokingColor(LINK_COLOUR[0], LINK_COLOUR[1], LINK_COLOUR[2]);
                });
                show(line, font, block.size(), x, lineWidth, Align.LEFT, baseline);
                stroke(x, baseline - 1.2f, x + lineWidth, baseline - 1.2f, THIN);
                io(() -> {
                    content.setNonStrokingColor(0f, 0f, 0f);
                    content.setStrokingColor(0f, 0f, 0f);
                });
                annotate(x, y, lineWidth, lineHeight, uri);
            }
        }
        y -= Math.min(block.spaceAfter(), remaining());
    }

    /** A thin rule across the page under the cursor. */
    public void rule() {
        space(2);
        stroke(left, y, left + width, y, THIN);
        space(4);
    }

    /**
     * A mark in a frame: the text in semibold, centred in a box across the page (for example a reversal or an
     * annulment).
     */
    public void mark(String text, float fontSize) {
        PDType0Font font = fonts.get(Face.SEMIBOLD);
        List<String> lines = wrap(PdfFonts.printable(text), font, fontSize, width - 4 * CELL_PADDING);
        float lineHeight = fontSize * LEADING;
        float height = lines.size() * lineHeight + 2 * CELL_PADDING;
        if (height > remaining()) {
            newPage();
        }
        float top = y;
        for (String line : lines) {
            y -= lineHeight;
            show(line, font, fontSize, left + CELL_PADDING, width - 2 * CELL_PADDING, Align.CENTER,
                    y + fontSize * 0.28f - CELL_PADDING);
        }
        y -= 2 * CELL_PADDING;
        rectangle(left, y, width, top - y, 1.2f);
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
        float textWidth = width - lineWidth - mm(4);
        String printable = fit(PdfFonts.printable(text), font, fontSize, textWidth);
        show(printable, font, fontSize, left, textWidth, Align.LEFT, y);
        float lineStart = left + width - lineWidth;
        stroke(lineStart, y - 1.5f, left + width, y - 1.5f, THIN);
        y -= captionSize * LEADING;
        show(PdfFonts.printable(caption), font, captionSize, lineStart, lineWidth, Align.CENTER, y);
        y -= Math.min(fontSize * 0.4f, remaining());
    }

    /**
     * What a {@link #drawing} draws: shapes on the page's content stream in the box it is given (PDF points, the
     * origin at the lower left of the page).
     */
    @FunctionalInterface
    public interface Drawing {

        /**
         * Draws in the box.
         *
         * @param content the content stream of the page
         * @param left    the left edge of the box (the left edge of the column)
         * @param bottom  the bottom edge of the box
         * @param width   the width of the box (the width of the column)
         * @param height  the height of the box, as asked
         */
        void draw(PDPageContentStream content, float left, float bottom, float width, float height)
                throws IOException;
    }

    /**
     * A box of the given height across the column under the cursor, drawn by the caller (for example the payment QR
     * code of an invoice); a box that does not fit goes to a new page. The cursor moves below it. Nothing is drawn
     * while measuring.
     *
     * @throws IllegalArgumentException when the height is not above zero or more than a page holds
     */
    public void drawing(float height, Drawing drawing) {
        Objects.requireNonNull(drawing, "drawing");
        if (height <= 0 || height > size.height() - 2 * size.margin) {
            throw new IllegalArgumentException("A drawing is above zero and at most a page high");
        }
        if (height > remaining()) {
            newPage();
        }
        float bottom = y - height;
        if (!measuring) {
            io(() -> drawing.draw(content, left, bottom, width, height));
        }
        y = bottom;
    }

    /** A table; a row that does not fit goes to a new page, where the header is repeated. */
    public void table(Table table) {
        float total = (float) table.columns().stream().mapToDouble(Column::weight).sum();
        float[] widths = new float[table.columns().size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = width * table.columns().get(i).weight() / total;
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
        float x = left;
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
        stroke(left, y, left + width, y, header ? 0.9f : THIN * 0.6f);
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

    private void show(String line, PDType0Font font, float fontSize, float boxLeft, float boxWidth, Align align,
            float baseline) {
        if (line.isEmpty() || measuring) {
            return;
        }
        float lineWidth = width(line, font, fontSize);
        float x = switch (align) {
            case LEFT -> boxLeft;
            case RIGHT -> boxLeft + boxWidth - lineWidth;
            case CENTER -> boxLeft + (boxWidth - lineWidth) / 2;
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
        if (measuring) {
            return;
        }
        io(() -> {
            content.setLineWidth(lineWidth);
            content.moveTo(x1, y1);
            content.lineTo(x2, y2);
            content.stroke();
        });
    }

    private void rectangle(float x, float bottom, float boxWidth, float height, float lineWidth) {
        if (measuring) {
            return;
        }
        io(() -> {
            content.setLineWidth(lineWidth);
            content.addRect(x, bottom, boxWidth, height);
            content.stroke();
        });
    }

    /** A link annotation without a border over the box, opening the URI. */
    private void annotate(float x, float bottom, float boxWidth, float height, String uri) {
        PDActionURI action = new PDActionURI();
        action.setURI(uri);
        PDAnnotationLink link = new PDAnnotationLink();
        link.setAction(action);
        link.setRectangle(new PDRectangle(x, bottom, boxWidth, height));
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);
        io(() -> {
            List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
            annotations.add(link);
            page.setAnnotations(annotations);
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
