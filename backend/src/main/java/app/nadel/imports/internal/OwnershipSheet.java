package app.nadel.imports.internal;

import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Reads the Ownership sheet: finds the four columns from the header row and turns every data row into a
 * {@link ParsedRow}. Only the shape of the sheet is checked here (headers, readable dates); the plot, the member and the
 * shares are checked by the planner and the plots module.
 * <p>
 * Like the Members sheet it may be absent: a file may hold plots (and members) only. A sheet that is there needs all
 * four columns. Comment and empty rows are skipped; Excel row numbers are kept.
 */
final class OwnershipSheet {

    /** The canonical (English) sheet name; the Ukrainian names come from {@link ImportVocabulary}. */
    static final String NAME = "Ownership";
    /** Every name the sheet may have in a file. */
    static final List<String> NAMES = ImportVocabulary.get().sheetNames(ImportVocabulary.COLUMNS_OWNERSHIP, NAME);
    /** More ownership rows than this are refused, before the rest of the file is read. */
    static final int MAX_ROWS = 6000;

    private static final String ALIASES_KEY = ImportVocabulary.COLUMNS_OWNERSHIP;

    private OwnershipSheet() {
    }

    /**
     * One ownership row as typed: trimmed text for the plot number, the member key and the share, and the date, null
     * when the cell is empty or its date could not be read (which is then in {@link Parsed#problems()}).
     */
    record ParsedRow(int row, @Nullable String plotNumber, @Nullable String memberKey, @Nullable String share,
            @Nullable LocalDate validFrom) {
    }

    /** The rows of the sheet and the problems found while reading them. */
    record Parsed(List<ParsedRow> rows, List<RowProblem> problems) {

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
            for (OwnershipColumn column : OwnershipColumn.values()) {
                collector.problems.add(new RowProblem(ImportSheet.OWNERSHIP, 1, column.key(),
                        ProblemCodes.HEADER_MISSING));
            }
        }
        return new Parsed(collector.rows, collector.problems);
    }

    /** Receives the rows in file order: the header first, then the owners. */
    private static final class Collector implements Consumer<RawRow> {

        private final boolean date1904;
        private final List<ParsedRow> rows = new ArrayList<>();
        private final List<RowProblem> problems = new ArrayList<>();
        private HeaderMatcher.@Nullable Match<OwnershipColumn> header;

        Collector(boolean date1904) {
            this.date1904 = date1904;
        }

        @Override
        public void accept(RawRow raw) {
            if (SheetRows.isEmpty(raw) || SheetRows.isComment(raw)) {
                return;
            }
            if (header == null) {
                header = HeaderMatcher.match(ALIASES_KEY, OwnershipColumn.class, raw);
                for (OwnershipColumn missing : header.missing()) {
                    problems.add(new RowProblem(ImportSheet.OWNERSHIP, raw.number(), missing.key(),
                            ProblemCodes.HEADER_MISSING));
                }
            } else if (header.missing().isEmpty()) {
                if (rows.size() >= MAX_ROWS) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.IMPORT_LIMIT_EXCEEDED,
                            "The file has more than " + MAX_ROWS + " ownership rows.");
                }
                rows.add(parseRow(raw, header));
            }
        }

        private ParsedRow parseRow(RawRow raw, HeaderMatcher.Match<OwnershipColumn> header) {
            Cells.Result<LocalDate> date = Cells.date(header.cell(raw, OwnershipColumn.VALID_FROM), date1904);
            if (date.problem() != null) {
                problems.add(new RowProblem(ImportSheet.OWNERSHIP, raw.number(), OwnershipColumn.VALID_FROM.key(),
                        date.problem()));
            }
            return new ParsedRow(raw.number(),
                    text(raw, header, OwnershipColumn.PLOT_NUMBER),
                    text(raw, header, OwnershipColumn.MEMBER_EXTERNAL_ID),
                    text(raw, header, OwnershipColumn.SHARE),
                    date.value());
        }
    }

    private static @Nullable String text(RawRow raw, HeaderMatcher.Match<OwnershipColumn> header,
            OwnershipColumn column) {
        return Cells.text(header.cell(raw, column)).value();
    }
}
