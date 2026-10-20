package app.nadel.imports.internal;

import app.nadel.problem.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.jspecify.annotations.Nullable;

/**
 * Streams the rows of the sheets of a workbook. The only class that uses the spreadsheet library, so the rest of the
 * import never sees it. Rows are read one at a time and handed to the caller, which can stop by throwing.
 * <p>
 * The library reads the XML with DTDs and external entities switched off. Whatever the library or the XML parser
 * throws on a damaged file is answered as {@code FILE_NOT_XLSX}. File names, sheet names and cell values are never
 * logged or put into an exception message.
 */
final class WorkbookReader implements AutoCloseable {

    /** The cell format is read to tell date cells (a number shown as a date) from plain numbers. */
    private static final ReadingOptions OPTIONS = new ReadingOptions(true, false);
    /** Quoted text, escaped characters and bracketed sections ({@code [Red]}, {@code [$-409]}) of a number format. */
    private static final Pattern FORMAT_NOISE = Pattern.compile("\"[^\"]*\"|\\\\.|\\[[^\\]]*]|_.|\\*.");
    private static final Pattern DATE_LETTERS = Pattern.compile("[ymdhs]", Pattern.CASE_INSENSITIVE);

    private final ReadableWorkbook workbook;

    private WorkbookReader(ReadableWorkbook workbook) {
        this.workbook = workbook;
    }

    /** @throws ApiProblemException 400 {@code FILE_NOT_XLSX} when the workbook cannot be opened */
    static WorkbookReader open(byte[] content) {
        try {
            return new WorkbookReader(new ReadableWorkbook(new ByteArrayInputStream(content), OPTIONS));
        } catch (IOException | RuntimeException e) {
            throw ZipGuard.notXlsx();
        }
    }

    /**
     * Streams the rows of the sheet with this name (ignoring case and surrounding spaces) to {@code consumer}, in file
     * order, skipping rows with no cells. The consumer may throw an {@link ApiProblemException} to stop reading.
     *
     * @return false when there is no such sheet
     * @throws ApiProblemException {@code FILE_NOT_XLSX} when the sheet cannot be read
     */
    boolean readSheet(String name, Consumer<RawRow> consumer) {
        Optional<Sheet> sheet = workbook.getSheets()
                .filter(candidate -> candidate.getName().strip().equalsIgnoreCase(name))
                .findFirst();
        if (sheet.isEmpty()) {
            return false;
        }
        try (Stream<Row> rows = sheet.get().openStream()) {
            rows.forEach(row -> consumer.accept(toRaw(row)));
        } catch (ApiProblemException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw ZipGuard.notXlsx();
        }
        return true;
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            // nothing was written; a failure to release the in-memory archive is not worth failing the request for
        }
    }

    private static RawRow toRaw(Row row) {
        List<@Nullable RawCell> cells = new ArrayList<>();
        for (Cell cell : row) {
            // the library hands out null for a cell that is not in the file between two that are
            RawCell raw = cell == null ? null : toRaw(cell);
            if (raw == null) {
                continue;
            }
            int column = cell.getColumnIndex();
            while (cells.size() <= column) {
                cells.add(null);
            }
            cells.set(column, raw);
        }
        return new RawRow(row.getRowNum(), cells);
    }

    /** A formula is read as its stored result; an error value and an empty cell carry nothing to import. */
    private static @Nullable RawCell toRaw(Cell cell) {
        return switch (cell.getType()) {
            case STRING, NUMBER, BOOLEAN, FORMULA -> switch (cell.getValue()) {
                case String text -> new RawCell.Text(text);
                case BigDecimal number -> new RawCell.Number(number, isDateFormat(cell.getDataFormatString()));
                case Boolean flag -> new RawCell.Bool(flag);
                case null, default -> null;
            };
            case ERROR, EMPTY -> null;
        };
    }

    /** A number format is a date format when, ignoring quoted text and brackets, it has year, month, day or time letters. */
    static boolean isDateFormat(@Nullable String format) {
        if (format == null) {
            return false;
        }
        return DATE_LETTERS.matcher(FORMAT_NOISE.matcher(format).replaceAll("")).find();
    }
}
