package app.nadel.imports.internal;

import static app.nadel.imports.internal.ImportWorkbooks.OWNERSHIP_HEADER;
import static app.nadel.imports.internal.ImportWorkbooks.owner;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Ownership sheet reader: headers, rows, dates and the date system of the workbook. */
class OwnershipSheetTests {

    private static final String SHEET_HEAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""";
    private static final String SHEET_TAIL = "</sheetData></worksheet>";

    private static OwnershipSheet.Parsed parse(byte[] workbook) {
        try (WorkbookReader reader = WorkbookReader.open(workbook)) {
            return OwnershipSheet.parse(reader);
        }
    }

    @Test
    void aWorkbookWithoutTheSheetHasNoRowsAndNoProblems() {
        byte[] file = ImportWorkbooks.builder().sheetWithRows("Plots", List.of(List.of("number"))).build();

        assertThat(parse(file)).isSameAs(OwnershipSheet.Parsed.EMPTY);
    }

    @Test
    void rowsKeepTheirTextAndExcelRowNumbersAndEmptyCellsAreNull() {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Ownership", OWNERSHIP_HEADER,
                        owner(" 12а ", "M-1", "1/2", LocalDate.of(2019, 4, 15)),
                        owner(7, null, null, "15.04.2019"))
                .build();

        OwnershipSheet.Parsed parsed = parse(file);

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rows()).containsExactly(
                new OwnershipSheet.ParsedRow(2, "12а", "M-1", "1/2", LocalDate.of(2019, 4, 15)),
                new OwnershipSheet.ParsedRow(3, "7", null, null, LocalDate.of(2019, 4, 15)));
    }

    @Test
    void anUnreadableDateIsAProblemOfTheDateColumnAndLeavesTheDateEmpty() {
        byte[] file = ImportWorkbooks.builder()
                .sheet("Ownership", OWNERSHIP_HEADER, owner("1", "M-1", "1", "31.02.2020"), owner("2", "M-1", "1", 5))
                .build();

        OwnershipSheet.Parsed parsed = parse(file);

        assertThat(parsed.rows()).extracting(OwnershipSheet.ParsedRow::validFrom).containsOnlyNulls();
        assertThat(parsed.problems()).containsExactly(
                new RowProblem(ImportSheet.OWNERSHIP, 2, "valid_from", "DATE_INVALID"),
                new RowProblem(ImportSheet.OWNERSHIP, 3, "valid_from", "DATE_INVALID"));
    }

    @Test
    void theDateSystemOfTheWorkbookIsHonoured() {
        assertThat(dateOf(false)).isEqualTo(LocalDate.of(2021, 6, 5));
        assertThat(dateOf(true)).isEqualTo(LocalDate.of(2025, 6, 6));
    }

    /** The date of serial 44352 in a hand-made workbook that counts from 1900 or from 1904. */
    private static LocalDate dateOf(boolean date1904) {
        String workbook = """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <workbookPr date1904="%s"/>
                <sheets><sheet name="Ownership" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""".formatted(date1904);
        String styles = """
                <?xml version="1.0" encoding="UTF-8"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <fonts count="1"><font><sz val="11"/></font></fonts>
                <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                <borders count="1"><border/></borders>
                <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                <xf numFmtId="14" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>
                </styleSheet>""";
        String sheet = SHEET_HEAD + """
                <row r="1"><c r="A1" t="inlineStr"><is><t>plot_number</t></is></c><c r="B1" t="inlineStr"><is><t>member_external_id</t></is></c>
                <c r="C1" t="inlineStr"><is><t>share</t></is></c><c r="D1" t="inlineStr"><is><t>valid_from</t></is></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>1</t></is></c><c r="B2" t="inlineStr"><is><t>M-1</t></is></c>
                <c r="C2" t="inlineStr"><is><t>1</t></is></c><c r="D2" s="1"><v>44352</v></c></row>
                """ + SHEET_TAIL;
        OwnershipSheet.Parsed parsed = parse(ImportWorkbooks.handMade(Map.of("xl/workbook.xml", workbook,
                "xl/styles.xml", styles, "xl/worksheets/sheet1.xml", sheet)));
        assertThat(parsed.problems()).isEmpty();
        return parsed.rows().getFirst().validFrom();
    }
}
