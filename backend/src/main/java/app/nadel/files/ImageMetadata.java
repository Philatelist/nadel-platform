package app.nadel.files;

import app.nadel.files.internal.JpegMetadataStripper;
import app.nadel.files.internal.PngMetadataStripper;

/**
 * Removes location, camera and other metadata from an uploaded image without re-encoding it (JDK only): a JPEG
 * segment by segment, keeping its Orientation; a PNG chunk by chunk. A PDF is not an image and is returned as received.
 * Pure and stateless.
 */
public final class ImageMetadata {

    private ImageMetadata() {
    }

    /**
     * The file of the given type without its metadata.
     *
     * @throws MalformedImageException when a JPEG or PNG structure cannot be read
     */
    public static byte[] strip(FileKind kind, byte[] content) {
        return switch (kind) {
            case JPEG -> JpegMetadataStripper.strip(content);
            case PNG -> PngMetadataStripper.strip(content);
            case PDF -> content;
        };
    }
}
