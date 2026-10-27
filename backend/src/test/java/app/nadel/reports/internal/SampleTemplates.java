package app.nadel.reports.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Sample report templates built with the spreadsheet library, never kept as binary files: the tests fill them, and
 * {@link #main} writes them to a folder for a look in Excel or LibreOffice (and for the demo data of slice 10).
 * {@code TemplateFillerTests} runs it on every test run, so {@code target/sample-templates/} holds the current files.
 */
final class SampleTemplates {

    /** The file name of {@link #landTaxStatement()}. */
    static final String LAND_TAX_STATEMENT = "sample-land-tax-statement.xlsx";
    /** The sheet of {@link #landTaxStatement()}. */
    static final String LAND_TAX_SHEET = "Відомість";

    private SampleTemplates() {
    }

    /**
     * "Land tax statement": a merged bold title with the official name, the ЄДРПОУ and the year in the header, the
     * column headers (bold) on row 4, the repeating row 5 of plots (number, area {@code #,##0.00}, rate
     * {@code #,##0.0000}, expected tax {@code #,##0.00}) and the totals row 6: {@code =SUM(D5:D5)} under the tax and
     * {@code ${totals.landTax.expected}} beside it, both bold {@code #,##0.00}.
     * <pre>
     *   | A                                         | B             | C                         | D                            | E
     * 1 | Відомість земельного податку ${cooperative.officialName} (merged A1:E1, bold)
     * 2 | ЄДРПОУ                                    | ${cooperative.edrpou}
     * 3 | Рік                                       | ${period.year}
     * 4 | Ділянка                                   | Площа, м²     | Ставка, грн за сотку      | Податок, грн                 | За звітом, грн
     * 5 | ${rows.plots.number}                      | ${rows.plots.area} | ${rows.plots.landTax.rate} | ${rows.plots.landTax.expected}
     * 6 | Разом                                     |               |                           | =SUM(D5:D5)                  | ${totals.landTax.expected}
     * </pre>
     */
    static byte[] landTaxStatement() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(out, "Nadel", "1.0")) {
            Worksheet sheet = workbook.newWorksheet(LAND_TAX_SHEET);
            sheet.value(0, 0, "Відомість земельного податку ${cooperative.officialName}");
            sheet.range(0, 0, 0, 4).merge();
            sheet.style(0, 0).bold().set();
            sheet.value(1, 0, "ЄДРПОУ");
            sheet.value(1, 1, "${cooperative.edrpou}");
            sheet.value(2, 0, "Рік");
            sheet.value(2, 1, "${period.year}");
            String[] headers = {"Ділянка", "Площа, м²", "Ставка, грн за сотку", "Податок, грн", "За звітом, грн"};
            for (int column = 0; column < headers.length; column++) {
                sheet.value(3, column, headers[column]);
                sheet.style(3, column).bold().set();
            }
            sheet.value(4, 0, "${rows.plots.number}");
            sheet.value(4, 1, "${rows.plots.area}");
            sheet.style(4, 1).format("#,##0.00").set();
            sheet.value(4, 2, "${rows.plots.landTax.rate}");
            sheet.style(4, 2).format("#,##0.0000").set();
            sheet.value(4, 3, "${rows.plots.landTax.expected}");
            sheet.style(4, 3).format("#,##0.00").set();
            sheet.value(5, 0, "Разом");
            sheet.style(5, 0).bold().set();
            sheet.formula(5, 3, "SUM(D5:D5)");
            sheet.style(5, 3).bold().format("#,##0.00").set();
            sheet.value(5, 4, "${totals.landTax.expected}");
            sheet.style(5, 4).bold().format("#,##0.00").set();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Writes the samples into the folder given (default {@code target/sample-templates}). */
    static void main(String[] args) throws IOException {
        Path folder = Files.createDirectories(Path.of(args.length > 0 ? args[0] : "target/sample-templates"));
        Files.write(folder.resolve(LAND_TAX_STATEMENT), landTaxStatement());
    }
}
