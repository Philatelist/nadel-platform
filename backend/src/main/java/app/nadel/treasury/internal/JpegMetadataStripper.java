package app.nadel.treasury.internal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Removes location, camera and other metadata from a JPEG attachment without re-encoding it (feature 018 §2.3,
 * technical considerations 2.6), JDK only. The file is read segment by segment:
 * <ul>
 *   <li>kept: APP0 (JFIF), APP14 (Adobe colour transform), APP2 {@code ICC_PROFILE}, the tables and frame segments
 *       (DQT, DHT, DAC, DRI, DNL, SOF0 to SOF15, the extension markers) and every scan (SOS with its entropy-coded
 *       data and restart markers, so progressive and multi-scan files stay intact);</li>
 *   <li>dropped: APP1 (Exif with its GPS and camera IFDs, XMP), every other APP2 (MPF, FlashPix), APP3 to APP13,
 *       APP15 and comments (COM);</li>
 *   <li>re-added: when the original Exif carried an Orientation tag (1 to 8), a minimal APP1 Exif holding only that
 *       tag, right after SOI and the leading APP0, so the photo is still shown the right way up;</li>
 *   <li>cut: everything after the first EOI (the extra images of an MPF file, trailing data).</li>
 * </ul>
 * A file that does not start with SOI, has a segment running past the end, ends before EOI or has no frame or scan is
 * refused with {@link MalformedImageException}. A malformed Exif segment only loses its orientation. Pure and
 * stateless.
 */
final class JpegMetadataStripper {

    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int APP2 = 0xE2;
    private static final int APP14 = 0xEE;
    private static final int COM = 0xFE;
    private static final int ORIENTATION_TAG = 0x0112;
    private static final int TIFF_SHORT = 3;

    private static final byte[] EXIF = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ICC_PROFILE = "ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII);

    private JpegMetadataStripper() {
    }

    /**
     * The JPEG without its metadata.
     *
     * @throws MalformedImageException when the structure cannot be read
     */
    static byte[] strip(byte[] in) {
        if (in.length < 4 || u8(in, 0) != 0xFF || u8(in, 1) != SOI) {
            throw new MalformedImageException("A JPEG starts with SOI");
        }
        ByteArrayOutputStream leading = new ByteArrayOutputStream();
        ByteArrayOutputStream rest = new ByteArrayOutputStream(in.length);
        boolean leadingPhase = true;
        boolean frame = false;
        boolean scan = false;
        boolean end = false;
        Integer orientation = null;
        int pos = 2;
        while (pos < in.length) {
            if (u8(in, pos) != 0xFF) {
                throw new MalformedImageException("A JPEG segment starts with a marker");
            }
            while (pos < in.length && u8(in, pos) == 0xFF) {
                pos++; // fill bytes before a marker
            }
            if (pos >= in.length) {
                throw new MalformedImageException("The JPEG ends inside a marker");
            }
            int marker = u8(in, pos++);
            if (marker == EOI) {
                end = true;
                break;
            }
            if (marker == 0x00 || marker == SOI) {
                throw new MalformedImageException("A stray marker in the JPEG");
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                rest.write(0xFF);
                rest.write(marker);
                leadingPhase = false;
                continue;
            }
            if (pos + 2 > in.length) {
                throw new MalformedImageException("The JPEG ends inside a segment length");
            }
            int length = (u8(in, pos) << 8) | u8(in, pos + 1);
            int segmentStart = pos - 2;
            int segmentEnd = pos + length;
            if (length < 2 || segmentEnd > in.length) {
                throw new MalformedImageException("A JPEG segment runs past the end");
            }
            int dataStart = pos + 2;
            pos = segmentEnd;
            if (marker == SOS) {
                rest.write(in, segmentStart, segmentEnd - segmentStart);
                int scanStart = pos;
                pos = endOfScan(in, pos);
                rest.write(in, scanStart, pos - scanStart);
                scan = true;
                leadingPhase = false;
                continue;
            }
            if (marker == APP1) {
                if (orientation == null && startsWith(in, dataStart, segmentEnd, EXIF)) {
                    orientation = orientation(in, dataStart + EXIF.length, segmentEnd);
                }
                continue;
            }
            boolean keep = marker == APP0 || marker == APP14
                    || (marker == APP2 && startsWith(in, dataStart, segmentEnd, ICC_PROFILE))
                    || (marker < APP0 || (marker > 0xEF && marker != COM));
            if (!keep) {
                continue;
            }
            if (isFrame(marker)) {
                frame = true;
            }
            if (marker == APP0 && leadingPhase) {
                leading.write(in, segmentStart, segmentEnd - segmentStart);
            } else {
                rest.write(in, segmentStart, segmentEnd - segmentStart);
                leadingPhase = false;
            }
        }
        if (!end || !frame || !scan) {
            throw new MalformedImageException("The JPEG has no complete image");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(0xFF);
        out.write(SOI);
        out.writeBytes(leading.toByteArray());
        if (orientation != null) {
            out.writeBytes(orientationSegment(orientation));
        }
        out.writeBytes(rest.toByteArray());
        out.write(0xFF);
        out.write(EOI);
        return out.toByteArray();
    }

    /** The position of the marker that ends the entropy-coded data starting at {@code pos}. */
    private static int endOfScan(byte[] in, int start) {
        int pos = start;
        while (true) {
            if (pos + 1 >= in.length) {
                throw new MalformedImageException("The JPEG ends inside a scan");
            }
            if (u8(in, pos) == 0xFF) {
                int next = u8(in, pos + 1);
                if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) {
                    pos += 2; // a stuffed FF or a restart marker: still the scan
                    continue;
                }
                if (next != 0xFF) {
                    return pos;
                }
            }
            pos++;
        }
    }

