package app.nadel.privacy.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local master key of the {@code dev} profile when the environment has none (ADR 0016): read
 * {@code NADEL_PD_MASTER_KEY=} from the first of the configured files that exists ({@code infra/.env} from the
 * repository root, {@code ../infra/.env} from {@code backend/}); when none exists and the directory of the first one
 * does, generate a random key and write it there with mode 0600; otherwise fail with a hint. There is never an
 * in-memory-only key (it would make the local data unreadable after a restart) and never a committed one. The key itself
 * is never logged, only the path of the file.
 */
final class DevMasterKeySource {

    static final String LINE_PREFIX = MasterKeys.CURRENT_VARIABLE + "=";
    private static final Logger log = LoggerFactory.getLogger(DevMasterKeySource.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path baseDir;
    private final List<String> files;

    /**
     * @param baseDir the directory the file names are relative to (the working directory)
     * @param files   candidate files, in order
     */
    DevMasterKeySource(Path baseDir, List<String> files) {
        this.baseDir = baseDir;
        this.files = List.copyOf(files);
    }

    /** The base64 master key. */
    String resolve() {
        for (String file : files) {
            Path path = baseDir.resolve(file).normalize();
            if (Files.isRegularFile(path)) {
                return read(path).orElseThrow(() -> new IllegalStateException(path + " has no "
                        + MasterKeys.CURRENT_VARIABLE + " line; add one, or remove the file and run `make up` to "
                        + "create a new local key (the data encrypted with the old key becomes unreadable)"));
            }
        }
        if (!files.isEmpty()) {
            Path first = baseDir.resolve(files.getFirst()).normalize();
            Path directory = first.getParent();
            if (directory != null && Files.isDirectory(directory)) {
                return create(first);
            }
        }
        throw new IllegalStateException(MasterKeys.CURRENT_VARIABLE + " is missing: set it in the environment, or run "
                + "`make up` from the repository root, which creates infra/.env with a local key");
    }

    private static Optional<String> read(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The local master key file " + path + " could not be read", e);
        }
        return lines.stream()
                .map(String::strip)
                .map(line -> line.startsWith("export ") ? line.substring("export ".length()).strip() : line)
                .filter(line -> line.startsWith(LINE_PREFIX))
                .map(line -> unquote(line.substring(LINE_PREFIX.length()).strip()))
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    /** Writes a new random key to {@code path}; when another process created the file first, reads that one. */
    static String create(Path path) {
        byte[] raw = new byte[MasterKeys.KEY_BYTES];
        RANDOM.nextBytes(raw);
        String key = Base64.getEncoder().encodeToString(raw);
        try {
            FileAttribute<?>[] attributes = path.getFileSystem().supportedFileAttributeViews().contains("posix")
                    ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------"))}
                    : new FileAttribute<?>[0];
            Files.createFile(path, attributes);
            Files.writeString(path, LINE_PREFIX + key + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (FileAlreadyExistsException raced) {
            return read(path).orElseThrow(() -> new IllegalStateException(path + " has no "
                    + MasterKeys.CURRENT_VARIABLE + " line"));
        } catch (IOException e) {
            throw new UncheckedIOException("The local master key file " + path + " could not be written", e);
        }
        log.warn("Created a local personal data master key in {} (mode 600). Keep the file: without it the encrypted "
                + "contacts of this database cannot be read.", path);
        return key;
    }
}
