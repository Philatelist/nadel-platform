package app.nadel.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.nadel.money.Money;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Note;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipFile;
import nadel.contract.model.ReportCellReason;
import nadel.contract.model.ReportCode;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportNoteCode;
import nadel.contract.model.ReportPeriodBasis;
import nadel.contract.model.ReportRowKind;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workbook writer on a sheet with every column type: number formats, dates, codes and charge type names in the
 * language, an unknown value as an empty cell with its reason as a comment, the indent of a tree row, the label of a
 * subtotal and of the totals, and Excel-safe sheet names.
 */
class XlsxReportWriterTests {

    private static final UUID TYPE = UUID.randomUUID();
    private static final ReportTexts EN = ReportTexts.of(ReportLanguage.EN);

    @TempDir
    Path folder;

    @Test
    void everyColumnTypeIsWrittenAsItsKind() throws Exception {
        byte[] bytes = write();

        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(bytes),
                new ReadingOptions(true, false))) {
            assertThat(workbook.getFirstSheet().getName()).isEqualTo("By billing month");
            List<org.dhatim.fastexcel.reader.Row> rows = workbook.getFirstSheet().read();
            assertThat(rows.get(2).getCellText(0)).isEqualTo(
                    "Filters: Billing months: 01.2025 – 02.2025; Street: all");
            assertThat(rows.get(5).getCellText(0)).startsWith("The sheet “By service period” groups");
            assertThat(texts(rows.get(6))).containsExactly("Month", "Month status", "Charge type", "Plots",
                    "Charged", "Opening balances", "Reversals", "Adjustments", "Effective total", "Security");
            org.dhatim.fastexcel.reader.Row detail = rows.get(7);
            assertFormat(detail, 0, "mm.yyyy");
            assertThat(detail.getCellText(1)).isEqualTo("Open");
            assertThat(detail.getCellText(2)).isEqualTo("Security");
            assertFormat(detail, 3, "#,##0");
            assertFormat(detail, 4, "#,##0.000");
            assertThat(detail.getCellAsNumber(4).orElseThrow()).isEqualByComparingTo("152.4");
            assertFormat(detail, 5, "0.00");
            assertFormat(detail, 6, "dd.mm.yyyy");
            assertThat(detail.getCellAsDate(6).orElseThrow().toLocalDate()).isEqualTo(LocalDate.of(2025, 1, 31));
            assertThat(detail.getCell(7).getType()).isEqualTo(CellType.BOOLEAN);
            assertFormat(detail, 8, "#,##0.00");
            assertThat(detail.getCellText(9)).isEqualTo("уч. 12а");
            // the unknown value is empty, never 0
            org.dhatim.fastexcel.reader.Row child = rows.get(8);
            assertThat(child.getOptionalCell(4).map(c -> c.getType()).orElse(CellType.EMPTY))
                    .isEqualTo(CellType.EMPTY);
            assertThat(child.getCellText(9)).isEqualTo("уч. 12б");
            org.dhatim.fastexcel.reader.Row subtotal = rows.get(9);
            assertThat(subtotal.getCellText(2)).isEqualTo("Subtotal");
            org.dhatim.fastexcel.reader.Row totals = rows.get(10);
            assertThat(totals.getCellText(1)).isEqualTo("Total");
            assertThat(totals.getCellAsNumber(8).orElseThrow()).isEqualByComparingTo("1250.5");
        }
        assertThat(part(bytes, "xl/comments1.xml")).contains("No reading, so the value is not known");
        assertThat(part(bytes, "xl/styles.xml")).contains("indent=\"2\"");
        assertThat(part(bytes, "xl/worksheets/sheet1.xml")).contains("ySplit=\"8\"");
    }

    @Test
    void sheetNamesAreSafeShortAndUnique() {
        Set<String> taken = new HashSet<>();

        assertThat(XlsxReportWriter.sheetName("A/B: [x]*?\\", taken)).isEqualTo("A B   x");
        assertThat(XlsxReportWriter.sheetName("x".repeat(40), taken)).isEqualTo("x".repeat(31));
        assertThat(XlsxReportWriter.sheetName("X".repeat(40), taken)).isEqualTo("x".repeat(29).toUpperCase() + " 2");
        assertThat(XlsxReportWriter.sheetName("Main", taken)).isEqualTo("Main");
        assertThat(XlsxReportWriter.sheetName("main", taken)).isEqualTo("main 2");
    }

    private byte[] write() {
        List<Column> columns = List.of(Column.of("month", ReportColumnType.MONTH),
                Column.codes("status", "billing.status"), Column.of("chargeType", ReportColumnType.CHARGE_TYPE),
                Column.of("plots", ReportColumnType.INTEGER), Column.of("regular", ReportColumnType.QUANTITY),
                Column.of("opening", ReportColumnType.PERCENT), Column.of("reversals", ReportColumnType.DATE),
                Column.of("adjustments", ReportColumnType.BOOLEAN), Column.of("effective", ReportColumnType.MONEY),
                new Column("debt." + TYPE, ReportColumnType.TEXT, TYPE, null));
        List<Row> rows = List.of(
                Row.detail(List.of(Cell.of(YearMonth.of(2025, 1)), Cell.of("OPEN"), Cell.of(TYPE), Cell.of(2),
                        Cell.of(new BigDecimal("152.400")), Cell.of(new BigDecimal("4.75")),
                        Cell.of(LocalDate.of(2025, 1, 31)), Cell.of(true), Cell.of(Money.parse("1250.50")),
                        Cell.of("уч. 12а"))),
                new Row(ReportRowKind.DETAIL, 2, List.of(Cell.of(YearMonth.of(2025, 2)), Cell.EMPTY, Cell.EMPTY,
                        Cell.EMPTY, Cell.unknown(ReportCellReason.NO_READING), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY,
                        Cell.EMPTY, Cell.of("уч. 12б"))),
                Row.subtotal(List.of(Cell.of(YearMonth.of(2025, 2)), Cell.of("CLOSED"), Cell.EMPTY, Cell.EMPTY,
                        Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.of(Money.parse("1250.50")),
                        Cell.EMPTY)));
        List<Cell> total = List.of(Cell.of(YearMonth.of(2025, 2)), Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.EMPTY,
                Cell.EMPTY, Cell.EMPTY, Cell.EMPTY, Cell.of(Money.parse("1250.50")), Cell.EMPTY);
        ReportResult result = new ReportResult(List.of(new Sheet("byBillingMonth", columns, rows,
                List.of(new Total("all", total)))), List.of(new Note(ReportNoteCode.SERVICE_PERIOD_SHEET, Map.of())));
        ReportDefinition definition = new FakeReport(ReportCode.CHARGES_BY_MONTH, Set.of(), 0);
        ReportFilters filters = ReportFilters.parse(List.of(
                MonthRangeFilter.previousMonth(ReportPeriodBasis.BILLING_MONTHS), new StreetFilter()),
                new RawFilters("2025-01", "2025-02", null, null, null, null, null, null, null, null, null, null, null,
                        null, null), new ReportContext(LocalDate.of(2026, 10, 24), 20_001, List.of()));
        return new XlsxReportWriter().write(definition, filters, result, new XlsxReportWriter.Header("СТ «Тест»",
                ZonedDateTime.of(2026, 10, 24, 14, 5, 0, 0, ZoneId.of("Europe/Kyiv")), "Петренко Іван"), EN,
                id -> id.equals(TYPE) ? "Security" : "?");
    }

    private static List<String> texts(org.dhatim.fastexcel.reader.Row row) {
        return row.stream().map(org.dhatim.fastexcel.reader.Cell::getText).toList();
    }

    private static void assertFormat(org.dhatim.fastexcel.reader.Row row, int column, String format) {
        assertThat(row.getCell(column).getType()).isEqualTo(CellType.NUMBER);
        assertThat(row.getCell(column).getDataFormatString()).isEqualTo(format);
    }

    private String part(byte[] workbook, String entry) throws IOException {
        Path file = folder.resolve(UUID.randomUUID() + ".xlsx");
        Files.write(file, workbook);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