    /** SOF0 to SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    private static boolean isFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    /**
     * The Orientation of IFD0 of the TIFF structure in {@code [start, end)}, or null when it has none, it is not a
     * single SHORT from 1 to 8, or the structure cannot be read.
     */
    static @Nullable Integer orientation(byte[] in, int start, int end) {
        if (start + 8 > end) {
            return null;
        }
        boolean little;
        if (u8(in, start) == 'I' && u8(in, start + 1) == 'I') {
            little = true;
        } else if (u8(in, start) == 'M' && u8(in, start + 1) == 'M') {
            little = false;
        } else {
            return null;
        }
        if (u16(in, start + 2, little) != 42) {
            return null;
        }
        long ifd = u32(in, start + 4, little);
        if (ifd < 8 || start + ifd + 2 > end) {
            return null;
        }
        int ifdStart = start + (int) ifd;
        int count = u16(in, ifdStart, little);
        for (int i = 0; i < count; i++) {
            int entry = ifdStart + 2 + 12 * i;
            if (entry + 12 > end) {
                return null;
            }
            if (u16(in, entry, little) == ORIENTATION_TAG) {
                if (u16(in, entry + 2, little) != TIFF_SHORT || u32(in, entry + 4, little) != 1) {
                    return null;
                }
                int value = u16(in, entry + 8, little);
                return value >= 1 && value <= 8 ? value : null;
            }
        }
        return null;
    }

    /** A minimal APP1 Exif segment (big-endian TIFF) whose IFD0 holds only the Orientation tag. */
    static byte[] orientationSegment(int orientation) {
        return new byte[] {
            (byte) 0xFF, (byte) APP1, 0x00, 0x22,
            'E', 'x', 'i', 'f', 0x00, 0x00,
            'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
    }

    private static boolean startsWith(byte[] in, int start, int end, byte[] prefix) {
        if (end - start < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (in[start + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u8(byte[] in, int pos) {
        return in[pos] & 0xFF;
    }

    private static int u16(byte[] in, int pos, boolean little) {
        return little ? u8(in, pos) | (u8(in, pos + 1) << 8) : (u8(in, pos) << 8) | u8(in, pos + 1);
    }

    private static long u32(byte[] in, int pos, boolean little) {
        long a = u8(in, pos);
        long b = u8(in, pos + 1);
        long c = u8(in, pos + 2);
        long d = u8(in, pos + 3);
        return little ? a | (b << 8) | (c << 16) | (d << 24) : (a << 24) | (b << 16) | (c << 8) | d;
    }
}
