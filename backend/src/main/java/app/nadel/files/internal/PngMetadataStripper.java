package app.nadel.files.internal;

import app.nadel.files.MalformedImageException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Removes metadata from an uploaded PNG without re-encoding it (feature 018 §2.3, technical considerations 2.6), JDK
 * only. The chunks of the image and its colour and animation are kept byte for byte (IHDR, PLTE, IDAT, IEND, tRNS,
 * gAMA, cHRM, sRGB, iCCP, sBIT, bKGD, pHYs, acTL, fcTL, fdAT); every other ancillary chunk is dropped, among them
 * {@code eXIf} (Exif with GPS), {@code tEXt}, {@code iTXt}, {@code zTXt} (XMP and other text) and {@code tIME}.
 * Everything after IEND is cut. A file without the signature, with a chunk running past the end or a wrong CRC, not
 * starting with IHDR, without IDAT or IEND, or with an unknown critical chunk (which no decoder could skip) is refused
 * with {@link MalformedImageException}. Pure and stateless. Public only for {@link app.nadel.files.ImageMetadata},
 * which other modules call.
 */
public final class PngMetadataStripper {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static final Set<String> KEPT = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB",
            "iCCP", "sBIT", "bKGD", "pHYs", "acTL", "fcTL", "fdAT");

    private PngMetadataStripper() {
    }

    /**
     * The PNG without its metadata.
     *
     * @throws MalformedImageException when the structure cannot be read
     */
    public static byte[] strip(byte[] in) {
        if (in.length < SIGNATURE.length) {
            throw new MalformedImageException("A PNG starts with its signature");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (in[i] != SIGNATURE[i]) {
                throw new MalformedImageException("A PNG starts with its signature");
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(in, 0, SIGNATURE.length);
        int pos = SIGNATURE.length;
        boolean first = true;
        boolean image = false;
        boolean end = false;
        while (pos < in.length) {
            if (pos + 12 > in.length) {
                throw new MalformedImageException("The PNG ends inside a chunk");
            }
            long length = u32(in, pos);
            if (length > in.length - pos - 12L) {
                throw new MalformedImageException("A PNG chunk runs past the end");
            }
            int chunkEnd = pos + 12 + (int) length;
            String type = type(in, pos + 4);
            CRC32 crc = new CRC32();
            crc.update(in, pos + 4, 4 + (int) length);
            if (crc.getValue() != u32(in, chunkEnd - 4)) {
                throw new MalformedImageException("A PNG chunk has a wrong CRC");
            }
            if (first != "IHDR".equals(type)) {
                throw new MalformedImageException("A PNG starts with IHDR, once");
            }
            first = false;
            if (KEPT.contains(type)) {
                out.write(in, pos, chunkEnd - pos);
                image |= "IDAT".equals(type);
            } else if (Character.isUpperCase(type.charAt(0))) {
                throw new MalformedImageException("A PNG has an unknown critical chunk");
            }
            pos = chunkEnd;
            if ("IEND".equals(type)) {
                end = true;
                break;
            }
        }
        if (!end || !image) {
            throw new MalformedImageException("The PNG has no complete image");
        }
        return out.toByteArray();
    }

    /** The four letters of a chunk type. */
    private static String type(byte[] in, int pos) {
        for (int i = pos; i < pos + 4; i++) {
            int c = in[i] & 0xFF;
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                throw new MalformedImageException("A PNG chunk type is four letters");
            }
        }
        return new String(in, pos, 4, StandardCharsets.US_ASCII);
    }

    private static long u32(byte[] in, int pos) {
        return ((long) (in[pos] & 0xFF) << 24) | ((in[pos + 1] & 0xFF) << 16) | ((in[pos + 2] & 0xFF) << 8)
                | (in[pos + 3] & 0xFF);
    }
}
