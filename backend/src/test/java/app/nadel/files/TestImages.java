package app.nadel.files;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Small synthetic photos for the uploaded file tests, built in memory (no binary fixtures): a JPEG or PNG written by
 * ImageIO, then given the metadata a phone camera writes. Every metadata value carries a marker text the tests look for
 * in the stored file ({@link #MARKERS}): the camera make, the GPS latitude, XMP, an MPF index with a second image
 * after the first EOI, Photoshop IPTC, a comment and trailing bytes.
 */
public final class TestImages {

    /** The camera make in the Exif IFD0. */
    public static final String CAMERA = "TestCamMake";
    /** The XMP packet (APP1 of a JPEG, iTXt of a PNG), with a GPS position. */
    public static final String XMP = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><exif:GPSLatitude>46,28.2N"
            + "</exif:GPSLatitude></x:xmpmeta>";
    public static final String COMMENT = "secret receipt comment";
    public static final String TRAILING = "TRAILING-BYTES-AFTER-EOI";
    public static final String IPTC = "Photoshop 3.0";
    /** Texts that must not be in a stripped file. */
    public static final String[] MARKERS = {CAMERA, "GPSLatitude", "adobe:ns:meta", COMMENT, TRAILING, IPTC, "MPF"};
    /** The Orientation the camera wrote (rotate 90 degrees clockwise). */
    public static final int ORIENTATION = 6;

    private TestImages() {
    }

    /** A plain JPEG as ImageIO writes it (SOI, JFIF APP0, tables, frame, scans, EOI). */
    public static byte[] jpeg(boolean progressive) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (progressive) {
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(picture(), null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * A camera JPEG: after SOI and APP0 an Exif APP1 (IFD0 with the make, Orientation {@value #ORIENTATION} and a
     * GPS IFD), an XMP APP1, an MPF APP2, a Photoshop APP13 and a comment; after the first EOI a second image (as
     * MPF stores it) and trailing bytes.
     */
    public static byte[] cameraJpeg(boolean progressive) {
        byte[] plain = jpeg(progressive);
        int afterApp0 = 2 + 2 + (((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, afterApp0);
        out.writeBytes(segment(0xE1, concat(ascii("Exif\0\0"), exifTiff())));
        out.writeBytes(segment(0xE1, concat(ascii("http://ns.adobe.com/xap/1.0/\0"), utf8(XMP))));
        out.writeBytes(segment(0xE2, concat(ascii("MPF\0"), new byte[] {'M', 'M', 0, 0x2A, 0, 0, 0, 8})));
        out.writeBytes(segment(0xED, concat(ascii(IPTC + "\0"), ascii("8BIM caption"))));
        out.writeBytes(segment(0xFE, utf8(COMMENT)));
        out.write(plain, afterApp0, plain.length - afterApp0);
        out.writeBytes(jpeg(false));
        out.writeBytes(ascii(TRAILING));
        return out.toByteArray();
    }

    /** A plain PNG as ImageIO writes it. */
    public static byte[] png() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(picture(), "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * A PNG with metadata: after IHDR the kept chunks gAMA and pHYs, and the dropped tEXt (a comment), iTXt (XMP),
     * zTXt, eXIf (the camera Exif with GPS) and tIME; trailing bytes after IEND.
     */
    public static byte[] metadataPng() {
        byte[] plain = png();
        int afterIhdr = 8 + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, afterIhdr);
        out.writeBytes(chunk("gAMA", new byte[] {0, 0, (byte) 0xB1, (byte) 0x8F}));
        out.writeBytes(chunk("pHYs", new byte[] {0, 0, 0x0B, 0x13, 0, 0, 0x0B, 0x13, 1}));
        out.writeBytes(chunk("tEXt", concat(ascii("Comment\0"), utf8(COMMENT))));
        out.writeBytes(chunk("iTXt", concat(ascii("XML:com.adobe.xmp\0\0\0\0\0"), utf8(XMP))));
        out.writeBytes(chunk("zTXt", concat(ascii("Raw profile\0\0"), deflate(utf8(IPTC)))));
        out.writeBytes(chunk("eXIf", exifTiff()));
        out.writeBytes(chunk("tIME", new byte[] {0x07, (byte) 0xEA, 10, 6, 12, 0, 0}));
        out.write(plain, afterIhdr, plain.length - afterIhdr);
        out.writeBytes(ascii(TRAILING));
        return out.toByteArray();
    }

    /** A small PDF. */
    public static byte[] pdf() {
        return ascii("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n");
    }

    /** One PNG chunk with its CRC. */
    public static byte[] chunk(String type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(u32(data.length));
        byte[] typeBytes = ascii(type);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(u32(crc.getValue()));
        return out.toByteArray();
    }

    /** One JPEG marker segment with its length. */
    public static byte[] segment(int marker, byte[] data) {
        int length = data.length + 2;
        return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length}, data);
    }

    /**
     * A big-endian TIFF structure: IFD0 with Make, Orientation and the GPS IFD pointer; the GPS IFD with
     * GPSLatitudeRef and GPSLatitude.
     */
    static byte[] exifTiff() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'M', 'M', 0, 0x2A, 0, 0, 0, 8});
        byte[] make = ascii(CAMERA + "\0");
        int ifd0 = 8;
        int makeAt = ifd0 + 2 + 3 * 12 + 4;
        int gpsAt = makeAt + make.length;
        int latitudeAt = gpsAt + 2 + 2 * 12 + 4;
        out.writeBytes(u16(3));
        out.writeBytes(entry(0x010F, 2, make.length, u32(makeAt)));
        out.writeBytes(entry(0x0112, 3, 1, new byte[] {0, (byte) ORIENTATION, 0, 0}));
        out.writeBytes(entry(0x8825, 4, 1, u32(gpsAt)));
        out.writeBytes(u32(0));
        out.writeBytes(make);
        out.writeBytes(u16(2));
        out.writeBytes(entry(0x0001, 2, 2, new byte[] {'N', 0, 0, 0}));
        out.writeBytes(entry(0x0002, 5, 3, u32(latitudeAt)));
        out.writeBytes(u32(0));
        for (long[] rational : new long[][] {{46, 1}, {28, 1}, {1234, 100}}) {
            out.writeBytes(u32(rational[0]));
            out.writeBytes(u32(rational[1]));
        }
        return out.toByteArray();
    }

    public static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static BufferedImage picture() {
        BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 32, 16);
        g.setColor(Color.BLUE);
        g.fillRect(4, 4, 12, 8);
        g.dispose();
        return image;
    }

    private static byte[] entry(int tag, int type, int count, byte[] value) {
        return concat(u16(tag), u16(type), u32(count), value);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        byte[] buffer = new byte[data.length + 64];
        int length = deflater.deflate(buffer);
        deflater.end();
        byte[] out = new byte[length];
        System.arraycopy(buffer, 0, out, 0, length);
        return out;
    }

    private static byte[] u16(int value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static byte[] u32(long value) {
        return new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
