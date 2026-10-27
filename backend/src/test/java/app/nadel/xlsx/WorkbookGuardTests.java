package app.nadel.xlsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;

/** What the guard lets through and what it refuses before any workbook parser sees the file. */
class WorkbookGuardTests {

    private static final long MB = 1024L * 1024;
    private final WorkbookGuard guard = WorkbookGuard.withDefaultLimits();

    /** A one-sheet workbook written by the spreadsheet library, as the import template and report files are. */
    private static byte[] workbook() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Nadel tests", "1.0")) {
            Worksheet sheet = workbook.newWorksheet("Plots");
            sheet.value(0, 0, "number");
            sheet.value(0, 1, "area_m2");
            sheet.value(1, 0, "1");
            sheet.value(1, 1, 600);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void assertRefused(String fileName, byte[] content, UnsafeWorkbookReason reason) {
        assertRefused(guard, fileName, content, reason);
    }

    private static void assertRefused(WorkbookGuard guard, String fileName, byte[] content,
            UnsafeWorkbookReason reason) {
        assertThatThrownBy(() -> guard.check(fileName, content))
                .isInstanceOfSatisfying(UnsafeWorkbookException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.getMessage()).doesNotContain(fileName);
                });
    }

    /** A zip of the given parts (name to text). */
    private static byte[] zipOfTexts(Map<String, String> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                addText(zip, part.getKey(), part.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A zip with a ready-made set of parts plus the given extra entries (name to size of zero bytes). */
    private static byte[] zipWith(Map<String, Long> zeros) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            addText(zip, "[Content_Types].xml", "<Types/>");
            addText(zip, "xl/workbook.xml", "<workbook/>");
            for (Map.Entry<String, Long> entry : zeros.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                writeZeros(zip, entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void addText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeZeros(OutputStream out, long count) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        for (long written = 0; written < count; written += chunk.length) {
            out.write(chunk, 0, (int) Math.min(chunk.length, count - written));
        }
    }

    // ---- what is accepted

    @Test
    void aWorkbookWrittenByTheLibraryPasses() {
        assertThatCode(() -> guard.check("plots.xlsx", workbook())).doesNotThrowAnyException();
    }

    @Test
    void theExtensionIsCheckedIgnoringCase() {
        assertThatCode(() -> guard.check("PLOTS.XLSX", workbook())).doesNotThrowAnyException();
    }

    @Test
    void aWorkbookWithADoctypeIsNotTheGuardsConcern() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("xl/workbook.xml", "<workbook/>");
        parts.put("xl/worksheets/sheet1.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>""");
        byte[] file = zipOfTexts(parts);

        assertThatCode(() -> guard.check("plots.xlsx", file)).doesNotThrowAnyException();
    }

    // ---- what is not an xlsx

    @Test
    void aWrongExtensionIsRefused() {
        assertRefused("plots.csv", workbook(), UnsafeWorkbookReason.WRONG_EXTENSION);
        assertRefused("plots.xls", workbook(), UnsafeWorkbookReason.WRONG_EXTENSION);
        assertRefused("plots", workbook(), UnsafeWorkbookReason.WRONG_EXTENSION);
        assertRefused("plots.xlsx.exe", workbook(), UnsafeWorkbookReason.WRONG_EXTENSION);
    }

    @Test
    void aFileThatIsNotAZipIsRefused() {
        assertRefused("plots.xlsx", "number,street\n1,Garden\n".getBytes(StandardCharsets.UTF_8),
                UnsafeWorkbookReason.NOT_A_ZIP);
        assertRefused("plots.xlsx", "%PDF-1.7 ...".getBytes(StandardCharsets.UTF_8), UnsafeWorkbookReason.NOT_A_ZIP);
        assertRefused("plots.xlsx", new byte[0], UnsafeWorkbookReason.NOT_A_ZIP);
        assertRefused("plots.xlsx", new byte[] {'P', 'K'}, UnsafeWorkbookReason.NOT_A_ZIP);
    }

    @Test
    void anOle2FileIsRefused() {
        // the old .xls format and every password-protected workbook start with the OLE2 signature
        byte[] ole2 = new byte[4096];
        byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(signature, 0, ole2, 0, signature.length);

        assertRefused("plots.xlsx", ole2, UnsafeWorkbookReason.NOT_A_ZIP);
    }

    @Test
    void aTruncatedWorkbookIsRefused() {
        byte[] whole = workbook();

        assertRefused("plots.xlsx", Arrays.copyOf(whole, whole.length / 2), UnsafeWorkbookReason.UNREADABLE);
    }

    @Test
    void aZipWithoutTheWorkbookPartsIsRefused() throws IOException {
        ByteArrayOutputStream onlyTypes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(onlyTypes)) {
            addText(zip, "[Content_Types].xml", "<Types/>");
        }
        ByteArrayOutputStream onlyWorkbook = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(onlyWorkbook)) {
            addText(zip, "xl/workbook.xml", "<workbook/>");
        }
        ByteArrayOutputStream document = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(document)) {
            addText(zip, "[Content_Types].xml", "<Types/>");
            addText(zip, "word/document.xml", "<document/>");
        }

        assertRefused("plots.xlsx", onlyTypes.toByteArray(), UnsafeWorkbookReason.NOT_A_WORKBOOK);
        assertRefused("plots.xlsx", onlyWorkbook.toByteArray(), UnsafeWorkbookReason.NOT_A_WORKBOOK);
        assertRefused("plots.xlsx", document.toByteArray(), UnsafeWorkbookReason.NOT_A_WORKBOOK);
    }

    @Test
    void tooManyEntriesAreRefused() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            addText(zip, "[Content_Types].xml", "<Types/>");
            addText(zip, "xl/workbook.xml", "<workbook/>");
            for (int i = 0; i < WorkbookGuard.DEFAULT_MAX_ENTRIES - 1; i++) {
                addText(zip, "xl/media/image" + i + ".xml", "x");
            }
        }
        assertRefused("plots.xlsx", out.toByteArray(), UnsafeWorkbookReason.TOO_MANY_ENTRIES); // 201 entries

        ByteArrayOutputStream fits = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(fits)) {
            addText(zip, "[Content_Types].xml", "<Types/>");
            addText(zip, "xl/workbook.xml", "<workbook/>");
            for (int i = 0; i < WorkbookGuard.DEFAULT_MAX_ENTRIES - 2; i++) {
                addText(zip, "xl/media/image" + i + ".xml", "x");
            }
        }
        assertThatCode(() -> guard.check("plots.xlsx", fits.toByteArray())).doesNotThrowAnyException(); // 200 entries
    }

    // ---- zip bombs

    @Test
    void anEntryThatUnpacksOverItsLimitIsRefused() {
        // 60 MB of zeros deflate to about 60 KB
        byte[] bomb = zipWith(Map.of("xl/worksheets/sheet1.xml", 60 * MB));

        assertThat(bomb.length).isLessThan((int) MB);
        assertRefused("plots.xlsx", bomb, UnsafeWorkbookReason.ENTRY_TOO_LARGE);
    }

    @Test
    void entriesThatTogetherUnpackOverTheTotalLimitAreRefused() {
        // each entry is under the per-entry limit; together they are 120 MB
        byte[] bomb = zipWith(Map.of(
                "xl/worksheets/sheet1.xml", 40 * MB,
                "xl/worksheets/sheet2.xml", 40 * MB,
                "xl/worksheets/sheet3.xml", 40 * MB));

        assertThat(bomb.length).isLessThan((int) MB);
        assertRefused("plots.xlsx", bomb, UnsafeWorkbookReason.TOTAL_TOO_LARGE);
    }

    @Test
    void aTwoHundredMegabyteBombIsRefused() {
        byte[] bomb = zipWith(Map.of("xl/sharedStrings.xml", 200 * MB));

        assertRefused("plots.xlsx", bomb, UnsafeWorkbookReason.ENTRY_TOO_LARGE);
    }

    @Test
    void sizesDeclaredByTheArchiveAreNotTrusted() {
        // the archive claims 10 bytes for an entry that really unpacks to 200 MB
        byte[] bomb = zipWith(Map.of("xl/worksheets/sheet1.xml", 200 * MB));
        lieAboutUncompressedSizes(bomb, 10);

        assertRefused("plots.xlsx", bomb, UnsafeWorkbookReason.ENTRY_TOO_LARGE);
    }

    @Test
    void theLimitsCanBeSetSmaller() {
        WorkbookGuard small = WorkbookGuard.withLimits(10, 1000, 1500);
        byte[] oneBigEntry = zipWith(Map.of("xl/a.xml", 2000L));
        byte[] twoMediumEntries = zipWith(Map.of("xl/a.xml", 800L, "xl/b.xml", 800L));
        byte[] fits = zipWith(Map.of("xl/a.xml", 500L));

        assertRefused(small, "plots.xlsx", oneBigEntry, UnsafeWorkbookReason.ENTRY_TOO_LARGE);
        assertRefused(small, "plots.xlsx", twoMediumEntries, UnsafeWorkbookReason.TOTAL_TOO_LARGE);
        assertThatCode(() -> small.check("plots.xlsx", fits)).doesNotThrowAnyException();
        assertRefused(WorkbookGuard.withLimits(2, 1000, 1500), "plots.xlsx", fits,
                UnsafeWorkbookReason.TOO_MANY_ENTRIES); // three entries
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> WorkbookGuard.withLimits(0, 1000, 1500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkbookGuard.withLimits(10, 0, 1500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkbookGuard.withLimits(10, 1000, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEncryptedEntryIsRefused() {
        byte[] encrypted = zipWith(Map.of("xl/worksheets/sheet1.xml", 100L));
        markEntriesEncrypted(encrypted);

        assertRefused("plots.xlsx", encrypted, UnsafeWorkbookReason.UNREADABLE);
    }

    /** Sets the encryption bit of the general purpose flag of every central directory header (offset 8). */
    private static void markEntriesEncrypted(byte[] zip) {
        for (int i = 0; i + 9 < zip.length; i++) {
            if (zip[i] == 'P' && zip[i + 1] == 'K' && zip[i + 2] == 1 && zip[i + 3] == 2) {
                zip[i + 8] |= 1;
            }
        }
    }

    /** Overwrites the uncompressed size field of every central directory header ({@code PK 01 02}, offset 24). */
    private static void lieAboutUncompressedSizes(byte[] zip, int declared) {
        for (int i = 0; i + 28 < zip.length; i++) {
            if (zip[i] == 'P' && zip[i + 1] == 'K' && zip[i + 2] == 1 && zip[i + 3] == 2) {
                zip[i + 24] = (byte) declared;
                zip[i + 25] = 0;
                zip[i + 26] = 0;
                zip[i + 27] = 0;
            }
        }
    }
}
