package app.nadel.imports.internal;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One problem of an import file.
 *
 * @param row    the Excel row number; 0 when the problem is about the file or a whole sheet
 * @param column the canonical column key, null when the problem is about the whole row
 * @param code   the stable code the client turns into a message
 * @param params values for the message
 */
record RowProblem(ImportSheet sheet, int row, @Nullable String column, String code, Map<String, String> params) {

    RowProblem(ImportSheet sheet, int row, @Nullable String column, String code) {
        this(sheet, row, column, code, Map.of());
    }
}
