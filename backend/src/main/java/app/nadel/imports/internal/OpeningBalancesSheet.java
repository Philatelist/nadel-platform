package app.nadel.imports.internal;

import app.nadel.ledger.OpeningBalanceImport;
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
 * Reads the Opening balances sheet (feature 011 §2.9): finds the columns from the header row and turns every data row
 * into an {@link OpeningBalanceImport.Row}. Only the shape of the sheet is checked here (headers, readable dates and
 * numbers); the plot, the charge type, the amount, the date and the reason are checked by the ledger.
 * <p>
 * Like the Members, Ownership and Meters sheets it may be absent. A sheet that is there needs the four required
 * columns. Comment and empty rows are skipped; Excel row numbers are kept.
 */
final class OpeningBalancesSheet {

    /** The canonical (English) sheet name; the Ukrainian names come from {@link ImportVocabulary}. */
    static final String NAME = "Opening balances";
    /** Every name the sheet may have in a file. */
    static final List<String> NAMES = ImportVocabulary.get().sheetNames(ImportVocabulary.COLUMNS_OPENING, NAME);
    /** More rows than this are refused, before the rest of the file is read. */
    static final int MAX_ROWS = 6000;

    private static final String ALIASES_KEY = ImportVocabulary.COLUMNS_OPENING;

    private OpeningBalancesSheet() {
    }

    /** The rows of the sheet and the problems found while reading them. */
    record Parsed(List<OpeningBalanceImport.Row> rows, List<RowProblem> problems) {

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
            // a sheet with no header row misses every required column
            for (OpeningBalanceColumn column : OpeningBalanceColumn.values()) {
                if (column.required()) {
                    collector.problems.add(new RowProblem(ImportSheet.OPENING_BALANCES, 1, column.key(),
                            ProblemCodes.HEADER_MISSING));
                }
            }
        }
        return new Parsed(collector.rows, collector.problems);
    }

    /** Receives the rows in file order: the header first, then the balances. */
    private static final class Collector implements Consumer<RawRow> {

        private final boolean date1904;
        private final List<OpeningBalanceImport.Row> rows = new ArrayList<>();
        private final List<RowProblem> problems = new ArrayList<>();
        private HeaderMatcher.@Nullable Match<OpeningBalanceColumn> header;

        Collector(boolean date1904) {
            this.date1904 = date1904;
        }

        @Override
        public void accept(RawRow raw) {
            if (SheetRows.isEmpty(raw) || SheetRows.isComment(raw)) {
                return;
            }
            if (header == null) {
                header = HeaderMatcher.match(ALIASES_KEY, OpeningBalanceColumn.class, raw);
                for (OpeningBalanceColumn missing : header.missing()) {
                    problems.add(new RowProblem(ImportSheet.OPENING_BALANCES, raw.number(), missing.key(),
                            ProblemCodes.HEADER_MISSING));
                }
            } else if (header.missing().isEmpty()) {
                if (rows.size() >= MAX_ROWS) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.IMPORT_LIMIT_EXCEEDED,
                            "The file has more than " + MAX_ROWS + " opening balance rows.");
                }
                rows.add(parseRow(raw, header));
            }
        }

        private OpeningBalanceImport.Row parseRow(RawRow raw, HeaderMatcher.Match<OpeningBalanceColumn> header) {
            Cells.Result<BigDecimal> amount = Cells.number(header.cell(raw, OpeningBalanceColumn.AMOUNT));
            if (amount.problem() != null) {
                problems.add(new RowProblem(ImportSheet.OPENING_BALANCES, raw.number(),
                        OpeningBalanceColumn.AMOUNT.key(), amount.problem()));
            }
            Cells.Result<LocalDate> date = Cells.date(header.cell(raw, OpeningBalanceColumn.DATE), date1904);
            if (date.problem() != null) {
                problems.add(new RowProblem(ImportSheet.OPENING_BALANCES, raw.number(),
                        OpeningBalanceColumn.DATE.key(), date.problem()));
            }
            return new OpeningBalanceImport.Row(raw.number(),
                    Cells.text(header.cell(raw, OpeningBalanceColumn.PLOT_NUMBER)).value(),
                    Cells.text(header.cell(raw, OpeningBalanceColumn.CHARGE_TYPE)).value(),
                    amount.value(),
                    date.value(),
                    Cells.text(header.cell(raw, OpeningBalanceColumn.REASON)).value());
        }
    }
}
