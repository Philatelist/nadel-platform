package app.nadel.treasury.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.treasury.TestImages;
import app.nadel.treasury.internal.FileSniffer.Kind;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The checks of the receipt files of an expense and the names they are stored with (feature 018 §2.3). */
class AttachmentUploadsTests {

    @Test
    void aFileIsTypedByItsContentStrippedAndDigested() throws Exception {
        AttachmentUploads.Prepared photo = AttachmentUploads.prepare(0, "IMG_0001.JPG", TestImages.cameraJpeg(false));

        assertThat(photo.kind()).isEqualTo(Kind.JPEG);
        assertThat(photo.fileName()).isEqualTo("IMG_0001.JPG");
        assertThat(TestImages.contains(photo.content(), TestImages.CAMERA)).isFalse();
        assertThat(photo.sha256()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(photo.content()));
        AttachmentUploads.Prepared pdf = AttachmentUploads.prepare(1, "act.png", TestImages.pdf());
        assertThat(pdf.kind()).as("by content, not by name").isEqualTo(Kind.PDF);
        assertThat(pdf.content()).isEqualTo(TestImages.pdf());
        assertThat(AttachmentUploads.prepare(2, null, TestImages.metadataPng()).kind()).isEqualTo(Kind.PNG);
    }

    @Test
    void theRefusalsNameTheFile() {
        assertThatThrownBy(() -> AttachmentUploads.prepare(2, "receipt.jpg", "plain text".getBytes()))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED");
                    assertThat(e.errors()).containsExactly(new FieldProblem("files[2]", "FILE_TYPE_NOT_ALLOWED"));
                });
        byte[] truncated = Arrays.copyOf(TestImages.jpeg(false), 100);
        assertThatThrownBy(() -> AttachmentUploads.prepare(0, "a.jpg", truncated))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        e -> assertThat(e.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED"));
        assertThatThrownBy(() -> AttachmentUploads.requireSize(4, AttachmentUploads.MAX_FILE_BYTES + 1))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
                    assertThat(e.code()).isEqualTo("FILE_TOO_LARGE");
                    assertThat(e.errors()).containsExactly(new FieldProblem("files[4]", "FILE_TOO_LARGE"));
                });
        AttachmentUploads.requireSize(0, AttachmentUploads.MAX_FILE_BYTES);
        assertThatThrownBy(() -> AttachmentUploads.requireCount(2, 4))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.code()).isEqualTo("TOO_MANY");
                    assertThat(e.errors()).containsExactly(new FieldProblem("files", "TOO_MANY"));
                });
        AttachmentUploads.requireCount(2, 3);
    }

    @Test
    void theStoredNameIsTheCleanBaseName() {
        assertThat(AttachmentUploads.fileName("C:\\Users\\me\\Чек 06.10.jpg", Kind.JPEG)).isEqualTo("Чек 06.10.jpg");
        assertThat(AttachmentUploads.fileName("../../etc/passwd.pdf", Kind.PDF)).isEqualTo("passwd.pdf");
        assertThat(AttachmentUploads.fileName(" bad\u202Ename\u0007.png ", Kind.PNG)).isEqualTo("badname.png");
        assertThat(AttachmentUploads.fileName(null, Kind.JPEG)).isEqualTo("receipt.jpg");
        assertThat(AttachmentUploads.fileName("  ", Kind.PNG)).isEqualTo("receipt.png");
        assertThat(AttachmentUploads.fileName("dir/", Kind.PDF)).isEqualTo("receipt.pdf");
        String longName = "ч".repeat(300) + ".jpeg";
        String shortened = AttachmentUploads.fileName(longName, Kind.JPEG);
        assertThat(shortened.codePointCount(0, shortened.length())).isEqualTo(255);
        assertThat(shortened).endsWith("ч.jpeg");
        assertThat(AttachmentUploads.fileName("x".repeat(400), Kind.PDF)).hasSize(255);
    }
}
