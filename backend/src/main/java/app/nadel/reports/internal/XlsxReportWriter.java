package app.nadel.reports.internal;

import app.nadel.money.Money;
import app.nadel.reports.internal.ReportResult.Cell;
import app.nadel.reports.internal.ReportResult.Column;
import app.nadel.reports.internal.ReportResult.Row;
import app.nadel.reports.internal.ReportResult.Sheet;
import app.nadel.reports.internal.ReportResult.Total;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportRowKind;
import org.dhatim.fastexcel.StyleSetter;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Writes a report as an {@code .xlsx} workbook with fastexcel: one worksheet per sheet of the result, every row, the
 * same totals as the preview. Each worksheet starts with the header block (the cooperative's name, the report's name,
 * the filters, when it was made in Europe/Kyiv and who made it), then one line per header note, an empty line and the
 * column headers, which stay frozen. Numbers are numbers ({@code #,##0.00} for money, {@code #,##0.000} for kWh and
 * m³, {@code 0.00} for percentages, {@code #,##0} for whole numbers, {@code #,##0.00##} for rates and areas), days
 * are dates ({@code dd.mm.yyyy}) and months the date of their first day shown as {@code mm.yyyy}. A subtotal and a
 * totals line are bold and name themselves in their first empty text cell; a row of a tree is indented by its level.
 * A value that is not known is an empty cell with its reason as a comment, never 0. Texts come from
 * {@link ReportTexts}; plot numbers, names and other content people typed are written as stored.
 */
@Component
class XlsxReportWriter {

    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int HEADER_BLOCK_ROWS = 5;
    private static final int MAX_SHEET_NAME = 31;
    /** A rate or an area: at least two decimals, the third and fourth only when they are not zero. */
    static final String DECIMAL_FORMAT = "#,##0.00##";

    /** The header block of every worksheet. */
    record Header(String cooperativeName, ZonedDateTime generatedAt, String generatedBy) {
    }

    /**
     * @param chargeTypeNames the name of a charge type in the file's language (a customised name as stored)
     * @return the workbook
     */
    byte[] write(ReportDefinition definition, ReportFilters filters, ReportResult result, Header header,
            ReportTexts texts, Function<UUID, String> chargeTypeNames) {
        String code = definition.code().getValue();
        String filterLine = texts.text("reports.file.filters") + ": " + definition.filters().stream()
                .map(filter -> filter.describe(filters, texts, chargeTypeNames)).collect(Collectors.joining("; "));
        List<String> headerLines = List.of(header.cooperativeName(), texts.text("reports." + code + ".title"),
                filterLine,
                texts.text("reports.file.generatedAt") + ": " + GENERATED_AT.format(header.generatedAt()),
                texts.text("reports.file.generatedBy") + ": " + header.generatedBy());
        List<String> notes = result.notes().stream()
                .map(note -> texts.text("reports.notes." + note.code().getValue(), note.params())).toList();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Workbook workbook = new Workbook(bytes, "Nadel", "1.0")) {
            Set<String> names = new HashSet<>();
            for (Sheet sheet : result.sheets()) {
                Worksheet worksheet = workbook.newWorksheet(sheetName(texts.text("reports." + code + ".sheets."
                        + sheet.key()), names));
                new SheetWriter(worksheet, code, texts, chargeTypeNames).write(sheet, headerLines, notes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the report", e);
        }
        return bytes.toByteArray();
    }

    /** A worksheet name Excel accepts: no {@code []:*?/\}, at most 31 characters, unique in the workbook. */
    static String sheetName(String text, Set<String> taken) {
        String clean = text.replaceAll("[\\[\\]:*?/\\\\]", " ").strip();
        String name = clean.length() > MAX_SHEET_NAME ? clean.substring(0, MAX_SHEET_NAME) : clean;
        int suffix = 2;
        while (!taken.add(name.toLowerCase())) {
            String tail = " " + suffix++;
            name = (clean.length() + tail.length() > MAX_SHEET_NAME
                    ? clean.substring(0, MAX_SHEET_NAME - tail.length()) : clean) + tail;
        }
        return name;
    }

    /** Writes one worksheet. */
    private static final class SheetWriter {

        private final Worksheet worksheet;
        private final String code;
        private final ReportTexts texts;
        private final Function<UUID, String> chargeTypeNames;

        SheetWriter(Worksheet worksheet, String code, ReportTexts texts, Function<UUID, String> chargeTypeNames) {
            this.worksheet = worksheet;
            this.code = code;
            this.texts = texts;
            this.chargeTypeNames = chargeTypeNames;
        }

        void write(Sheet sheet, List<String> headerLines, List<String> notes) {
            for (int index = 0; index < HEADER_BLOCK_ROWS; index++) {
                worksheet.value(index, 0, headerLines.get(index));
                if (index < 2) {
                    worksheet.style(index, 0).bold().set();
                }
            }
            int row = HEADER_BLOCK_ROWS;
            for (String note : notes) {
                worksheet.value(row, 0, note);
                worksheet.style(row, 0).italic().set();
                row++;
            }
            int headerRow = row + 1;
            List<Column> columns = sheet.columns();
            for (int index = 0; index < columns.size(); index++) {
                Column column = columns.get(index);
                worksheet.value(headerRow, index, header(column));
                worksheet.style(headerRow, index).bold().wrapText(true).verticalAlignment("top").set();
                worksheet.width(index, width(column.type()));
            }
            worksheet.freezePane(0, headerRow + 1);
            row = headerRow + 1;
            for (Row data : sheet.rows()) {
                String label = data.kind() == ReportRowKind.SUBTOTAL ? texts.text("reports.rows.SUBTOTAL") : null;
                writeLine(row++, columns, data.cells(), data.kind() == ReportRowKind.SUBTOTAL, data.level(), label);
            }
            for (Total total : sheet.totals()) {
                writeLine(row++, columns, total.cells(), true, 0, texts.text("reports.totals." + total.key()));
            }
        }

        private String header(Column column) {
            if (column.chargeTypeId() != null) {
                return chargeTypeNames.apply(column.chargeTypeId());
            }
            return texts.text("reports." + code + ".columns." + column.key());
        }

        private void writeLine(int row, List<Column> columns, List<Cell> cells, boolean bold, int level,
                @Nullable String label) {
            int labelColumn = label == null ? -1 : labelColumn(columns, cells);
            for (int index = 0; index < columns.size(); index++) {
                Column column = columns.get(index);
                Cell cell = cells.get(index);
                String format = null;
                if (index == labelColumn) {
                    worksheet.value(row, index, label);
                } else if (cell.value() != null) {
                    format = value(row, index, column, cell.value());
                } else if (cell.reason() != null) {
                    worksheet.comment(row, index, texts.text("reports.reasons." + cell.reason().getValue()));
                }
                if (format != null || bold || (level > 0 && index == 0)) {
                    StyleSetter style = worksheet.style(row, index);
                    if (format != null) {
                        style.format(format);
                    }
                    if (bold) {
                        style.bold();
                    }
                    if (level > 0 && index == 0) {
                        style.indent(level);
                    }
                    style.set();
                }
            }
        }

        /** The first empty text, code or charge type cell of a subtotal or totals line, which names the line. */
        private static int labelColumn(List<Column> columns, List<Cell> cells) {
            for (int index = 0; index < columns.size(); index++) {
                ReportColumnType type = columns.get(index).type();
                boolean text = type == ReportColumnType.TEXT || type == ReportColumnType.CODE
                        || type == ReportColumnType.CHARGE_TYPE;
                if (text && cells.get(index).value() == null && cells.get(index).reason() == null) {
                    return index;
                }
            }
            return -1;
        }

        /** Writes a value by the column type and returns its number format, null for text. */
        private @Nullable String value(int row, int index, Column column, Object value) {
            return switch (column.type()) {
                case MONEY -> number(row, index, ((Money) value).value(), "#,##0.00");
                case QUANTITY -> number(row, index, (BigDecimal) value, "#,##0.000");
                case PERCENT -> number(row, index, (BigDecimal) value, "0.00");
                case DECIMAL -> number(row, index, (BigDecimal) value, DECIMAL_FORMAT);
                case INTEGER -> number(row, index, (Number) value, "#,##0");
                case DATE -> {
                    worksheet.value(row, index, (LocalDate) value);
                    yield "dd.mm.yyyy";
                }
                case MONTH -> {
                    worksheet.value(row, index, ((YearMonth) value).atDay(1));
                    yield "mm.yyyy";
                }
                case BOOLEAN -> {
                    worksheet.value(row, index, (Boolean) value);
                    yield null;
                }
                case CODE -> text(row, index, texts.text(column.codes() + "." + value));
                case CHARGE_TYPE -> text(row, index, chargeTypeNames.apply((UUID) value));
                case TEXT -> text(row, index, (String) value);
            };
        }

        private String number(int row, int index, Number value, String format) {
            worksheet.value(row, index, value);
            return format;
        }

        private @Nullable String text(int row, int index, String value) {
            worksheet.value(row, index, value);
            return null;
        }

        private static double width(ReportColumnType type) {
            return switch (type) {
                case TEXT, CHARGE_TYPE -> 24;
                case CODE -> 16;
                case MONEY, QUANTITY -> 15;
                case DATE, DECIMAL -> 12;
                case MONTH, PERCENT, INTEGER, BOOLEAN -> 10;
            };
        }
    }
}
