package app.nadel.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.problem.ApiProblemException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Reading rows and cells out of a workbook: types, gaps, row numbers, sheet lookup and damaged files. */
class WorkbookReaderTests {

    private static final String SHEET_HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""";
    private static final String SHEET_TAIL = "</sheetData></worksheet>";

    private static List<RawRow> read(byte[] workbook, String sheet) {
        List<RawRow> rows = new ArrayList<>();
        try (WorkbookReader reader = WorkbookReader.open(workbook)) {
            assertThat(reader.readSheet(sheet, rows::add)).isTrue();
        }
        return rows;
    }

    @Test
    void textNumbersBooleansAndDatesAreReadWithGapsAsNulls() {
        byte[] file = ImportWorkbooks.builder()
                .sheetWithRows("Plots", List.of(Arrays.asList("abc", 12, true, LocalDate.of(2020, 3, 1), null, "end")))
                .build();

        RawRow row = read(file, "Plots").getFirst();

        assertThat(row.number()).isEqualTo(1);
        assertThat(row.cells()).hasSize(6);
        assertThat(row.cell(0)).isEqualTo(new RawCell.Text("abc"));
        RawCell.Number number = (RawCell.Number) row.cell(1);
        assertThat(number.value()).isEqualByComparingTo("12");
        assertThat(number.dateFormat()).isFalse();
        assertThat(row.cell(2)).isEqualTo(new RawCell.Bool(true));
        RawCell.Number date = (RawCell.Number) row.cell(3);
        assertThat(date.dateFormat()).isTrue();
        assertThat(Cells.date(date, false).value()).isEqualTo(LocalDate.of(2020, 3, 1));
        assertThat(row.cell(4)).isNull();
        assertThat(row.cell(5)).isEqualTo(new RawCell.Text("end"));
        assertThat(row.cell(6)).isNull();
        assertThat(row.cell(-1)).isNull();
    }

    @Test
    void emptyRowsAreNotReportedAndRowNumbersAreTheExcelOnes() {
        byte[] file = ImportWorkbooks.builder()
                .sheetWithRows("Plots", List.of(List.of("first"), List.of(), List.of(), List.of("fourth")))
                .build();

        assertThat(read(file, "Plots")).extracting(RawRow::number).containsExactly(1, 4);
    }

    @Test
    void aSheetIsFoundIgnoringCaseAndSurroundingSpacesAndAMissingOneIsReported() {
        byte[] file = ImportWorkbooks.builder().sheetWithRows(" Plots ", List.of(List.of("x"))).build();

        assertThat(read(file, "plots")).hasSize(1);
        try (WorkbookReader reader = WorkbookReader.open(file)) {
            assertThat(reader.readSheet("Members", row -> { throw new AssertionError(); })).isFalse();
        }
    }

    @Test
    void formulasAreReadAsTheirStoredResultAndErrorsAndFormulasWithoutResultAreEmpty() {
        byte[] file = ImportWorkbooks.handMade(Map.of("xl/worksheets/sheet1.xml", SHEET_HEAD + """
                <row r="1">
                <c r="A1"><f>1+1</f></c>
                <c r="B1"><f>1+1</f><v>2</v></c>
                <c r="C1" t="str"><f>"a"&amp;"b"</f><v>ab</v></c>
                <c r="D1" t="e"><v>#N/A</v></c>
                <c r="E1" t="b"><v>1</v></c>
                <c r="F1" t="inlineStr"><is><t>kept</t></is></c>
                </row>""" + SHEET_TAIL));

        RawRow row = read(file, "Plots").getFirst();

        assertThat(row.cell(0)).isNull();
        assertThat(((RawCell.Number) row.cell(1)).value()).isEqualByComparingTo("2");
        assertThat(row.cell(2)).isEqualTo(new RawCell.Text("ab"));
        assertThat(row.cell(3)).isNull();
        assertThat(row.cell(4)).isEqualTo(new RawCell.Bool(true));
        assertThat(row.cell(5)).isEqualTo(new RawCell.Text("kept"));
    }

    @Test
    void aDamagedSheetIsNotAWorkbook() {
        byte[] file = ImportWorkbooks.handMade(Map.of("xl/worksheets/sheet1.xml",
                SHEET_HEAD + "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row><row r=\"2\"><c r="));

        try (WorkbookReader reader = WorkbookReader.open(file)) {
            assertThatThrownBy(() -> reader.readSheet("Plots", row -> { }))
                    .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.code()).isEqualTo("FILE_NOT_XLSX");
                    });
        }
    }

    @Test
    void aWorkbookThatCannotBeOpenedIsNotAWorkbook() {
        byte[] file = ImportWorkbooks.handMade(Map.of("xl/workbook.xml", "<workbook><sheets>"));

        assertThatThrownBy(() -> WorkbookReader.open(file))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> assertThat(e.code()).isEqualTo("FILE_NOT_XLSX"));
    }

    @Test
    void aConsumerCanStopReadingByThrowingAProblem() {
        byte[] file = ImportWorkbooks.builder()
                .sheetWithRows("Plots", List.of(List.of("a"), List.of("b"), List.of("c")))
                .build();
        List<Integer> seen = new ArrayList<>();

        try (WorkbookReader reader = WorkbookReader.open(file)) {
            assertThatThrownBy(() -> reader.readSheet("Plots", row -> {
                seen.add(row.number());
                if (row.number() == 2) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, "IMPORT_LIMIT_EXCEEDED", "stop");
                }
            })).isInstanceOfSatisfying(ApiProblemException.class,
                    e -> assertThat(e.code()).isEqualTo("IMPORT_LIMIT_EXCEEDED"));
        }

        assertThat(seen).containsExactly(1, 2);
    }

    @Test
    void dateFormatsAreRecognisedByTheirLetters() {
        for (String format : new String[] {"dd.mm.yyyy", "yyyy-mm-dd hh:mm", "[$-409]d-mmm", "h:mm AM/PM", "DD/MM/YY"}) {
            assertThat(WorkbookReader.isDateFormat(format)).as(format).isTrue();
        }
        for (String format : new String[] {"General", "0.00", "#,##0.00", "0.0%", "@", "[Red]0.0", "0.0 \"m2\"",
                "0\\d", "0.00E+00"}) {
            assertThat(WorkbookReader.isDateFormat(format)).as(format).isFalse();
        }
        assertThat(WorkbookReader.isDateFormat(null)).isFalse();
    }
}
