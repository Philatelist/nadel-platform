package app.nadel.reports.internal;

import app.nadel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import nadel.contract.model.ReportCellReason;
import nadel.contract.model.ReportColumnType;
import nadel.contract.model.ReportNoteCode;
import nadel.contract.model.ReportRowKind;
import org.jspecify.annotations.Nullable;

/**
 * What a report definition loads ({@link ReportDefinition#load}): its sheets with typed cells and the sentences of
 * its header. The preview and the file are both made from one result, so their numbers always agree.
 *
 * @param sheets the sheets in order, at least one
 * @param notes  the sentences of the header, as codes and parameters
 */
record ReportResult(List<Sheet> sheets, List<Note> notes) {

    ReportResult {
        sheets = List.copyOf(sheets);
        notes = List.copyOf(notes);
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("A report has at least one sheet");
        }
    }

    /**
     * A sheet: the columns, every row (the runner caps and cuts them) and the totals over all rows.
     *
     * @param key the sheet key of the contract, for example {@code byBillingMonth} or {@code main}
     */
    record Sheet(String key, List<Column> columns, List<Row> rows, List<Total> totals) {

        Sheet {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
            totals = List.copyOf(totals);
            int width = columns.size();
            if (rows.stream().anyMatch(row -> row.cells().size() != width)
                    || totals.stream().anyMatch(total -> total.cells().size() != width)) {
                throw new IllegalArgumentException("Every row of sheet " + key + " has one cell per column");
            }
        }
    }

    /**
     * A column.
     *
     * @param key          the column key of the contract; its header is {@code reports.<code>.columns.<key>}
     * @param type         how its values are written
     * @param chargeTypeId set on a column per charge type
     * @param codes        for a {@link ReportColumnType#CODE} column, the catalog path of its values in the file
     *                     ({@code <codes>.<VALUE>}, for example {@code billing.status}); null for other columns
     */
    record Column(String key, ReportColumnType type, @Nullable UUID chargeTypeId, @Nullable String codes) {

        Column {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
            if ((type == ReportColumnType.CODE) != (codes != null)) {
                throw new IllegalArgumentException("A CODE column, and only it, names the catalog of its values");
            }
        }

        static Column of(String key, ReportColumnType type) {
            return new Column(key, type, null, null);
        }

        static Column codes(String key, String codes) {
            return new Column(key, ReportColumnType.CODE, null, codes);
        }
    }

    /** A row of a sheet; {@code level} is the depth in a tree report, 0 elsewhere. */
    record Row(ReportRowKind kind, int level, List<Cell> cells) {

        Row {
            cells = List.copyOf(cells);
        }

        static Row detail(List<Cell> cells) {
            return new Row(ReportRowKind.DETAIL, 0, cells);
        }

        static Row subtotal(List<Cell> cells) {
            return new Row(ReportRowKind.SUBTOTAL, 0, cells);
        }
    }

    /** A totals line over all rows of a sheet; {@code key} names it ({@code all} for the whole report). */
    record Total(String key, List<Cell> cells) {

        Total {
            cells = List.copyOf(cells);
        }
    }

    /**
     * One cell. The value's Java type follows the column type: {@link Money} for {@code MONEY}, {@link BigDecimal} with
     * its scale for {@code QUANTITY} and {@code PERCENT}, a {@link Number} for {@code INTEGER}, {@link LocalDate},
     * {@link YearMonth}, {@link Boolean}, {@link UUID} for {@code CHARGE_TYPE} and a {@link String} for {@code TEXT}
     * and {@code CODE}. An empty cell has no value; a value that is not known has none either, but a reason.
     */
    record Cell(@Nullable Object value, @Nullable ReportCellReason reason) {

        static final Cell EMPTY = new Cell(null, null);

        Cell {
            if (value != null && reason != null) {
                throw new IllegalArgumentException("A cell with a value has no reason");
            }
        }

        static Cell of(@Nullable Object value) {
            return value == null ? EMPTY : new Cell(value, null);
        }

        static Cell unknown(ReportCellReason reason) {
            return new Cell(null, Objects.requireNonNull(reason, "reason"));
        }

        /** The value as the preview carries it, formatted by the column type; null for an empty cell. */
        @Nullable String json(ReportColumnType type) {
            if (value == null) {
                return null;
            }
            return switch (type) {
                case QUANTITY, PERCENT -> ((BigDecimal) value).toPlainString();
                case MONEY, TEXT, CODE, CHARGE_TYPE, INTEGER, DATE, MONTH, BOOLEAN -> value.toString();
            };
        }
    }

    /** A sentence of the report header: a code and its parameters (codes, numbers and dates). */
    record Note(ReportNoteCode code, Map<String, String> params) {

        Note {
            params = Map.copyOf(params);
        }
    }
}
