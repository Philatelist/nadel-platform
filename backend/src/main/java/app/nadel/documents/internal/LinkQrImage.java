package app.nadel.documents.internal;

import app.nadel.documents.internal.pdf.PdfDocument;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

/**
 * A plain QR code of a link (feature 017: the small code «Отримувати рахунки в Telegram» of a paper copy, holding the
 * recipient's deep link to the cooperative's bot): ZXing's matrix with error correction level M and a quiet zone of
 * {@value #QUIET_ZONE} modules, drawn as vector rectangles. Unlike the NBU payment code it has no sign in the middle
 * and no fixed version.
 */
final class LinkQrImage {

    static final int QUIET_ZONE = 4;

    private final boolean[][] dark;

    private LinkQrImage(boolean[][] dark) {
        this.dark = dark;
    }

    /** The code of the link (printable ASCII). */
    static LinkQrImage of(String link) {
        Objects.requireNonNull(link, "link");
        if (link.isEmpty() || !link.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            throw new IllegalArgumentException("A QR link is printable ASCII");
        }
        QRCode code;
        try {
            code = Encoder.encode(link, ErrorCorrectionLevel.M, Map.of(EncodeHintType.CHARACTER_SET, "ISO-8859-1"));
        } catch (WriterException e) {
            throw new IllegalArgumentException("The link does not fit a QR code", e);
        }
        ByteMatrix matrix = code.getMatrix();
        boolean[][] dark = new boolean[matrix.getHeight()][matrix.getWidth()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                dark[y][x] = matrix.get(x, y) == 1;
            }
        }
        return new LinkQrImage(dark);
    }

    /** The side of the whole image (quiet zone included) in modules. */
    int sideModules() {
        return dark.length + 2 * QUIET_ZONE;
    }

    /**
     * Draws the code, white background with the quiet zone and the dark modules as one filled path, into a square of
     * {@code side} points whose bottom left corner is ({@code x}, {@code y}).
     */
    void drawPdf(PDPageContentStream content, float x, float y, float side) {
        float module = side / sideModules();
        float top = y + side;
        try {
            content.saveGraphicsState();
            content.setNonStrokingColor(1f);
            content.addRect(x, y, side, side);
            content.fill();
            content.setNonStrokingColor(0f);
            for (int row = 0; row < dark.length; row++) {
                int col = 0;
                while (col < dark.length) {
                    if (!dark[row][col]) {
                        col++;
                        continue;
                    }
                    int start = col;
                    while (col < dark.length && dark[row][col]) {
                        col++;
                    }
                    content.addRect(x + (QUIET_ZONE + start) * module, top - (QUIET_ZONE + row + 1) * module,
                            (col - start) * module, module);
                }
            }
            content.fill();
            content.restoreGraphicsState();
        } catch (IOException e) {
            throw new UncheckedIOException("The QR code cannot be drawn", e);
        }
    }

    /** The side in PDF points of a code {@code mm} millimetres wide. */
    static float points(float mm) {
        return PdfDocument.mm(mm);
    }
}
