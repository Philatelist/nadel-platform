package app.nadel.meters.internal;

import app.nadel.files.FileKind;
import app.nadel.files.FileSniffer;
import app.nadel.files.ImageMetadata;
import app.nadel.files.MalformedImageException;
import app.nadel.files.UploadLimits;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * The checks of the photo of a meter reading (feature 021 §2.3), run before anything is stored and before the meter is
 * locked, with the codes of the expense receipts (feature 018) on the field {@value #FIELD}, in this order: over
 * {@value UploadLimits#MAX_FILE_BYTES} bytes (5 MiB) by the size the upload declares, before it is read: 413
 * {@code FILE_TOO_LARGE}; not a JPEG or PNG by its first bytes ({@link FileSniffer}; a PDF too, a photo is an image),
 * or a JPEG or PNG whose structure cannot be read: 400 {@code FILE_TYPE_NOT_ALLOWED}. The photo is stored without its
 * metadata ({@link ImageMetadata}) and never re-encoded. Neither its name nor anything read from it is logged or put
 * into an answer.
 */
final class ReadingPhotoUploads {

    /** The multipart part and the field of a refusal. */
    static final String FIELD = "photo";

    private ReadingPhotoUploads() {
    }

    /** A photo that may be stored: its type, the stored bytes and their SHA-256. */
    record Prepared(FileKind kind, byte[] content, byte[] sha256) {
    }

    /** Checks and prepares the uploaded photo (see the class comment). */
    static Prepared prepare(MultipartFile photo) {
        requireSize(photo.getSize());
        try {
            return prepare(photo.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("An uploaded photo could not be read", e);
        }
    }

    /** The checked photo: its type by content, the bytes to store and their digest. */
    static Prepared prepare(byte[] content) {
        requireSize(content.length);
        FileKind kind = FileSniffer.sniff(content).filter(found -> found != FileKind.PDF)
                .orElseThrow(ReadingPhotoUploads::notAllowed);
        byte[] stored;
        try {
            stored = ImageMetadata.strip(kind, content);
        } catch (MalformedImageException e) {
            throw notAllowed();
        }
        requireSize(stored.length);
        return new Prepared(kind, stored, sha256(stored));
    }

    /** Refuses a photo over 5 MiB with 413 {@code FILE_TOO_LARGE} on {@code photo}. */
    private static void requireSize(long size) {
        if (size > UploadLimits.MAX_FILE_BYTES) {
            throw new ApiProblemException(HttpStatus.CONTENT_TOO_LARGE, ProblemCodes.FILE_TOO_LARGE,
                    "The photo is larger than 5 MB.",
                    List.of(new FieldProblem(FIELD, ProblemCodes.FILE_TOO_LARGE)));
        }
    }

    private static ApiProblemException notAllowed() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.FILE_TYPE_NOT_ALLOWED,
                "Only JPEG and PNG photos are allowed.",
                List.of(new FieldProblem(FIELD, ProblemCodes.FILE_TYPE_NOT_ALLOWED)));
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
