package app.nadel.imports.internal;

import app.nadel.meters.MeterImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Reads the Meters sheet: finds the five columns from the header row and turns every data row into a
 * {@link MeterImportRow}. Only the shape of the sheet is checked here (headers, readable dates and numbers); the plot,
 * the resource, the serial number and the reading are checked by the meters module.
 * <p>
 * Like the Members and Ownership sheets it may be absent. A sheet that is there needs all five columns. Comment and
 * empty rows are skipped; Excel row numbers are kept. A resource word of either language ({@code ЕЛЕКТРОЕНЕРГІЯ}) is
 * turned into the canonical English word ({@code ELECTRICITY}); an unknown word is passed on as typed and reported by
 * the meters module.
 */
final class MetersSheet {

    /** The canonical (English) sheet name; the Ukrainian names come from {@link ImportVocabulary}. */
    static final String NAME = "Meters";
    /** Every name the sheet may have in a file. */
    static final List<String> NAMES = ImportVocabulary.get().sheetNames(ImportVocabulary.COLUMNS_METERS, NAME);
    /** More meter rows than this are refused, before the rest of the file is read. */
    static final int MAX_ROWS = 2000;

    private static final String ALIASES_KEY = ImportVocabulary.COLUMNS_METERS;

    private MetersSheet() {
    }

    /** The rows of the sheet and the problems found while reading them. */
    record Parsed(List<MeterImportRow> rows, List<RowProblem> problems) {

        static final Parsed EMPTY = new Parsed(List.of(), List.of());
    }

    /**
     * @throws ApiProblemException 400 {@code IMPORT_LIMIT_EXCEEDED} when the sheet has more than {@link #MAX_ROWS} rows
     */
    static Parsed parse(WorkbookReader reader) {
        Collector collector = new Collector(reader.date1904());
        if (!reader.readSheet(NAMES, collector)) {
            return Parsed.EMPTY;
        }
        if (collector.header == null) {
            // a sheet with no header row misses every column
            for (MeterColumn column : MeterColumn.values()) {
                collector.problems.add(new RowProblem(ImportSheet.METERS, 1, column.key(),
                        ProblemCodes.HEADER_MISSING));
            }
        }
        return new Parsed(collector.rows, collector.problems);
    }

    /** Receives the rows in file order: the header first, then the meters. */
    private static final class Collector implements Consumer<RawRow> {

        private final boolean date1904;
        private final List<MeterImportRow> rows = new ArrayList<>();
        private final List<RowProblem> problems = new ArrayList<>();
        private HeaderMatcher.@Nullable Match<MeterColumn> header;

        Collector(boolean date1904) {
            this.date1904 = date1904;
        }

        @Override
        public void accept(RawRow raw) {
            if (SheetRows.isEmpty(raw) || SheetRows.isComment(raw)) {
                return;
            }
            if (header == null) {
                header = HeaderMatcher.match(ALIASES_KEY, MeterColumn.class, raw);
                for (MeterColumn missing : header.missing()) {
                    problems.add(new RowProblem(ImportSheet.METERS, raw.number(), missing.key(),
                            ProblemCodes.HEADER_MISSING));
                }
            } else if (header.missing().isEmpty()) {
                if (rows.size() >= MAX_ROWS) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.IMPORT_LIMIT_EXCEEDED,
                            "The file has more than " + MAX_ROWS + " meter rows.");
                }
                rows.add(parseRow(raw, header));
            }
        }

        private MeterImportRow parseRow(RawRow raw, HeaderMatcher.Match<MeterColumn> header) {
            Cells.Result<LocalDate> date = Cells.date(header.cell(raw, MeterColumn.INSTALLED_ON), date1904);
            if (date.problem() != null) {
                problems.add(new RowProblem(ImportSheet.METERS, raw.number(), MeterColumn.INSTALLED_ON.key(),
                        date.problem()));
            }
            Cells.Result<BigDecimal> reading = Cells.number(header.cell(raw, MeterColumn.INITIAL_READING));
            if (reading.problem() != null) {
                problems.add(new RowProblem(ImportSheet.METERS, raw.number(), MeterColumn.INITIAL_READING.key(),
                        reading.problem()));
            }
            return new MeterImportRow(raw.number(),
                    Cells.text(header.cell(raw, MeterColumn.PLOT_NUMBER)).value(),
                    Cells.word(header.cell(raw, MeterColumn.RESOURCE), ImportVocabulary.RESOURCE).value(),
                    Cells.text(header.cell(raw, MeterColumn.SERIAL)).value(),
                    date.value(),
                    reading.value());
        }
    }
}
