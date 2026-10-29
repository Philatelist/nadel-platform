package app.nadel.documents.internal.nbu;

import app.nadel.documents.internal.pdf.PdfDocument;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

/**
 * The image of an NBU payment QR code (NBU Board Resolution No. 97, Annex 1 items 5–13 and Annex 4 items 9 and 10.4;
 * {@code docs/domain/nbu-qr.md}): the module matrix from ZXing with error correction level M, QR version 10 to 17
 * (10 is forced when the link would fit a smaller one, more than 17 is refused), a quiet zone of 4 modules, a white
 * background circle centred on the code with the diameter of Annex 1 item 11 and the hryvnia sign inscribed in a
 * circle 4 modules smaller (item 12). Immutable; the geometry is in modules, the origin at the top left corner of the
 * whole image (quiet zone included), the y axis pointing down.
 * <p>
 * The same geometry is drawn as vector shapes into a PDF page ({@link #drawPdf}) and as a PNG ({@link #png}).
 */
public final class NbuQrImage {

    /** Annex 1 item 5 allows 10 to 20; Annex 4 item 9 caps format 003 at 17. */
    static final int MIN_VERSION = 10;
    static final int MAX_VERSION = 17;
    /** The quiet zone around the code, in modules. */
    public static final int QUIET_ZONE = 4;
    /** Annex 1 item 12: the sign's circle is this many modules smaller than the background circle. */
    static final int SIGN_INSET = 4;

    /** A cubic Bézier quarter circle: the control point distance for a radius of 1. */
    private static final double KAPPA = 0.5522847498307936;

    private final String content;
    private final int version;
    private final boolean[][] dark;
    private final HryvniaSign sign;

    private NbuQrImage(String content, int version, boolean[][] dark, HryvniaSign sign) {
        this.content = content;
        this.version = version;
        this.dark = dark;
        this.sign = sign;
    }

    /**
     * The image of the link ({@link NbuQrPayload#link()}).
     *
     * @throws IllegalArgumentException when the link is not printable ISO 646 or needs a QR version above 17
     */
    public static NbuQrImage of(String link) {
        return of(link, HryvniaSign.standard());
    }

