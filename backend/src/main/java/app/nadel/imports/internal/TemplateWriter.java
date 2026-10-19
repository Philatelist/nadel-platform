package app.nadel.imports.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/**
 * Builds the import template: three sheets (Plots, Members, Ownership) with English canonical headers, one instruction
 * row and one example row. Both of those rows start with {@code #}, which the import skips. Columns that carry
 * identifiers or phone numbers are formatted as text so that Excel keeps leading zeros and long digit strings.
 */
final class TemplateWriter {

    /** Marks a row the import ignores: instructions and the example. */
    static final String COMMENT_PREFIX = "#";

    private static final String TEXT_FORMAT = "@";
    private static final int HEADER_ROW = 0;
    private static final int INSTRUCTION_ROW = 1;
    private static final int EXAMPLE_ROW = 2;

    /**
     * @param header      canonical column header
     * @param instruction short English hint (the first cell of the row gets the {@code #} prefix)
     * @param example     realistic example value (the first cell of the row gets the {@code #} prefix)
     * @param text        whether the column has the text format
     */
    private record Column(String header, String instruction, String example, boolean text) {
    }

    private record Sheet(String name, List<Column> columns) {
    }

    private static final List<Sheet> SHEETS = List.of(
            new Sheet("Plots", List.of(
                    new Column("number", "Required. Plot number, unique in the cooperative", "12a", true),
                    new Column("street", "Street name", "Garden street", false),
                    new Column("kind", "PLOT or COMMON (common land). Required for a new plot", "PLOT", false),
                    new Column("area_m2", "Area in square metres. Fill exactly one of area_m2 and area_sotky",
                            "600", false),
                    new Column("area_sotky", "Area in sotky (1 sotka = 100 m2). Fill exactly one of the two", "",
                            false),
                    new Column("cadastral_number", "Optional. Format 1234567890:12:345:6789",
                            "1234567890:12:345:6789", true),
                    new Column("has_building", "yes or no. Default no", "yes", false),
                    new Column("notes", "Free text", "Corner plot", false))),
            new Sheet("Members", List.of(
                    new Column("external_id", "Required. Any key unique within this file, used by the Ownership sheet",
                            "M-001", true),
                    new Column("last_name", "Required", "Shevchenko", false),
                    new Column("first_name", "Required", "Taras", false),
                    new Column("middle_name", "Optional", "Hryhorovych", false),
                    new Column("phones", "Comma-separated, international format", "+380501234567, +380671234567",
                            true),
                    new Column("email", "Optional. Used to match an existing member on re-import",
                            "taras@example.com", false),
                    new Column("postal_address", "Optional", "1 Garden street, Kyiv", false),
                    new Column("delivery_channel", "EMAIL, TELEGRAM, VIBER or PAPER. The channel needs its contact",
                            "EMAIL", false),
                    new Column("telegram_username", "Needed for the TELEGRAM channel", "", false),
                    new Column("viber_phone", "Needed for the VIBER channel", "", true))),
            new Sheet("Ownership", List.of(
                    new Column("plot_number", "Required. Number of a plot from the Plots sheet or an existing plot",
                            "12a", true),
                    new Column("member_external_id", "Required. external_id from the Members sheet", "M-001", true),
                    new Column("share", "Share of the plot, for example 1, 1/2 or 1/3. Shares of a plot add up to 1",
                            "1", false),
                    new Column("valid_from", "Owner since, DD.MM.YYYY. The same date for every owner of a plot",
                            "01.03.2020", false))));

    /** @return the template as an {@code .xlsx} file */
    byte[] write() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(bytes, "Nadel", "1.0")) {
            for (Sheet sheet : SHEETS) {
                writeSheet(workbook.newWorksheet(sheet.name()), sheet.columns());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the import template", e);
        }
        return bytes.toByteArray();
    }

    private static void writeSheet(Worksheet worksheet, List<Column> columns) {
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index);
            if (column.text()) {
                worksheet.style(index).format(TEXT_FORMAT).set();
            }
            worksheet.width(index, Math.max(16, column.header().length() + 4));
            worksheet.value(HEADER_ROW, index, column.header());
            worksheet.style(HEADER_ROW, index).bold().set();
            worksheet.value(INSTRUCTION_ROW, index, prefixed(index, "", column.instruction()));
            if (index == 0 || !column.example().isEmpty()) {
                worksheet.value(EXAMPLE_ROW, index, prefixed(index, "example: ", column.example()));
            }
            worksheet.style(INSTRUCTION_ROW, index).italic().wrapText(true).fontColor("808080").set();
            worksheet.style(EXAMPLE_ROW, index).italic().fontColor("808080").set();
        }
        worksheet.freezePane(0, 1);
    }

    /** The first cell of a comment row carries the {@code #} marker; the other cells hold the plain text. */
    private static String prefixed(int columnIndex, String label, String text) {
        return columnIndex == 0 ? COMMENT_PREFIX + " " + label + text : text;
    }
}
