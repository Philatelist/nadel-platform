package app.nadel.imports.internal;

/** Row tests shared by the sheet parsers. */
final class SheetRows {

    private static final String COMMENT_PREFIX = TemplateWriter.COMMENT_PREFIX;

    private SheetRows() {
    }

    /** True when every cell of the row is empty or only spaces. */
    static boolean isEmpty(RawRow row) {
        return row.cells().stream().allMatch(cell -> Cells.text(cell).value() == null);
    }

    /** True when the first cell (column A) starts with {@code #}: the template's instruction and example rows. */
    static boolean isComment(RawRow row) {
        RawCell first = row.cell(0);
        return first instanceof RawCell.Text text && text.value().stripLeading().startsWith(COMMENT_PREFIX);
    }
}
