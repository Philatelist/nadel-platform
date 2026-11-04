package app.nadel.treasury.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import app.nadel.treasury.internal.FileSniffer.Kind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * The checks of the receipt files of a new expense (feature 018 §2.3, technical considerations 2.6), before anything is
 * stored and before any lock is taken, in this order (the first that fails answers):
 * <ol>
 *   <li>more than {@value #MAX_FILES} files, reused ones included: 400 {@code TOO_MANY} on {@code files};</li>
 *   <li>per uploaded file {@code files[i]} (0-based), in order: over {@value #MAX_FILE_BYTES} bytes (5 MiB) by the size
 *       the upload declares, before it is read: 413 {@code FILE_TOO_LARGE}; not a JPEG, PNG or PDF by its first bytes
 *       ({@link FileSniffer}), or a JPEG or PNG whose structure cannot be read: 400 {@code FILE_TYPE_NOT_ALLOWED}.</li>
 * </ol>
 * A JPEG or PNG is stored without its metadata ({@link JpegMetadataStripper}, {@link PngMetadataStripper}); a PDF as
 * received. The stored name is the base name the client gave, without control and invisible characters, at most 255
 * characters ({@code receipt.jpg}/{@code .png}/{@code .pdf} when nothing is left). Neither a name nor anything read
 * from a file is logged or put into an answer.
 */
final class AttachmentUploads {

    /** 5 MiB: the largest stored file (and the {@code size_bytes} CHECK of V32). */
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    /** The most files of one expense (the {@code position} CHECK of V32). */
    static final int MAX_FILES = 5;

    private static final int MAX_FILE_NAME_CODE_POINTS = 255;
    /** Control and invisible formatting characters (bidirectional overrides, zero-width marks). */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private AttachmentUploads() {
    }

    /** A file that may be stored: its type, the stored name, the stored bytes and their SHA-256. */
    record Prepared(Kind kind, String fileName, byte[] content, byte[] sha256) {
    }

    /** Refuses more than {@value #MAX_FILES} files with 400 {@code TOO_MANY} on {@code files}. */
    static void requireCount(int reused, int uploaded) {
        if (reused + uploaded > MAX_FILES) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.TOO_MANY,
                    "An expense takes at most five files.",
                    List.of(new FieldProblem("files", ProblemCodes.TOO_MANY)));
        }
    }

    /** Checks and prepares the uploaded files in order (see the class comment). */
    static List<Prepared> prepare(List<MultipartFile> files) {
        List<Prepared> prepared = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            requireSize(i, file.getSize());
            try {
                prepared.add(prepare(i, file.getOriginalFilename(), file.getBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException("An uploaded file could not be read", e);
            }
        }
        return prepared;
    }

    /** Refuses a file over {@value #MAX_FILE_BYTES} bytes with 413 {@code FILE_TOO_LARGE} on {@code files[i]}. */
    static void requireSize(int index, long size) {
        if (size > MAX_FILE_BYTES) {
            throw new ApiProblemException(HttpStatus.CONTENT_TOO_LARGE, ProblemCodes.FILE_TOO_LARGE,
                    "An expense file is larger than 5 MB.",
                    List.of(new FieldProblem(field(index), ProblemCodes.FILE_TOO_LARGE)));
        }
    }

    /** The checked file {@code files[index]}: its type by content, the bytes to store and their digest. */
    static Prepared prepare(int index, @Nullable String originalName, byte[] content) {
        requireSize(index, content.length);
        Kind kind = FileSniffer.sniff(content).orElseThrow(() -> notAllowed(index));
        byte[] stored;
        try {
            stored = switch (kind) {
                case JPEG -> JpegMetadataStripper.strip(content);
                case PNG -> PngMetadataStripper.strip(content);
                case PDF -> content;
            };
        } catch (MalformedImageException e) {
            throw notAllowed(index);
        }
        requireSize(index, stored.length);
        return new Prepared(kind, fileName(originalName, kind), stored, sha256(stored));
    }

    /**
     * The last path segment without control and invisible characters, trimmed, at most 255 characters (shortened
     * before its extension); {@code receipt} with the extension of the type when nothing is left.
     */
    static String fileName(@Nullable String original, Kind kind) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = INVISIBLE.matcher(name).replaceAll("").strip();
        if (name.isEmpty()) {
            return "receipt." + kind.name().toLowerCase(Locale.ROOT).replace("jpeg", "jpg");
        }
        if (name.codePointCount(0, name.length()) > MAX_FILE_NAME_CODE_POINTS) {
            int dot = name.lastIndexOf('.');
            String suffix = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            String stem = name.substring(0, name.length() - suffix.length());
            int keep = MAX_FILE_NAME_CODE_POINTS - suffix.codePointCount(0, suffix.length());
            name = stem.substring(0, stem.offsetByCodePoints(0, keep)) + suffix;
        }
        return name;
    }

    private static ApiProblemException notAllowed(int index) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.FILE_TYPE_NOT_ALLOWED,
                "Only JPEG, PNG and PDF files are allowed.",
                List.of(new FieldProblem(field(index), ProblemCodes.FILE_TYPE_NOT_ALLOWED)));
    }

    private static String field(int index) {
        return "files[" + index + "]";
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