    static NbuQrImage of(String link, HryvniaSign sign) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(sign, "sign");
        if (link.isEmpty() || !link.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            throw new IllegalArgumentException("A QR link is printable ISO 646 text");
        }
        QRCode code = encode(link, Map.of());
        if (code.getVersion().getVersionNumber() < MIN_VERSION) {
            code = encode(link, Map.of(EncodeHintType.QR_VERSION, MIN_VERSION));
        }
        int version = code.getVersion().getVersionNumber();
        if (version > MAX_VERSION) {
            throw new IllegalArgumentException("The QR link needs version " + version + ", above " + MAX_VERSION);
        }
        ByteMatrix matrix = code.getMatrix();
        boolean[][] dark = new boolean[matrix.getHeight()][matrix.getWidth()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                dark[y][x] = matrix.get(x, y) == 1;
            }
        }
        return new NbuQrImage(link, version, dark, sign);
    }

    /** Annex 1 item 11: the background circle's diameter in modules for a QR version from 10 to 17. */
    static int circleDiameter(int version) {
        return switch (version) {
            case 10 -> 17;
            case 11, 12 -> 19;
            case 13 -> 21;
            case 14, 15 -> 23;
            case 16, 17 -> 25;
            default -> throw new IllegalArgumentException("Format 003 uses QR versions 10 to 17, not " + version);
        };
    }

    /** The link the code holds. */
    public String content() {
        return content;
    }

    /** The QR version, 10 to 17. */
    public int version() {
        return version;
    }

    /** The side of the code without the quiet zone: version × 4 + 17 modules (Annex 1 item 7). */
    public int codeModules() {
        return dark.length;
    }

    /** The side of the whole image with the quiet zone, in modules. */
    public int sideModules() {
        return codeModules() + 2 * QUIET_ZONE;
    }

    /** Whether the module of the code (without the quiet zone; column x, row y from the top left) is dark. */
    public boolean dark(int x, int y) {
        return dark[y][x];
    }

    /** The centre of both circles: the crossing of the code's diagonals (Annex 1 item 10), in image modules. */
    public double centre() {
        return sideModules() / 2.0;
    }

    /** The background circle's diameter in modules. */
    public int circleDiameter() {
        return circleDiameter(version);
    }

    /** The diameter of the circle the sign is inscribed in, in modules. */
    public int signDiameter() {
        return circleDiameter() - SIGN_INSET;
    }

    /** The side of the whole image in PDF points for a module of {@code moduleMm} millimetres. */
    public float sidePoints(float moduleMm) {
        return sideModules() * PdfDocument.mm(moduleMm);
    }

    /**
     * The side of the largest image format 003 can need (version {@value #MAX_VERSION} with the quiet zone) in PDF
     * points for a module of {@code moduleMm} millimetres: the space an invoice reserves for its code (Annex 1 item
     * 17).
     */
    public static float maxSidePoints(float moduleMm) {
        return (MAX_VERSION * 4 + 17 + 2 * QUIET_ZONE) * PdfDocument.mm(moduleMm);
    }

    /**
     * Draws the image as vector shapes: a white square with the quiet zone, the dark modules as rectangles (one
     * filled path), the white background circle and the black sign.
     *
     * @param content the page's content stream
     * @param x the left edge of the image (quiet zone included), in PDF points
     * @param y the bottom edge of the image (quiet zone included), in PDF points
     * @param moduleMm the side of a module in millimetres (Annex 1 item 18: at least 0.5)
     */
    public void drawPdf(PDPageContentStream content, float x, float y, float moduleMm) {
        Objects.requireNonNull(content, "content");
        float module = PdfDocument.mm(moduleMm);
        float top = y + sideModules() * module;
        AffineTransform toPage = new AffineTransform(module, 0, 0, -module, x, top);
        try {
            content.saveGraphicsState();
            content.setNonStrokingColor(1f);
            content.addRect(x, y, sideModules() * module, sideModules() * module);
            content.fill();

            content.setNonStrokingColor(0f);
            for (int row = 0; row < codeModules(); row++) {
                int col = 0;
                while (col < codeModules()) {
                    if (!dark[row][col]) {
                        col++;
                        continue;
                    }
                    int start = col;
                    while (col < codeModules() && dark[row][col]) {
                        col++;
                    }
                    content.addRect(x + (QUIET_ZONE + start) * module, top - (QUIET_ZONE + row + 1) * module,
                            (col - start) * module, module);
                }
            }
            content.fill();

            content.setNonStrokingColor(1f);
            circle(content, x + (float) centre() * module, top - (float) centre() * module,
                    circleDiameter() * module / 2);
            content.fill();

            content.setNonStrokingColor(0f);
            boolean evenOdd = path(content, signShape(), toPage);
            if (evenOdd) {
                content.fillEvenOdd();
            } else {
                content.fill();
            }
            content.restoreGraphicsState();
        } catch (IOException e) {
            throw new UncheckedIOException("The QR code cannot be drawn", e);
        }
    }

    /** The image as a PNG, {@code pxPerModule} pixels per module, black on white; the circles anti-aliased. */
    public byte[] png(int pxPerModule) {
        if (pxPerModule < 1) {
            throw new IllegalArgumentException("A module is at least one pixel");
        }
        int side = sideModules() * pxPerModule;
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, side, side);
            g.setColor(Color.BLACK);
            for (int row = 0; row < codeModules(); row++) {
                for (int col = 0; col < codeModules(); col++) {
                    if (dark[row][col]) {
                        g.fillRect((QUIET_ZONE + col) * pxPerModule, (QUIET_ZONE + row) * pxPerModule, pxPerModule,
                                pxPerModule);
                    }
                }
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(pxPerModule, pxPerModule);
            g.setColor(Color.WHITE);
            double radius = circleDiameter() / 2.0;
            g.fill(new Ellipse2D.Double(centre() - radius, centre() - radius, 2 * radius, 2 * radius));
            g.setColor(Color.BLACK);
            g.fill(signShape());
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException("The QR image cannot be written", e);
        }
        return out.toByteArray();
    }

    /** The sign in image modules: inscribed in the sign circle around the centre. */
    Shape signShape() {
        return sign.inscribed(centre(), centre(), signDiameter());
    }

    private static QRCode encode(String link, Map<EncodeHintType, ?> hints) {
        try {
            return Encoder.encode(link, ErrorCorrectionLevel.M, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException("The QR link is too long for a QR code", e);
        }
    }

    private static void circle(PDPageContentStream content, float cx, float cy, float r) throws IOException {
        float k = (float) (KAPPA * r);
        content.moveTo(cx + r, cy);
        content.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        content.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        content.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        content.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        content.closePath();
    }

    /** Adds the shape to the current path in page coordinates; true when it is filled by the even-odd rule. */
    private static boolean path(PDPageContentStream content, Shape shape, AffineTransform toPage)
            throws IOException {
        PathIterator it = shape.getPathIterator(toPage);
        double[] c = new double[6];
        double lastX = 0;
        double lastY = 0;
        double startX = 0;
        double startY = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    content.moveTo((float) c[0], (float) c[1]);
                    lastX = c[0];
                    lastY = c[1];
                    startX = lastX;
                    startY = lastY;
                }
                case PathIterator.SEG_LINETO -> {
                    content.lineTo((float) c[0], (float) c[1]);
                    lastX = c[0];
                    lastY = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    // a quadratic segment as the equal cubic: control points two thirds of the way to the quad's one
                    content.curveTo((float) (lastX + 2 * (c[0] - lastX) / 3), (float) (lastY + 2 * (c[1] - lastY) / 3),
                            (float) (c[2] + 2 * (c[0] - c[2]) / 3), (float) (c[3] + 2 * (c[1] - c[3]) / 3),
                            (float) c[2], (float) c[3]);
                    lastX = c[2];
                    lastY = c[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    content.curveTo((float) c[0], (float) c[1], (float) c[2], (float) c[3], (float) c[4],
                            (float) c[5]);
                    lastX = c[4];
                    lastY = c[5];
                }
                case PathIterator.SEG_CLOSE -> {
                    content.closePath();
                    lastX = startX;
                    lastY = startY;
                }
                default -> throw new IllegalStateException("Unknown path segment");
            }
            it.next();
        }
        return it.getWindingRule() == PathIterator.WIND_EVEN_ODD;
    }
}
