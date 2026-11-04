package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.treasury.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Metadata leaves a PNG without re-encoding (feature 018 §2.3, technical considerations 2.6): no {@code eXIf} (Exif
 * with GPS), {@code tEXt}, {@code iTXt} (XMP), {@code zTXt} or {@code tIME}, nothing after IEND; the image and colour
 * chunks stay byte for byte; a broken file is refused.
 */
class PngMetadataStripperTests {

    @Test
    void aPngLosesItsExifTextsXmpAndTimeButKeepsItsImageAndColourChunks() throws IOException {
        byte[] png = TestImages.metadataPng();
        assertThat(chunkTypes(png)).contains("eXIf", "tEXt", "iTXt", "zTXt", "tIME");

        byte[] stripped = PngMetadataStripper.strip(png);

        assertThat(chunkTypes(stripped)).containsExactly("IHDR", "gAMA", "pHYs", "IDAT", "IEND");
        for (String marker : TestImages.MARKERS) {
            assertThat(TestImages.contains(stripped, marker)).as(marker).isFalse();
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(stripped));
        assertThat(image.getWidth()).isEqualTo(32);
        assertThat(image.getHeight()).isEqualTo(16);
    }

    @Test
    void aPngWithoutMetadataStaysByteForByte() {
        byte[] plain = TestImages.png();

        assertThat(PngMetadataStripper.strip(plain)).isEqualTo(plain);
    }

    @Test
    void animationAndPaletteChunksAreKeptAndUnknownAncillaryChunksDropped() {
        byte[] plain = TestImages.png();
        int afterIhdr = 8 + 12 + 13;
        byte[] withChunks = TestImages.concat(Arrays.copyOfRange(plain, 0, afterIhdr),
                TestImages.chunk("acTL", new byte[] {0, 0, 0, 1, 0, 0, 0, 0}),
                TestImages.chunk("sRGB", new byte[] {0}), TestImages.chunk("prVt", new byte[] {1, 2, 3}),
                Arrays.copyOfRange(plain, afterIhdr, plain.length));

        assertThat(chunkTypes(PngMetadataStripper.strip(withChunks))).containsExactly("IHDR", "acTL", "sRGB", "IDAT",
                "IEND");
    }

    @Test
    void aBrokenPngIsRefused() {
        byte[] plain = TestImages.png();
        int afterIhdr = 8 + 12 + 13;
        List<byte[]> broken = new ArrayList<>();
        broken.add(Arrays.copyOf(plain, 6));
        broken.add(TestImages.concat(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0B},
                Arrays.copyOfRange(plain, 8, plain.length)));
        broken.add(Arrays.copyOf(plain, plain.length - 12));
        broken.add(Arrays.copyOf(plain, plain.length - 3));
        byte[] badCrc = plain.clone();
        badCrc[afterIhdr - 1] ^= 0x01;
        broken.add(badCrc);
        byte[] tooLong = plain.clone();
        tooLong[8] = 0x7F;
        broken.add(tooLong);
        // the first chunk is not IHDR, or IHDR comes twice
        broken.add(TestImages.concat(Arrays.copyOf(plain, 8), TestImages.chunk("gAMA", new byte[] {0, 0, 0, 1}),
                Arrays.copyOfRange(plain, 8, plain.length)));
        broken.add(TestImages.concat(Arrays.copyOf(plain, afterIhdr), Arrays.copyOfRange(plain, 8, plain.length)));
        // an unknown critical chunk, a chunk type that is not letters, no image data
        broken.add(TestImages.concat(Arrays.copyOf(plain, afterIhdr), TestImages.chunk("ABCD", new byte[0]),
                Arrays.copyOfRange(plain, afterIhdr, plain.length)));
        broken.add(TestImages.concat(Arrays.copyOf(plain, afterIhdr), TestImages.chunk("a1cd", new byte[0]),
                Arrays.copyOfRange(plain, afterIhdr, plain.length)));
        broken.add(TestImages.concat(Arrays.copyOf(plain, afterIhdr), TestImages.chunk("IEND", new byte[0])));

        for (byte[] file : broken) {
            assertThatThrownBy(() -> PngMetadataStripper.strip(file)).isInstanceOf(MalformedImageException.class);
        }
    }

    /** The chunk types of a PNG in order, up to IEND. */
    private static List<String> chunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        int pos = 8;
        while (pos + 8 <= png.length) {
            int length = ((png[pos] & 0xFF) << 24) | ((png[pos + 1] & 0xFF) << 16) | ((png[pos + 2] & 0xFF) << 8)
                    | (png[pos + 3] & 0xFF);
            String type = new String(png, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            types.add(type);
            if (type.equals("IEND")) {
                break;
            }
            pos += 12 + length;
        }
        return types;
    }
}
