package app.nadel.imports.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One row of a sheet. {@code number} is the Excel row number (the first row is 1), {@code cells} is indexed by column
 * (A is 0) and holds {@code null} for an empty cell; the list ends with the last non-empty cell.
 */
record RawRow(int number, List<@Nullable RawCell> cells) {

    /** The cell in this column, or null when the row does not reach that far or the cell is empty. */
    @Nullable RawCell cell(int column) {
        return column >= 0 && column < cells.size() ? cells.get(column) : null;
    }
}
