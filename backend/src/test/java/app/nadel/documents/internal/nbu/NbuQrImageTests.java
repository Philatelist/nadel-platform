package app.nadel.documents.internal.nbu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import app.nadel.documents.internal.nbu.NbuQrPayload.Invoice;
import app.nadel.money.Money;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.YearMonth;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The image of the NBU payment QR code (NBU Board Resolution No. 97, Annex 1 items 5–13, Annex 4 item 9): level M,
 * versions 10 to 17, the quiet zone, the background circle of item 11 and the sign 4 modules smaller; the code still
 * decodes with ZXing after the circle and the sign are drawn, as a PNG and as vector shapes on a PDF page.
 */
class NbuQrImageTests {

    private static final int PX = 8;

    private static String demoLink() {
        return NbuQrPayload.of(new Invoice("Садівниче товариство «Надія»", "UA213223130000026007233566001",
                Money.parse("413.26"), "12345678", "9", YearMonth.of(2026, 10), "2026-10/9")).link();
    }

    /** A link of {@code length} bytes: the start code and Base64URL-like characters. */
    private static String link(int length) {
        StringBuilder link = new StringBuilder(NbuQrPayload.START);
        String alphabet = "QkNECjAwMwoyClhDVAoK0eDks-Lt6PflIPLu4uDw6PHy4u4gq83g5LP_uwpVQTIxMzIyMzEzMDAw";
        for (int i = 0; link.length() < length; i++) {
            link.append(alphabet.charAt(i % alphabet.length()));
        }
        return link.toString();
    }

    private static String decode(BufferedImage image) throws Exception {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }

