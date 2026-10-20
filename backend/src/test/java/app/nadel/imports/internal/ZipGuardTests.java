package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.problem.ApiProblemException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** What the guard lets through and what it refuses before any workbook parser sees the file. */
class ZipGuardTests {

    private static final long MB = 1024L * 1024;
    private final ZipGuard guard = new ZipGuard();

    private static byte[] workbook() {
        return ImportWorkbooks.plots(ImportWorkbooks.plot("1", 600));
    }

    private void assertRefused(String fileName, byte[] content) {
        assertThatThrownBy(() -> guard.check(fileName, content))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("FILE_NOT_XLSX");
                });
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
        byte[] file = ImportWorkbooks.handMade(Map.of("xl/worksheets/sheet1.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData/></worksheet>"""));

        assertThatCode(() -> guard.check("plots.xlsx", file)).doesNotThrowAnyException();
    }

    // ---- what is not an xlsx

    @Test
    void aWrongExtensionIsRefused() {
        assertRefused("plots.csv", workbook());
        assertRefused("plots.xls", workbook());
        assertRefused("plots", workbook());
        assertRefused("plots.xlsx.exe", workbook());
    }

    @Test
    void aFileThatIsNotAZipIsRefused() {
        assertRefused("plots.xlsx", "number,street\n1,Garden\n".getBytes(StandardCharsets.UTF_8));
        assertRefused("plots.xlsx", "%PDF-1.7 ...".getBytes(StandardCharsets.UTF_8));
        assertRefused("plots.xlsx", new byte[0]);
        assertRefused("plots.xlsx", new byte[] {'P', 'K'});
    }

    @Test
    void anOle2FileIsRefused() {
        // the old .xls format and every password-protected workbook start with the OLE2 signature
        byte[] ole2 = new byte[4096];
        byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(signature, 0, ole2, 0, signature.length);

        assertRefused("plots.xlsx", ole2);
    }

    @Test
    void aTruncatedWorkbookIsRefused() {
        byte[] whole = workbook();

        assertRefused("plots.xlsx", Arrays.copyOf(whole, whole.length / 2));
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

        assertRefused("plots.xlsx", onlyTypes.toByteArray());
        assertRefused("plots.xlsx", onlyWorkbook.toByteArray());
        assertRefused("plots.xlsx", document.toByteArray());
    }

    @Test
    void tooManyEntriesAreRefused() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            addText(zip, "[Content_Types].xml", "<Types/>");
            addText(zip, "xl/workbook.xml", "<workbook/>");
            for (int i = 0; i < ZipGuard.DEFAULT_MAX_ENTRIES - 1; i++) {
                addText(zip, "xl/media/image" + i + ".xml", "x");
            }
        }
        assertRefused("plots.xlsx", out.toByteArray()); // 201 entries

        ByteArrayOutputStream fits = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(fits)) {
            addText(zip, "[Content_Types].xml", "<Types/>");
            addText(zip, "xl/workbook.xml", "<workbook/>");
            for (int i = 0; i < ZipGuard.DEFAULT_MAX_ENTRIES - 2; i++) {
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
        assertRefused("plots.xlsx", bomb);
    }

    @Test
    void entriesThatTogetherUnpackOverTheTotalLimitAreRefused() {
        // each entry is under the per-entry limit; together they are 120 MB
        byte[] bomb = zipWith(Map.of(
                "xl/worksheets/sheet1.xml", 40 * MB,
                "xl/worksheets/sheet2.xml", 40 * MB,
                "xl/worksheets/sheet3.xml", 40 * MB));

        assertThat(bomb.length).isLessThan((int) MB);
        assertRefused("plots.xlsx", bomb);
    }

    @Test
    void aTwoHundredMegabyteBombIsRefused() {
        byte[] bomb = zipWith(Map.of("xl/sharedStrings.xml", 200 * MB));

        assertRefused("plots.xlsx", bomb);
    }

    @Test
    void sizesDeclaredByTheArchiveAreNotTrusted() {
        // the archive claims 10 bytes for an entry that really unpacks to 200 MB
        byte[] bomb = zipWith(Map.of("xl/worksheets/sheet1.xml", 200 * MB));
        lieAboutUncompressedSizes(bomb, 10);

        assertRefused("plots.xlsx", bomb);
    }

    @Test
    void theLimitsCanBeSetSmaller() {
        ZipGuard small = new ZipGuard(10, 1000, 1500);
        byte[] oneBigEntry = zipWith(Map.of("xl/a.xml", 2000L));
        byte[] twoMediumEntries = zipWith(Map.of("xl/a.xml", 800L, "xl/b.xml", 800L));
        byte[] fits = zipWith(Map.of("xl/a.xml", 500L));

        assertThatThrownBy(() -> small.check("plots.xlsx", oneBigEntry)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> small.check("plots.xlsx", twoMediumEntries)).isInstanceOf(ApiProblemException.class);
        assertThatCode(() -> small.check("plots.xlsx", fits)).doesNotThrowAnyException();
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
