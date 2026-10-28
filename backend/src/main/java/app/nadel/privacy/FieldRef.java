package app.nadel.privacy;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where a sealed value lives: the associated data of its encryption. A value sealed for one row, column, table (or
 * cooperative) does not open in another.
 *
 * @param table  table name, lower case letters, {@code _} and {@code .} only
 * @param column column name (or a path inside a JSON column, such as {@code changes.email.old}), same alphabet
 * @param rowId  id of the row
 */
public record FieldRef(String table, String column, UUID rowId) {

    private static final Pattern NAME = Pattern.compile("[a-z_.]+");

    public FieldRef {
        Objects.requireNonNull(rowId, "rowId");
        requireName(table, "table");
        requireName(column, "column");
    }

    private static void requireName(String value, String what) {
        // The names are joined with '|' into the associated data, so they must never contain one
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("The " + what + " name of a field must match [a-z_.]+");
        }
    }
}
