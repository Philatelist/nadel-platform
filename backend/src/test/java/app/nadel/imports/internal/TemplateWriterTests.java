package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;

/** The generated import template, read back with the same library the import will use. */
class TemplateWriterTests {

    private final byte[] template = new TemplateWriter().write();

    @Test
    void hasThePlotsMembersAndOwnershipSheetsInThatOrder() throws IOException {
        try (ReadableWorkbook workbook = open()) {
            assertThat(workbook.getSheets().map(Sheet::getName)).containsExactly("Plots", "Members", "Ownership");
        }
    }

    @Test
    void headersAreTheEnglishCanonicalColumnNames() throws IOException {
        assertThat(headers("Plots")).containsExactly(
                "number", "street", "kind", "area_m2", "area_sotky", "cadastral_number", "has_building", "notes");
        assertThat(headers("Members")).containsExactly(
                "external_id", "last_name", "first_name", "middle_name", "phones", "email", "postal_address",
                "delivery_channel", "telegram_username", "viber_phone");
        assertThat(headers("Ownership")).containsExactly(
                "plot_number", "member_external_id", "share", "valid_from");
    }

    @Test
    void secondAndThirdRowsAreCommentRowsThatStartWithAHash() throws IOException {
        try (ReadableWorkbook workbook = open()) {
            for (String name : List.of("Plots", "Members", "Ownership")) {
                List<Row> rows = workbook.findSheet(name).orElseThrow().read();
                assertThat(rows).as(name).hasSize(3);
                assertThat(rows.get(1).getCellText(0)).as(name + " instruction row").startsWith("#");
                assertThat(rows.get(2).getCellText(0)).as(name + " example row").startsWith("#");
            }
        }
    }

    @Test
    void everyInstructionCellIsFilledAndTheExampleRowCarriesRealisticValues() throws IOException {
        try (ReadableWorkbook workbook = open()) {
            List<Row> plots = workbook.findSheet("Plots").orElseThrow().read();
            for (int column = 0; column < 8; column++) {
                assertThat(plots.get(1).getCellText(column)).as("instruction " + column).isNotBlank();
            }
            Row example = plots.get(2);
            assertThat(example.getCellText(0)).isEqualTo("# example: 12a");
            assertThat(example.getCellText(1)).isEqualTo("Garden street");
            assertThat(example.getCellText(2)).isEqualTo("PLOT");
            assertThat(example.getCellText(3)).isEqualTo("600");
            assertThat(example.getCellText(5)).isEqualTo("1234567890:12:345:6789");

            Row member = workbook.findSheet("Members").orElseThrow().read().get(2);
            assertThat(member.getCellText(0)).isEqualTo("# example: M-001");
            assertThat(member.getCellText(4)).isEqualTo("+380501234567, +380671234567");
            assertThat(member.getCellText(7)).isEqualTo("EMAIL");

            Row ownership = workbook.findSheet("Ownership").orElseThrow().read().get(2);
            assertThat(ownership.getCellText(0)).isEqualTo("# example: 12a");
            assertThat(ownership.getCellText(1)).isEqualTo("M-001");
            assertThat(ownership.getCellText(2)).isEqualTo("1");
            assertThat(ownership.getCellText(3)).isEqualTo("01.03.2020");
        }
    }

    @Test
    void identifierAndPhoneColumnsHaveTheTextFormat() throws IOException {
        // text columns: Plots number and cadastral_number; Members external_id, phones, viber_phone;
        // Ownership plot_number and member_external_id
        assertThat(textColumns(1)).containsExactly(1, 6);
        assertThat(textColumns(2)).containsExactly(1, 5, 10);
        assertThat(textColumns(3)).containsExactly(1, 2);
        assertThat(entry("xl/styles.xml")).contains("formatCode=\"@\"");
    }

    @Test
    void containsNoCyrillicText() throws IOException {
        try (ReadableWorkbook workbook = open()) {
            for (Sheet sheet : workbook.getSheets().toList()) {
                for (Row row : sheet.read()) {
                    for (int column = 0; column < row.getCellCount(); column++) {
                        assertThat(row.getCellText(column)).doesNotContainPattern("[\\u0400-\\u04FF]");
                    }
                }
            }
        }
    }

    private ReadableWorkbook open() throws IOException {
        return new ReadableWorkbook(new ByteArrayInputStream(template));
    }

    private List<String> headers(String sheet) throws IOException {
        try (ReadableWorkbook workbook = open()) {
            Row header = workbook.findSheet(sheet).orElseThrow().read().get(0);
            List<String> names = new ArrayList<>();
            for (int column = 0; column < header.getCellCount(); column++) {
                names.add(header.getCellText(column));
            }
            return names;
        }
    }

    /** 1-based indexes of the columns that carry a column-level style in the given worksheet part. */
    private List<Integer> textColumns(int sheetNumber) throws IOException {
        Matcher cols = Pattern.compile("<col [^>]*min=\"(\\d+)\"[^>]*style=\"\\d+\"")
                .matcher(entry("xl/worksheets/sheet" + sheetNumber + ".xml"));
        List<Integer> indexes = new ArrayList<>();
        while (cols.find()) {
            indexes.add(Integer.parseInt(cols.group(1)));
        }
        return indexes;
    }

    /** A part of the package as text. A temp file and ZipFile, because the writer's entries use data descriptors. */
    private String entry(String name) throws IOException {
        Path file = Files.createTempFile("nadel-template", ".xlsx");
        try {
            Files.write(file, template);
            try (ZipFile zip = new ZipFile(file.toFile())) {
                ZipEntry entry = zip.getEntry(name);
                assertThat(entry).as("part " + name).isNotNull();
                return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            Files.delete(file);
        }
    }
}