    private static BufferedImage read(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @ParameterizedTest(name = "version {0}: {1} modules")
    @CsvSource({"10, 17", "11, 19", "12, 19", "13, 21", "14, 23", "15, 23", "16, 25", "17, 25"})
    void drawsTheBackgroundCircleOfAnnex1Item11(int version, int diameter) {
        assertThat(NbuQrImage.circleDiameter(version)).isEqualTo(diameter);
    }

    @ParameterizedTest
    @CsvSource({"9", "18", "20"})
    void knowsNoCircleOutsideVersions10To17(int version) {
        assertThatThrownBy(() -> NbuQrImage.circleDiameter(version)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "a link of {0} bytes: version {1}")
    @CsvSource({"150, 10", "230, 11", "270, 12", "310, 13", "350, 14", "390, 15", "430, 16", "480, 17", "498, 17"})
    void picksTheVersionByTheLinkAndSizesTheCirclesByIt(int length, int version) {
        NbuQrImage image = NbuQrImage.of(link(length));

        assertThat(image.version()).isEqualTo(version);
        assertThat(image.codeModules()).isEqualTo(version * 4 + 17);
        assertThat(image.sideModules()).isEqualTo(version * 4 + 17 + 2 * NbuQrImage.QUIET_ZONE);
        assertThat(image.circleDiameter()).isEqualTo(NbuQrImage.circleDiameter(version));
        assertThat(image.signDiameter()).isEqualTo(image.circleDiameter() - 4);
        assertThat(image.centre()).isEqualTo(image.sideModules() / 2.0);
        assertThat(image.content()).isEqualTo(link(length));
    }

    @Test
    void forcesVersion10ForAShortLink() {
        NbuQrImage image = NbuQrImage.of(NbuQrPayload.START + "QkNE");

        assertThat(image.version()).isEqualTo(10);
        assertThat(image.codeModules()).isEqualTo(57);
        assertThat(image.circleDiameter()).isEqualTo(17);
    }

    @Test
    void refusesALinkThatNeedsAVersionAbove17() {
        assertThatThrownBy(() -> NbuQrImage.of(link(520))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesALinkThatIsNotPrintableIso646() {
        assertThatThrownBy(() -> NbuQrImage.of(NbuQrPayload.START + "Ж")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NbuQrImage.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePngDecodesToTheLinkWithTheCircleAndTheSign() throws Exception {
        for (String link : new String[] {demoLink(), link(330), link(498)}) {
            NbuQrImage image = NbuQrImage.of(link);

            BufferedImage png = read(image.png(PX));

            assertThat(png.getWidth()).isEqualTo(image.sideModules() * PX);
            assertThat(png.getHeight()).isEqualTo(image.sideModules() * PX);
            assertThat(decode(png)).isEqualTo(link);
        }
    }

    @Test
    void thePngHasTheQuietZoneTheWhiteRingAndTheBlackSign() throws IOException {
        NbuQrImage image = NbuQrImage.of(demoLink());
        BufferedImage png = read(image.png(PX));
        int centre = (int) (image.centre() * PX);
        int ring = (int) ((image.centre() + image.circleDiameter() / 2.0 - 1) * PX);

        assertThat(png.getRaster().getSample(1, 1, 0)).isEqualTo(255); // quiet zone
        assertThat(png.getRaster().getSample(NbuQrImage.QUIET_ZONE * PX - 1, NbuQrImage.QUIET_ZONE * PX - 1, 0))
                .isEqualTo(255);
        assertThat(png.getRaster().getSample(NbuQrImage.QUIET_ZONE * PX + 1, NbuQrImage.QUIET_ZONE * PX + 1, 0))
                .isEqualTo(0); // the finder pattern's corner
        assertThat(png.getRaster().getSample(ring, centre, 0)).isEqualTo(255);
        assertThat(png.getRaster().getSample(centre, ring, 0)).isEqualTo(255);
        int dark = 0;
        int half = image.signDiameter() * PX / 2;
        for (int y = centre - half; y < centre + half; y++) {
            for (int x = centre - half; x < centre + half; x++) {
                boolean inSignCircle = Math.hypot(x - centre, y - centre) <= half;
                if (inSignCircle && png.getRaster().getSample(x, y, 0) < 128) {
                    dark++;
                }
            }
        }
        assertThat(dark).as("the sign inside its circle").isGreaterThan(half * half / 4);
    }

    @Test
    void thePngIsTheSameEveryTime() {
        NbuQrImage image = NbuQrImage.of(demoLink());

        assertThat(image.png(PX)).isEqualTo(NbuQrImage.of(demoLink()).png(PX));
        assertThatThrownBy(() -> image.png(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theVectorDrawingOnAPdfPageDecodesAt300Dpi() throws Exception {
        String link = demoLink();
        NbuQrImage image = NbuQrImage.of(link);
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                image.drawPdf(content, 300, 500, 0.5f);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            pdf = out.toByteArray();
        }

        try (PDDocument document = Loader.loadPDF(pdf)) {
            BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, 300, ImageType.GRAY);
            assertThat(decode(rendered)).isEqualTo(link);
        }
        assertThat(image.sidePoints(0.5f)).isCloseTo(image.sideModules() * 0.5f * 72 / 25.4f, within(0.01f));
    }

    @Test
    void inscribesTheSignInTheCircleFourModulesSmaller() {
        NbuQrImage image = NbuQrImage.of(demoLink());

        Rectangle2D bounds = image.signShape().getBounds2D();

        assertThat(image.version()).isEqualTo(12); // the demo link has 255 bytes
        assertThat(image.signDiameter()).isEqualTo(15);
        assertThat(Math.hypot(bounds.getWidth(), bounds.getHeight())).isCloseTo(15.0, within(1e-6));
        assertThat(bounds.getCenterX()).isCloseTo(image.centre(), within(1e-6));
        assertThat(bounds.getCenterY()).isCloseTo(image.centre(), within(1e-6));
        assertThat(bounds.getHeight()).isGreaterThan(bounds.getWidth()); // the glyph ₴ is taller than wide
    }

    @Test
    void theStandardSignIsTheGlyphOfTheEmbeddedFont() {
        Rectangle2D bounds = HryvniaSign.standard().outline().getBounds2D();

        assertThat(HryvniaSign.standard()).isSameAs(GlyphHryvniaSign.INSTANCE);
        assertThat(bounds.getWidth()).isPositive();
        assertThat(bounds.getHeight()).isPositive();
        assertThat(bounds.getMaxY()).as("flipped: y points down").isGreaterThan(0).isLessThan(50);
    }

    @Test
    void anotherSignSourceReplacesTheGlyph() {
        HryvniaSign square = () -> new Rectangle2D.Double(0, 0, 10, 10);

        Shape sign = NbuQrImage.of(demoLink(), square).signShape();

        Rectangle2D bounds = sign.getBounds2D();
        assertThat(bounds.getWidth()).isCloseTo(15.0 / Math.sqrt(2), within(1e-6));
        assertThat(bounds.getHeight()).isCloseTo(15.0 / Math.sqrt(2), within(1e-6));
    }
}
