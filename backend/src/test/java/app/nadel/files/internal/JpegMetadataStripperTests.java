package app.nadel.files.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.files.MalformedImageException;
import app.nadel.files.TestImages;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Metadata leaves a JPEG without re-encoding (feature 018 §2.3, technical considerations 2.6): no Exif with its GPS and
 * camera IFDs, no XMP, no MPF index or second image, no Photoshop IPTC, no comment, nothing after the first EOI; the
 * Orientation stays; the image data is byte for byte the same; a broken file is refused.
 */
class JpegMetadataStripperTests {

    @Test
    void aCameraPhotoLosesItsGpsPositionCameraDataXmpAndEverythingAfterTheImage() throws IOException {
        byte[] photo = TestImages.cameraJpeg(false);
        assertThat(TestImages.contains(photo, TestImages.CAMERA)).as("the fixture has camera data").isTrue();
        assertThat(indexOf(photo, GPS_POINTER)).as("the fixture has a GPS IFD").isPositive();

        byte[] stripped = JpegMetadataStripper.strip(photo);

        for (String marker : TestImages.MARKERS) {
            assertThat(TestImages.contains(stripped, marker)).as(marker).isFalse();
        }
        assertThat(indexOf(stripped, GPS_POINTER)).as("the GPS IFD pointer").isNegative();
        List<Integer> markers = markers(stripped);
        assertThat(markers).startsWith(0xD8, 0xE0, 0xE1).endsWith(0xDA, 0xD9).contains(0xDB, 0xC0, 0xC4)
                .doesNotContain(0xE2, 0xED, 0xFE);
        assertThat(markers.stream().filter(m -> m == 0xE1).count()).isEqualTo(1);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(stripped));
        assertThat(image.getWidth()).isEqualTo(32);
        assertThat(image.getHeight()).isEqualTo(16);
    }

    @Test
    void theOrientationIsKeptInAMinimalExifSegmentRightAfterJfif() {
        byte[] stripped = JpegMetadataStripper.strip(TestImages.cameraJpeg(false));

        byte[] exif = JpegMetadataStripper.orientationSegment(TestImages.ORIENTATION);
        int at = indexOf(stripped, exif);
        int app0Length = ((stripped[4] & 0xFF) << 8) | (stripped[5] & 0xFF);
        assertThat(at).isEqualTo(2 + 2 + app0Length);
        assertThat(JpegMetadataStripper.orientation(stripped, at + 10, at + exif.length))
                .isEqualTo(TestImages.ORIENTATION);
        assertThat(indexOf(Arrays.copyOfRange(stripped, at + exif.length, stripped.length), new byte[] {'E', 'x',
                'i', 'f'})).as("only one Exif segment").isNegative();
    }

    @Test
    void aPhotoWithoutMetadataKeepsItsImageDataByteForByte() {
        byte[] plain = TestImages.jpeg(false);

        assertThat(JpegMetadataStripper.strip(plain)).isEqualTo(plain);
        assertThat(JpegMetadataStripper.strip(TestImages.cameraJpeg(false))).isEqualTo(withOrientation(plain));
    }

    @Test
    void aProgressivePhotoKeepsEveryScan() throws IOException {
        byte[] plain = TestImages.jpeg(true);
        byte[] stripped = JpegMetadataStripper.strip(TestImages.cameraJpeg(true));

        assertThat(markers(stripped).stream().filter(m -> m == 0xDA).count()).isGreaterThan(1);
        assertThat(markers(stripped)).contains(0xC2);
        assertThat(stripped).isEqualTo(withOrientation(plain));
        assertThat(ImageIO.read(new ByteArrayInputStream(stripped)).getWidth()).isEqualTo(32);
    }

    @Test
    void anMpfFileLosesItsIndexAndItsSecondImage() {
        byte[] photo = TestImages.cameraJpeg(false);
        assertThat(count(photo, new byte[] {(byte) 0xFF, (byte) 0xD8})).as("two images in the fixture")
                .isEqualTo(2);
        byte[] stripped = JpegMetadataStripper.strip(photo);

        assertThat(TestImages.contains(stripped, "MPF")).isFalse();
        assertThat(count(stripped, new byte[] {(byte) 0xFF, (byte) 0xD8})).isEqualTo(1);
        assertThat(stripped[stripped.length - 2] & 0xFF).isEqualTo(0xFF);
        assertThat(stripped[stripped.length - 1] & 0xFF).isEqualTo(0xD9);
    }

    @Test
    void iccProfilesAdobeSegmentsRestartMarkersAndFillBytesAreKept() {
        byte[] plain = TestImages.jpeg(false);
        int app0End = 4 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        byte[] icc = TestImages.segment(0xE2, TestImages.concat("ICC_PROFILE\0".getBytes(), new byte[] {1, 1, 9}));
        byte[] adobe = TestImages.segment(0xEE, "Adobe\0d\0\0\0\0\1".getBytes());
        byte[] flash = TestImages.segment(0xE2, "FPXR\0data".getBytes());
        byte[] withSegments = TestImages.concat(Arrays.copyOfRange(plain, 0, app0End), icc, flash, adobe,
                new byte[] {(byte) 0xFF}, Arrays.copyOfRange(plain, app0End, plain.length));

        byte[] stripped = JpegMetadataStripper.strip(withSegments);

        assertThat(indexOf(stripped, icc)).isPositive();
        assertThat(indexOf(stripped, adobe)).isPositive();
        assertThat(TestImages.contains(stripped, "FPXR")).isFalse();
    }

    @Test
    void anExifWithoutAUsableOrientationLeavesNoExifAtAll() {
        byte[] plain = TestImages.jpeg(false);
        int app0End = 4 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        byte[] brokenExif = TestImages.segment(0xE1, "Exif\0\0XX*\0".getBytes());
        byte[] stripped = JpegMetadataStripper.strip(TestImages.concat(Arrays.copyOfRange(plain, 0, app0End),
                brokenExif, Arrays.copyOfRange(plain, app0End, plain.length)));

        assertThat(stripped).isEqualTo(plain);
        assertThat(JpegMetadataStripper.orientation(tiff(0x0112, 3, 1, 9), 0, 26)).as("outside 1..8").isNull();
        assertThat(JpegMetadataStripper.orientation(tiff(0x0112, 4, 1, 6), 0, 26)).as("not a SHORT").isNull();
        assertThat(JpegMetadataStripper.orientation(tiff(0x0112, 3, 2, 6), 0, 26)).as("two values").isNull();
        assertThat(JpegMetadataStripper.orientation(tiff(0x0110, 3, 1, 6), 0, 26)).as("another tag").isNull();
        assertThat(JpegMetadataStripper.orientation(tiff(0x0112, 3, 1, 6), 0, 20)).as("cut short").isNull();
        assertThat(JpegMetadataStripper.orientation(tiff(0x0112, 3, 1, 6), 0, 26)).isEqualTo(6);
        assertThat(JpegMetadataStripper.orientation(littleEndianTiff(3), 0, 26)).isEqualTo(3);
        assertThat(JpegMetadataStripper.orientation(new byte[] {'M', 'M', 0, 43, 0, 0, 0, 8}, 0, 8)).isNull();
        assertThat(JpegMetadataStripper.orientation(new byte[] {'M', 'M', 0, 42, 0, 0, 0, 4, 0, 0}, 0, 10))
                .as("an IFD inside the header").isNull();
        assertThat(JpegMetadataStripper.orientation(new byte[] {'M', 'M', 0, 42}, 0, 4)).isNull();
    }

    @Test
    void aBrokenJpegIsRefused() {
        byte[] plain = TestImages.jpeg(false);
        List<byte[]> broken = new ArrayList<>();
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD8});
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD9, 0, 0});
        broken.add(Arrays.copyOf(plain, plain.length / 2));
        broken.add(Arrays.copyOf(plain, plain.length - 2));
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x7F, 0x00, 1, 2});
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x01, 1, 2});
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x12, 0x34, (byte) 0xFF, (byte) 0xD9});
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xFF});
        broken.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB});
        // tables but no frame or scan
        broken.add(TestImages.concat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                TestImages.segment(0xDB, new byte[] {0, 1, 2}), new byte[] {(byte) 0xFF, (byte) 0xD9}));

        for (byte[] file : broken) {
            assertThatThrownBy(() -> JpegMetadataStripper.strip(file)).isInstanceOf(MalformedImageException.class);
        }
    }

    // ---- helpers

    /** The GPS IFD pointer entry of IFD0: tag 0x8825, type LONG. */
    private static final byte[] GPS_POINTER = {(byte) 0x88, 0x25, 0x00, 0x04};

    private static byte[] withOrientation(byte[] plain) {
        int app0End = 4 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        return TestImages.concat(Arrays.copyOfRange(plain, 0, app0End),
                JpegMetadataStripper.orientationSegment(TestImages.ORIENTATION),
                Arrays.copyOfRange(plain, app0End, plain.length));
    }

    /** A big-endian TIFF with one IFD0 entry (26 bytes). */
    private static byte[] tiff(int tag, int type, int count, int value) {
        return new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, (byte) (tag >> 8), (byte) tag, 0, (byte) type, 0, 0, 0,
            (byte) count, 0, (byte) value, 0, 0, 0, 0, 0, 0};
    }

    private static byte[] littleEndianTiff(int value) {
        return new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) value, 0, 0, 0, 0, 0,
            0, 0};
    }

    /** The markers of the segments outside the scans, in order (restart markers inside scans are skipped). */
    private static List<Integer> markers(byte[] jpeg) {
        List<Integer> markers = new ArrayList<>();
        markers.add(jpeg[1] & 0xFF);
        int pos = 2;
        while (pos + 1 < jpeg.length) {
            int marker = jpeg[pos + 1] & 0xFF;
            markers.add(marker);
            if (marker == 0xD9) {
                break;
            }
            int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            pos += 2 + length;
            if (marker == 0xDA) {
                while (!((jpeg[pos] & 0xFF) == 0xFF && (jpeg[pos + 1] & 0xFF) != 0
                        && ((jpeg[pos + 1] & 0xFF) < 0xD0 || (jpeg[pos + 1] & 0xFF) > 0xD7))) {
                    pos++;
                }
            }
        }
        return markers;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int count(byte[] haystack, byte[] needle) {
        int count = 0;
        for (int at = indexOf(haystack, needle); at >= 0; ) {
            count++;
            int next = indexOf(Arrays.copyOfRange(haystack, at + 1, haystack.length), needle);
            at = next < 0 ? -1 : at + 1 + next;
        }
        return count;
    }
}
