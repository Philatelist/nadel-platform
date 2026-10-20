package app.nadel.imports.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.http.HttpStatus;

/**
 * Looks at an uploaded file before any workbook parser does: is it an {@code .xlsx} at all, and is it safe to unpack.
 * <ul>
 *   <li>the name ends with {@code .xlsx} and the content starts with the zip signature, which also refuses the old
 *       {@code .xls} format and encrypted workbooks (both are OLE2 files);</li>
 *   <li>the archive has the two parts every workbook has ({@code [Content_Types].xml}, {@code xl/workbook.xml}) and
 *       no more than a few hundred entries;</li>
 *   <li>unpacked, no entry exceeds its limit and the whole archive stays under its limit. The sizes counted are the
 *       bytes really produced by decompression: the sizes a zip declares can be false, which is how a zip bomb works.</li>
 * </ul>
 * Everything that is wrong, including a corrupt archive, is answered with the same 400 {@code FILE_NOT_XLSX}.
 */
final class ZipGuard {

    static final int DEFAULT_MAX_ENTRIES = 200;
    static final long DEFAULT_MAX_ENTRY_BYTES = 50L * 1024 * 1024;
    static final long DEFAULT_MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    private static final String EXTENSION = ".xlsx";
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};
    private static final String CONTENT_TYPES_PART = "[Content_Types].xml";
    private static final String WORKBOOK_PART = "xl/workbook.xml";
    private static final int BUFFER_BYTES = 16 * 1024;

    private final int maxEntries;
    private final long maxEntryBytes;
    private final long maxTotalBytes;

    ZipGuard() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    ZipGuard(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * @param fileName the name the client gave the file (only its extension is looked at)
     * @param content  the whole file
     * @throws ApiProblemException 400 {@code FILE_NOT_XLSX} when the file is not a safe workbook
     */
    void check(String fileName, byte[] content) {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION) || !startsWithZipSignature(content)) {
            throw notXlsx();
        }
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(content)).get()) {
            if (zip.getEntry(CONTENT_TYPES_PART) == null || zip.getEntry(WORKBOOK_PART) == null) {
                throw notXlsx();
            }
            checkEntries(zip);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiProblemException problem) {
                throw problem;
            }
            throw notXlsx();
        }
    }

    private void checkEntries(ZipFile zip) throws IOException {
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        Enumeration<ZipArchiveEntry> all = zip.getEntries();
        while (all.hasMoreElements()) {
            ZipArchiveEntry entry = all.nextElement();
            if (++entries > maxEntries || !zip.canReadEntryData(entry)) {
                throw notXlsx();
            }
            long entryBytes = 0;
            try (InputStream data = zip.getInputStream(entry)) {
                int read;
                while ((read = data.read(buffer)) != -1) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > maxEntryBytes || total > maxTotalBytes) {
                        throw notXlsx();
                    }
                }
            }
        }
    }

    private static boolean startsWithZipSignature(byte[] content) {
        if (content.length < ZIP_SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < ZIP_SIGNATURE.length; index++) {
            if (content[index] != ZIP_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    static ApiProblemException notXlsx() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.FILE_NOT_XLSX,
                "The file is not a valid .xlsx workbook.");
    }
}
