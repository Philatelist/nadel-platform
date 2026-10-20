package app.nadel.imports.internal;

import app.nadel.members.MemberImportRow;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ProblemCodes;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Reads the Members sheet: finds the columns from the header row and turns every data row into a
 * {@link MemberImportRow}. The rules about members (names, phones, e-mail, channel, matching) are checked by the
 * members module; only the shape of the sheet is checked here.
 * <p>
 * A sheet that is not in the workbook is not a problem: a file may contain plots only. A sheet that is there needs its
 * header row. Comment and empty rows are skipped as in the Plots sheet; Excel row numbers are kept.
 */
final class MembersSheet {

    static final String NAME = "Members";
    /** More member rows than this are refused, before the rest of the file is read. */
    static final int MAX_ROWS = 3000;

    private static final String ALIASES_KEY = "members";
    private static final Pattern PHONE_SEPARATOR = Pattern.compile("[,;\\n]");
    /** Digits of a Ukrainian mobile number without its leading zero, as Excel stores {@code 0671234567} in a number cell. */
    private static final int NATIONAL_DIGITS_WITHOUT_ZERO = 9;

    private MembersSheet() {
    }

    /** The rows of the sheet and the problems found while reading them. */
    record Parsed(List<MemberImportRow> rows, List<RowProblem> problems) {

        static final Parsed EMPTY = new Parsed(List.of(), List.of());
    }

    /**
     * @throws ApiProblemException 400 {@code IMPORT_LIMIT_EXCEEDED} when the sheet has more than {@link #MAX_ROWS} rows
     */
    static Parsed parse(WorkbookReader reader) {
        Collector collector = new Collector();
        if (!reader.readSheet(NAME, collector)) {
            return Parsed.EMPTY;
        }
        if (collector.header == null) {
            // a sheet with no header row misses every required column
            for (MemberColumn column : MemberColumn.values()) {
                if (column.required()) {
                    collector.problems.add(new RowProblem(ImportSheet.MEMBERS, 1, column.key(),
                            ProblemCodes.HEADER_MISSING));
                }
            }
        }
        return new Parsed(collector.rows, collector.problems);
    }

    /** Receives the rows in file order: the header first, then the members. */
    private static final class Collector implements Consumer<RawRow> {

        private final List<MemberImportRow> rows = new ArrayList<>();
        private final List<RowProblem> problems = new ArrayList<>();
        private HeaderMatcher.@Nullable Match<MemberColumn> header;

        @Override
        public void accept(RawRow raw) {
            if (SheetRows.isEmpty(raw) || SheetRows.isComment(raw)) {
                return;
            }
            if (header == null) {
                header = HeaderMatcher.match(ALIASES_KEY, MemberColumn.class, raw);
                for (MemberColumn missing : header.missing()) {
                    problems.add(new RowProblem(ImportSheet.MEMBERS, raw.number(), missing.key(),
                            ProblemCodes.HEADER_MISSING));
                }
            } else if (header.missing().isEmpty()) {
                if (rows.size() >= MAX_ROWS) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.IMPORT_LIMIT_EXCEEDED,
                            "The file has more than " + MAX_ROWS + " members.");
                }
                rows.add(parseRow(raw, header));
            }
        }
    }

    private static MemberImportRow parseRow(RawRow raw, HeaderMatcher.Match<MemberColumn> header) {
        return new MemberImportRow(raw.number(),
                text(raw, header, MemberColumn.EXTERNAL_ID),
                text(raw, header, MemberColumn.LAST_NAME),
                text(raw, header, MemberColumn.FIRST_NAME),
                text(raw, header, MemberColumn.MIDDLE_NAME),
                phones(phoneText(header.cell(raw, MemberColumn.PHONES))),
                text(raw, header, MemberColumn.EMAIL),
                text(raw, header, MemberColumn.POSTAL_ADDRESS),
                text(raw, header, MemberColumn.DELIVERY_CHANNEL),
                text(raw, header, MemberColumn.TELEGRAM_USERNAME),
                phoneText(header.cell(raw, MemberColumn.VIBER_PHONE)));
    }

    private static @Nullable String text(RawRow raw, HeaderMatcher.Match<MemberColumn> header, MemberColumn column) {
        return Cells.text(header.cell(raw, column)).value();
    }

    /** The phones typed in one cell, separated by commas or semicolons; blank pieces are dropped. */
    static List<String> phones(@Nullable String cell) {
        if (cell == null) {
            return List.of();
        }
        return Arrays.stream(PHONE_SEPARATOR.split(cell))
                .map(String::strip)
                .filter(piece -> !piece.isEmpty())
                .toList();
    }

    /**
     * The text of a phone cell. A cell Excel stored as a number has lost its leading zero ({@code 0671234567} is
     * {@code 671234567}); nine digits are a Ukrainian number without it, so the zero is put back. Other numbers are
     * taken as they are. A template cell is formatted as text, so this only helps files typed in by hand.
     */
    static @Nullable String phoneText(@Nullable RawCell cell) {
        String text = Cells.text(cell).value();
        if (cell instanceof RawCell.Number number && text != null) {
            BigDecimal value = number.value();
            if (text.length() == NATIONAL_DIGITS_WITHOUT_ZERO && text.chars().allMatch(Character::isDigit)
                    && value.signum() > 0) {
                return "0" + text;
            }
        }
        return text;
    }
}
