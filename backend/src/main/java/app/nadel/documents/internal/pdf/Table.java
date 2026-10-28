package app.nadel.documents.internal.pdf;

import app.nadel.documents.internal.pdf.TextBlock.Align;
import java.util.List;
import java.util.Objects;

/**
 * A table of a document: columns with a share of the page width and an alignment (amounts right-aligned), a header
 * row in semibold and rows of text that wrap inside their cells. A row never splits: when it does not fit on the page,
 * the table goes on on a new page and repeats its header there.
 *
 * @param columns the columns, at least one
 * @param rows    the rows, each with one text per column; a {@link Row#strong} row is printed in semibold (a total)
 * @param size    the font size in points
 */
public record Table(List<Column> columns, List<Row> rows, float size) {

    /**
     * A column.
     *
     * @param title  the header text
     * @param weight the share of the width relative to the other columns, above zero
     * @param align  the alignment of the column's cells and header
     */
    public record Column(String title, float weight, Align align) {

        public Column {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(align, "align");
            if (weight <= 0) {
                throw new IllegalArgumentException("weight must be above zero");
            }
        }
    }

    /** One row: a text per column. */
    public record Row(List<String> cells, boolean strong) {

        public Row {
            cells = List.copyOf(cells);
        }

        public static Row of(String... cells) {
            return new Row(List.of(cells), false);
        }

        public static Row total(String... cells) {
            return new Row(List.of(cells), true);
        }
    }

    public Table {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("A table has at least one column");
        }
        for (Row row : rows) {
            if (row.cells().size() != columns.size()) {
                throw new IllegalArgumentException("Every row has one cell per column");
            }
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be above zero");
        }
    }
}
