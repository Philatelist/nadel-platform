package app.nadel.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The type of an attachment comes from its first bytes, never from its name (feature 018 §2.3). */
class FileSnifferTests {

    @Test
    void jpegPngAndPdfAreRecognisedByTheirSignatures() {
        assertThat(FileSniffer.sniff(TestImages.jpeg(false))).contains(FileKind.JPEG);
        assertThat(FileSniffer.sniff(TestImages.png())).contains(FileKind.PNG);
        assertThat(FileSniffer.sniff(TestImages.pdf())).contains(FileKind.PDF);
        assertThat(FileKind.JPEG.mediaType()).isEqualTo("image/jpeg");
        assertThat(FileKind.PNG.mediaType()).isEqualTo("image/png");
        assertThat(FileKind.PDF.mediaType()).isEqualTo("application/pdf");
    }

    @Test
    void anythingElseIsNoAllowedType() {
        assertThat(FileSniffer.sniff("a text file renamed to receipt.jpg".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
        assertThat(FileSniffer.sniff(new byte[0])).isEmpty();
        assertThat(FileSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8})).as("too short for a JPEG").isEmpty();
        assertThat(FileSniffer.sniff("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(FileSniffer.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x00})).isEmpty();
        assertThat(FileSniffer.sniff("%PDX-1.4".getBytes(StandardCharsets.US_ASCII))).isEmpty();
    }
}
