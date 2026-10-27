package app.nadel.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Looks at an uploaded file before any workbook parser does: is it an {@code .xlsx} at all, and is it safe to unpack.
 * <ul>
 *   <li>the name ends with {@code .xlsx} (ignoring case) and the content starts with the zip signature, which also
 *       refuses the old {@code .xls} format and encrypted workbooks (both are OLE2 files);</li>
 *   <li>the archive has the two parts every workbook has ({@code [Content_Types].xml}, {@code xl/workbook.xml}) and
 *       no more entries than the limit;</li>
 *   <li>unpacked, no entry exceeds its limit and the whole archive stays under its limit. The sizes counted are the
 *       bytes really produced by decompression: the sizes a zip declares can be false, which is how a zip bomb works.</li>
 * </ul>
 * Everything that is wrong, including a corrupt archive, is an {@link UnsafeWorkbookException}; the caller maps it to
 * its own problem. The archive is read with commons-compress over the bytes in memory, because the JDK's
 * {@code ZipInputStream} cannot read archives written with data descriptors (ADR 0012). Instances are immutable and
 * thread-safe.
 */
public final class WorkbookGuard {

    public static final int DEFAULT_MAX_ENTRIES = 200;
    public static final long DEFAULT_MAX_ENTRY_BYTES = 50L * 1024 * 1024;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    private static final String EXTENSION = ".xlsx";
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};
    private static final String CONTENT_TYPES_PART = "[Content_Types].xml";
    private static final String WORKBOOK_PART = "xl/workbook.xml";
    private static final int BUFFER_BYTES = 16 * 1024;

    private final int maxEntries;
    private final long maxEntryBytes;
    private final long maxTotalBytes;

    private WorkbookGuard(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        if (maxEntries < 1 || maxEntryBytes < 1 || maxTotalBytes < 1) {
            throw new IllegalArgumentException("Workbook limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    /** A guard with the default limits: 200 entries, 50 MB per entry, 100 MB in total once unpacked. */
    public static WorkbookGuard withDefaultLimits() {
        return new WorkbookGuard(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    /**
     * A guard with its own limits, for callers that accept only smaller files.
     *
     * @param maxEntries    the most entries the archive may have
     * @param maxEntryBytes the most bytes one entry may unpack to
     * @param maxTotalBytes the most bytes all entries together may unpack to
     * @throws IllegalArgumentException when a limit is not positive
     */
    public static WorkbookGuard withLimits(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        return new WorkbookGuard(maxEntries, maxEntryBytes, maxTotalBytes);
    }

    /**
     * @param fileName the name the client gave the file (only its extension is looked at)
     * @param content  the whole file
     * @throws UnsafeWorkbookException when the file is not a safe workbook
     */
    public void check(String fileName, byte[] content) {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            throw new UnsafeWorkbookException(UnsafeWorkbookReason.WRONG_EXTENSION);
        }
        if (!startsWithZipSignature(content)) {
            throw new UnsafeWorkbookException(UnsafeWorkbookReason.NOT_A_ZIP);
        }
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(content)).get()) {
            if (zip.getEntry(CONTENT_TYPES_PART) == null || zip.getEntry(WORKBOOK_PART) == null) {
                throw new UnsafeWorkbookException(UnsafeWorkbookReason.NOT_A_WORKBOOK);
            }
            checkEntries(zip);
        } catch (UnsafeWorkbookException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnsafeWorkbookException(UnsafeWorkbookReason.UNREADABLE);
        }
    }

    private void checkEntries(ZipFile zip) throws IOException {
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        Enumeration<ZipArchiveEntry> all = zip.getEntries();
        while (all.hasMoreElements()) {
            ZipArchiveEntry entry = all.nextElement();
            if (++entries > maxEntries) {
                throw new UnsafeWorkbookException(UnsafeWorkbookReason.TOO_MANY_ENTRIES);
            }
            if (!zip.canReadEntryData(entry)) {
                throw new UnsafeWorkbookException(UnsafeWorkbookReason.UNREADABLE);
            }
            long entryBytes = 0;
            try (InputStream data = zip.getInputStream(entry)) {
                int read;
                while ((read = data.read(buffer)) != -1) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > maxEntryBytes) {
                        throw new UnsafeWorkbookException(UnsafeWorkbookReason.ENTRY_TOO_LARGE);
                    }
                    if (total > maxTotalBytes) {
                        throw new UnsafeWorkbookException(UnsafeWorkbookReason.TOTAL_TOO_LARGE);
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
}
