package app.nadel.imports.internal;

import java.math.BigDecimal;

/** The content of one cell as the workbook stores it, before any interpretation. An empty cell is {@code null}. */
sealed interface RawCell {

    /** Text, also the result of a text or inline string cell. */
    record Text(String value) implements RawCell {
    }

    /**
     * A numeric cell. {@code dateFormat} tells that the cell is formatted as a date, which makes the number an Excel
     * serial date; the value is the decimal exactly as stored.
     */
    record Number(BigDecimal value, boolean dateFormat) implements RawCell {
    }

    record Bool(boolean value) implements RawCell {
    }
}
