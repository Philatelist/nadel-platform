package app.nadel.reports.internal;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites the A1 references of a formula, or of any A1 reference text (a merged range, an {@code sqref} list such as
 * {@code A5:C5 E7}, a defined name), when the repeating row of a template sheet is filled (ADR 0015). The repeating
 * row {@code row} of sheet {@code sheet} is replaced by {@code copies} copies, so every row below it moves by
 * {@code copies - 1}; with no copies the row is removed and the rows below move up by one.
 * <p>
 * <b>Semantics (Excel's rule for inserting rows inside a range):</b>
 * <ul>
 *   <li>Rows are inserted, not cells moved, so a reference to a row below the repeating row moves whether or not its
 *       row is absolute: {@code $D$9} becomes {@code $D$11} with three copies, as Excel does when rows are inserted.
 *       The {@code $} markers are kept as written. Columns never change.</li>
 *   <li>A reference above the repeating row stays. A single cell on the repeating row stays (it is the first
 *       copy).</li>
 *   <li>A range (a cell range or a whole-row range such as {@code 5:5}) whose last row is the repeating row or below
 *       grows to the last copy, and one that starts below it moves: {@code SUM(D5:D5)} becomes {@code SUM(D5:D7)} with
 *       three copies, {@code D4:D5} becomes {@code D4:D7}. The repeating row counts as inside every range that starts
 *       or ends on it, which is what a template author means by summing the repeated rows.</li>
 *   <li>Whole-column references ({@code A:A}, {@code $B:$D}) never change.</li>
 *   <li>With no copies (an empty row set) a single cell on the removed row becomes {@code #REF!}, a range loses the
 *       row ({@code D4:D6} becomes {@code D4:D5}) and a range lying only on it becomes {@code #REF!}; a sheet qualifier
 *       is kept ({@code Sheet1!#REF!}), as Excel writes it.</li>
 *   <li>A row that would move past row {@value #MAX_ROW} becomes {@code #REF!}.</li>
 * </ul>
 * <b>Which references move:</b> only those that point at the repeated sheet: an unqualified reference when the
 * formula lives on that sheet, and a qualified one ({@code Sheet1!A5}, {@code 'My Sheet'!A5:B9}, quotes doubled
 * inside) whose sheet name equals it ignoring case, from any sheet. Left as written: string literals ({@code "A5"}),
 * error literals ({@code #REF!}, {@code #N/A}), function names ({@code LOG10(}), names that only look like a reference
 * ({@code A1B}, {@code XFE1}, {@code R5C3}), numbers, structured references ({@code Table1[A5]}), references into
 * another workbook ({@code [1]Sheet1!A5}, removed at upload anyway) and three-dimensional references
 * ({@code Sheet1:Sheet3!A5}). R1C1 notation is out of scope: the file format stores formulas in A1 notation only.
 * <p>
 * <b>The copies themselves</b> ({@link #copy}): a formula on the repeating row is written into every copy the way
 * Excel pastes a copied row (fill down): its relative row parts move with the copy, so {@code D5*E5} in the third
 * copy is {@code D7*E7} and a running total {@code SUM(D$5:D5)} becomes {@code SUM(D$5:D7)}; absolute row parts stay
 * on their row; and a reference to a row below the repeating row first moves by the inserted rows. Relative
 * references to other sheets move with the copy too, as when pasting. Ranges do not grow inside a copy:
 * {@code SUM(D$5:D$5)} on the repeating row is the first copy only (refer to a totals row instead).
 * <p>
 * {@link #offset} is the plain paste rule in both directions, used to turn a shared formula into the formula of each
 * cell it covers before the formulas are shifted. No formula is ever evaluated. Instances are immutable and
 * thread-safe.
 */
final class FormulaShifter {

    /** The last row of a sheet. */
    static final int MAX_ROW = 1_048_576;
    /** The last column of a sheet ({@code XFD}). */
    static final int MAX_COLUMN = 16_384;
    /** What a reference into a removed row, or past the sheet, becomes. */
    static final String REF_ERROR = "#REF!";

    private static final Pattern CELL = Pattern.compile("(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]{1,7})");
    private static final Pattern ROW = Pattern.compile("(\\$?)([0-9]{1,7})");
    private static final Pattern COLUMN = Pattern.compile("(\\$?)([A-Za-z]{1,3})");

    private final String sheet;
    private final int row;
    private final int copies;

    /**
     * @param sheet  the name of the sheet with the repeating row
     * @param row    the repeating row, 1-based as in the file
     * @param copies how many copies replace it; 0 removes it
     */
    FormulaShifter(String sheet, int row, int copies) {
        if (sheet.isEmpty()) {
            throw new IllegalArgumentException("The sheet name is empty");
        }
        if (row < 1 || row > MAX_ROW) {
            throw new IllegalArgumentException("The repeating row is outside the sheet");
        }
        if (copies < 0 || copies > MAX_ROW) {
            throw new IllegalArgumentException("The number of copies is outside the sheet");
        }
        this.sheet = sheet;
        this.row = row;
        this.copies = copies;
    }

    /**
     * Rewrites a formula, or A1 reference text, that lies outside the copies: on another row of the repeated sheet,
     * on another sheet, or in a defined name.
     *
     * @param formulaSheet the sheet the formula lives on, or {@code null} for a defined name (its unqualified
     *                     references are then left as they are)
     */
    String shift(String formula, @Nullable String formulaSheet) {
        boolean unqualifiedOnSheet = formulaSheet != null && sheet.equalsIgnoreCase(formulaSheet);
        return new Rewriter(formula, unqualifiedOnSheet, sheet::equalsIgnoreCase, this::inserted).run();
    }

    /**
     * The formula of a cell on the repeating row as written into one copy.
     *
     * @param copyIndex the copy, 0-based: copy 0 is on the repeating row itself
     */
    String copy(String formula, int copyIndex) {
        if (copyIndex < 0 || copyIndex >= copies) {
            throw new IllegalArgumentException("No such copy of the repeating row");
        }
        return new Rewriter(formula, true, sheet::equalsIgnoreCase,
                (ref, onSheet) -> ref.map(part -> copied(part, onSheet, copyIndex))).run();
    }

    /**
     * A formula pasted {@code rows} down and {@code columns} right: relative parts move, absolute parts stay, on
     * every sheet; a part moved off the sheet makes the reference {@code #REF!}. Expands a shared formula: the formula
     * of a covered cell is {@code offset(master, cellRow - masterRow, cellColumn - masterColumn)}.
     */
    static String offset(String formula, int rows, int columns) {
        return new Rewriter(formula, false, name -> false,
                (ref, onSheet) -> ref.map(part -> part.offset(rows, columns))).run();
    }

    private @Nullable Ref inserted(Ref ref, boolean onSheet) {
        Part first = ref.first();
        if (!onSheet || first.row() == 0) {
            return ref; // another sheet, or whole columns
        }
        int delta = copies - 1;
        Part last = ref.last();
        if (last == null) {
            if (first.row() == row && copies == 0) {
                return null;
            }
            int moved = first.row() > row ? first.row() + delta : first.row();
            return moved > MAX_ROW ? null : new Ref(first.atRow(moved), null);
        }
        boolean ascending = first.row() <= last.row();
        int low = Math.min(first.row(), last.row());
        int high = Math.max(first.row(), last.row());
        int newLow = low > row ? low + delta : low;
        int newHigh = high >= row ? high + delta : high;
        if (newHigh < newLow || newHigh > MAX_ROW) {
            return null;
        }
        return new Ref(first.atRow(ascending ? newLow : newHigh), last.atRow(ascending ? newHigh : newLow));
    }

    private @Nullable Part copied(Part part, boolean onSheet, int copyIndex) {
        if (part.row() == 0) {
            return part; // a whole column
        }
        int moved = onSheet && part.row() > row ? part.row() + copies - 1 : part.row();
        int target = part.rowAbsolute() ? moved : moved + copyIndex;
        return target > MAX_ROW ? null : part.atRow(target);
    }

    private static @Nullable Part part(String token) {
        Matcher cell = CELL.matcher(token);
        if (cell.matches()) {
            int column = column(cell.group(2));
            int row = Integer.parseInt(cell.group(4));
            return column <= MAX_COLUMN && row >= 1 && row <= MAX_ROW
                    ? new Part(!cell.group(1).isEmpty(), column, !cell.group(3).isEmpty(), row)
                    : null;
        }
        Matcher wholeRow = ROW.matcher(token);
        if (wholeRow.matches()) {
            int row = Integer.parseInt(wholeRow.group(2));
            return row >= 1 && row <= MAX_ROW ? new Part(false, 0, !wholeRow.group(1).isEmpty(), row) : null;
        }
        Matcher wholeColumn = COLUMN.matcher(token);
        if (wholeColumn.matches()) {
            int column = column(wholeColumn.group(2));
            return column <= MAX_COLUMN ? new Part(!wholeColumn.group(1).isEmpty(), column, false, 0) : null;
        }
        return null;
    }

    /** The column and row of a cell reference ({@code $D$5} is {@code {4, 5}}); null when it is not one. */
    static int @Nullable [] cell(String reference) {
        Part part = part(reference);
        return part == null || !part.isCell() ? null : new int[] {part.column(), part.row()};
    }

    /**
     * The first and last row of a cell or a cell range ({@code A5:C7} is {@code {5, 7}}, {@code B4} is
     * {@code {4, 4}}); null for anything else (whole columns or rows, names, {@code #REF!}).
     */
    static int @Nullable [] rows(String reference) {
        int colon = reference.indexOf(':');
        int[] first = cell(colon < 0 ? reference : reference.substring(0, colon));
        int[] last = colon < 0 ? first : cell(reference.substring(colon + 1));
        return first == null || last == null ? null
                : new int[] {Math.min(first[1], last[1]), Math.max(first[1], last[1])};
    }

    /** The column number of letters ({@code D} is 4), ignoring case. */
    static int column(String letters) {
        int column = 0;
        for (int i = 0; i < letters.length(); i++) {
            column = column * 26 + Character.toUpperCase(letters.charAt(i)) - 'A' + 1;
        }
        return column;
    }

    /** The letters of a column number (4 is {@code D}). */
    static String letters(int column) {
        StringBuilder letters = new StringBuilder();
        for (int rest = column; rest > 0; rest = (rest - 1) / 26) {
            letters.insert(0, (char) ('A' + (rest - 1) % 26));
        }
        return letters.toString();
    }

    /** Maps a whole reference; {@code null} when it becomes {@code #REF!}. */
    @FunctionalInterface
    private interface RefMapper {

        @Nullable Ref map(Ref ref, boolean onSheet);
    }

    /** Maps one end of a reference; {@code null} when it leaves the sheet. */
    @FunctionalInterface
    private interface PartMapper {

        @Nullable Part map(Part part);
    }

    /**
     * One end of a reference: a cell ({@code $D5}), a whole row ({@code 5}) with {@code column} 0, or a whole column
     * ({@code D}) with {@code row} 0.
     */
    private record Part(boolean columnAbsolute, int column, boolean rowAbsolute, int row) {

        Part atRow(int newRow) {
            return new Part(columnAbsolute, column, rowAbsolute, newRow);
        }

        @Nullable Part offset(int rows, int columns) {
            long newRow = row == 0 || rowAbsolute ? row : (long) row + rows;
            long newColumn = column == 0 || columnAbsolute ? column : (long) column + columns;
            boolean rowOff = row != 0 && (newRow < 1 || newRow > MAX_ROW);
            boolean columnOff = column != 0 && (newColumn < 1 || newColumn > MAX_COLUMN);
            if (rowOff || columnOff) {
                return null;
            }
            return new Part(columnAbsolute, (int) newColumn, rowAbsolute, (int) newRow);
        }

        boolean isCell() {
            return column != 0 && row != 0;
        }

        boolean sameKind(Part other) {
            return (column == 0) == (other.column == 0) && (row == 0) == (other.row == 0);
        }

        String render() {
            StringBuilder text = new StringBuilder();
            if (column != 0) {
                text.append(columnAbsolute ? "$" : "").append(letters(column));
            }
            if (row != 0) {
                text.append(rowAbsolute ? "$" : "").append(row);
            }
            return text.toString();
        }
    }

    /** A single cell ({@code last} null) or a range of two parts of the same kind. */
    private record Ref(Part first, @Nullable Part last) {

        @Nullable Ref map(PartMapper mapper) {
            Part newFirst = mapper.map(first);
            if (newFirst == null) {
                return null;
            }
            if (last == null) {
                return new Ref(newFirst, null);
            }
            Part newLast = mapper.map(last);
            return newLast == null ? null : new Ref(newFirst, newLast);
        }

        String render() {
            return last == null ? first.render() : first.render() + ":" + last.render();
        }
    }

    /** One pass over one formula: copies everything but the references it maps. */
    private static final class Rewriter {

        private final String formula;
        private final boolean unqualifiedOnSheet;
        private final Predicate<String> qualifiedOnSheet;
        private final RefMapper mapper;
        private final StringBuilder out;
        private int pos;

        Rewriter(String formula, boolean unqualifiedOnSheet, Predicate<String> qualifiedOnSheet, RefMapper mapper) {
            this.formula = formula;
            this.unqualifiedOnSheet = unqualifiedOnSheet;
            this.qualifiedOnSheet = qualifiedOnSheet;
            this.mapper = mapper;
            this.out = new StringBuilder(formula.length() + 8);
        }

        String run() {
            while (pos < formula.length()) {
                char c = formula.charAt(pos);
                if (c == '"') {
                    copyString();
                } else if (c == '#') {
                    copyError();
                } else if (c == '[') {
                    copyBracket();
                } else if (c == '\'') {
                    quotedSheet();
                } else if (isNameChar(c)) {
                    name();
                } else {
                    out.append(c);
                    pos++;
                }
            }
            return out.toString();
        }

        /** A string literal, {@code ""} inside it being one quote. */
        private void copyString() {
            int end = pos + 1;
            while (end < formula.length()) {
                if (formula.charAt(end) == '"') {
                    if (charAt(end + 1) != '"') {
                        end++;
                        break;
                    }
                    end++;
                }
                end++;
            }
            copyTo(Math.min(end, formula.length()));
        }

        /** An error literal: {@code #REF!}, {@code #N/A}, {@code #DIV/0!}, {@code #NAME?}. */
        private void copyError() {
            int end = pos + 1;
            while (end < formula.length() && isErrorChar(formula.charAt(end))) {
                end++;
            }
            if (charAt(end) == '!' || charAt(end) == '?') {
                end++;
            }
            copyTo(end);
        }

        /**
         * A bracketed part, nested brackets and {@code '}-escapes included: a structured reference or the workbook
         * index of an external reference, whose sheet and reference that follow are copied as they are.
         */
        private void copyBracket() {
            int depth = 0;
            int end = pos;
            while (end < formula.length()) {
                char c = formula.charAt(end++);
                if (c == '\'') {
                    end++;
                } else if (c == '[') {
                    depth++;
                } else if (c == ']' && --depth == 0) {
                    break;
                }
            }
            copyTo(Math.min(end, formula.length()));
            copyTo(nameEnd(pos));
            if (charAt(pos) == '!') {
                copyTo(nameEnd(pos + 1));
                if (charAt(pos) == ':') {
                    copyTo(nameEnd(pos + 1));
                }
            }
        }

        /** {@code 'My Sheet'!A5}: the name between quotes, {@code ''} being one quote. */
        private void quotedSheet() {
            StringBuilder name = new StringBuilder();
            int end = pos + 1;
            while (end < formula.length()) {
                char c = formula.charAt(end);
                if (c == '\'') {
                    if (charAt(end + 1) != '\'') {
                        break;
                    }
                    end++;
                }
                name.append(c);
                end++;
            }
            if (end >= formula.length() || charAt(end + 1) != '!') {
                copyTo(Math.min(end + 1, formula.length())); // not a sheet qualifier: left as written
                return;
            }
            String qualifier = formula.substring(pos, end + 2);
            reference(end + 2, qualifier, qualifiedOnSheet.test(name.toString()));
        }

        /** A run of name characters: a function, a sheet, a table, a defined name, a number or a reference. */
        private void name() {
            int end = nameEnd(pos);
            char next = charAt(end);
            if (next == '(' || next == '[') {
                copyTo(end); // a function or a table
            } else if (next == '!') {
                reference(end + 1, formula.substring(pos, end + 1), qualifiedOnSheet.test(formula.substring(pos, end)));
            } else if (next == ':' && charAt(nameEnd(end + 1)) == '!') {
                int bang = nameEnd(end + 1);
                reference(bang + 1, formula.substring(pos, bang + 1), false); // Sheet1:Sheet3!A5
            } else {
                reference(pos, "", unqualifiedOnSheet);
            }
        }

        /** The reference at {@code at}, after its qualifier (copied first); anything else is copied as written. */
        private void reference(int at, String qualifier, boolean onSheet) {
            int firstEnd = nameEnd(at);
            Part first = part(formula.substring(at, firstEnd));
            Part last = null;
            int end = firstEnd;
            if (first != null && charAt(firstEnd) == ':') {
                int lastEnd = nameEnd(firstEnd + 1);
                Part candidate = part(formula.substring(firstEnd + 1, lastEnd));
                char after = charAt(lastEnd);
                if (candidate != null && first.sameKind(candidate) && after != '(' && after != '!' && after != '[') {
                    last = candidate;
                    end = lastEnd;
                }
            }
            out.append(qualifier);
            pos = at;
            if (first == null || last == null && !first.isCell()) {
                copyTo(firstEnd); // a number, a lone column name or a defined name
                return;
            }
            Ref ref = new Ref(first, last);
            Ref mapped = mapper.map(ref, onSheet);
            if (mapped == null) {
                out.append(REF_ERROR);
                pos = end;
            } else if (mapped.equals(ref)) {
                copyTo(end);
            } else {
                out.append(mapped.render());
                pos = end;
            }
        }

        private void copyTo(int end) {
            out.append(formula, pos, end);
            pos = end;
        }

        private int nameEnd(int from) {
            int end = from;
            while (end < formula.length() && isNameChar(formula.charAt(end))) {
                end++;
            }
            return end;
        }

        private char charAt(int index) {
            return index < formula.length() ? formula.charAt(index) : '\0';
        }

        private static boolean isNameChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$' || c == '\\';
        }

        private static boolean isErrorChar(char c) {
            return Character.isLetterOrDigit(c) || c == '/' || c == '_';
        }
    }
}
