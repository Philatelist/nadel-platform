package app.nadel.ledger;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.plots.PlotImportPlan;
import app.nadel.plots.PlotImportRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the Excel import may ask of the ledger: the rows of the "Opening balances" sheet (feature 011 §2.9). The import
 * parses the file; the ledger decides what each row means, with the rules of an opening balance entered on the plot
 * account (amount greater than zero, a date not in the future, booked in the month of the date or the earliest open
 * month after it, an optional reason stored on the charge).
 */
public interface OpeningBalanceImport {

    /**
     * One row of the sheet after the cells were read. A blank cell is {@code null}.
     *
     * @param row        the Excel row number, echoed in the plan
     * @param plotNumber the plot number as typed
     * @param chargeType the name of the charge type as typed
     * @param amount     the amount as typed, never rounded here
     * @param date       the day the debt counts from
     * @param reason     the optional reason or document reference (user content)
     */
    record Row(int row, @Nullable String plotNumber, @Nullable String chargeType, @Nullable BigDecimal amount,
            @Nullable LocalDate date, @Nullable String reason) {
    }

    /** What the import does with a row. */
    enum Action {
        /** A new opening balance is booked. */
        NEW,
        /** The plot already has this opening balance (same charge type, date and amount, not reversed). */
        UNCHANGED
    }

    /**
     * One problem of a row.
     *
     * @param field  {@code plotNumber}, {@code chargeType}, {@code amount}, {@code date} or {@code reason}
     * @param code   stable code, see {@code uploadImport} in the API contract
     * @param params values for the message ({@code amount} for {@code OPENING_BALANCE_EXISTS}); empty for most codes
     */
    record Problem(String field, String code, Map<String, String> params) {

        public Problem {
            params = Map.copyOf(params);
        }

        public Problem(String field, String code) {
            this(field, code, Map.of());
        }
    }

    /**
     * The plan for one row. A row with problems, and a row whose plot is not checked because its own row of the Plots
     * sheet has a problem, has no action.
     *
     * @param row          the Excel row number
     * @param action       null when the row has a problem or its plot is not checked
     * @param plotId       the plot, an existing one or the id a plot of the same import will get; null without an
     *                     action
     * @param chargeTypeId the charge type; null without an action
     * @param problems     the problems of the row
     */
    record Item(int row, @Nullable Action action, @Nullable UUID plotId, @Nullable UUID chargeTypeId,
            List<Problem> problems) {

        public Item {
            problems = List.copyOf(problems);
        }
    }

    /** The plan for all rows of the sheet, item for item. */
    record Plan(List<Item> items) {

        public Plan {
            items = List.copyOf(items);
        }
    }

    /**
     * Plans the rows without saving anything. Per row: the cells ({@code REQUIRED} for the plot, the charge type, the
     * amount and the date; {@code AMOUNT_NOT_POSITIVE}, {@code INVALID} for an amount with more than two decimals or
     * more than 12 integer digits, {@code DATE_IN_FUTURE}, {@code TOO_LONG} for a reason over 500 characters); the
     * charge type by name ({@link app.nadel.tariffs.ChargeTypeCatalog#findByNames}: {@code UNKNOWN_CHARGE_TYPE},
     * {@code CHARGE_TYPE_ARCHIVED}, {@code INVALID} when the name stands for two types); the plot (a plot of the file
     * or an active plot, else {@code UNKNOWN_PLOT}; common land {@code PLOT_COMMON_LAND}; a plot whose Plots row has a
     * problem is not checked further); {@code DUPLICATE_IN_FILE} on {@code chargeType} for an earlier row without
     * problems with the same plot, charge type and date; last, against the stored opening balances of the plot that are
     * not reversed: the same charge type, date and amount is {@code UNCHANGED}, the same charge type and date with
     * another amount {@code OPENING_BALANCE_EXISTS} on {@code amount} (parameter {@code amount}, the stored one), else
     * {@code NEW}. Runs inside the caller's transaction.
     *
     * @param plotRows the rows of the Plots sheet, in file order
     * @param plotPlan their plan; the caller replaces the item of a row with a problem by an item without action
     * @param rows     the rows of the sheet, in file order
     * @return one item per row, in the same order
     */
    Plan plan(UUID tenantId, List<PlotImportRow> plotRows, PlotImportPlan plotPlan, List<Row> rows);

    /**
     * Locks what a plan relies on until the end of the caller's transaction: the existing plots of the {@code NEW}
     * rows {@code FOR SHARE} in id order (as an opening balance entered by hand does), then their charge types
     * {@code FOR SHARE} in id order. The caller locks plots for its own purposes first and members after this. Must run
     * in the caller's transaction.
     */
    void lock(Plan plan);

    /**
     * Books the {@code NEW} rows as opening balances (kind and source {@code OPENING}) with the source key
     * {@code OPENING:{importId}:{row}}, each in the month of its date or the earliest open month after it, publishes
     * one {@link ChargesBooked} for their plots and builds one {@code OPENING_BALANCE_ENTERED} audit event per balance
     * credited to {@code actor}, as when entered by hand. The audit events are <b>returned, not published</b>: the
     * caller publishes them in batches in the same transaction. Must run in the caller's transaction, after the plots
     * the rows name were created in it. A source key that exists already is refused with 409 {@code IMPORT_STALE}.
     *
     * @param rows the rows the plan was computed from
     * @param plan the plan of {@code rows}, computed in the same transaction after {@link #lock}
     * @return the audit events in row order; the caller must publish them
     */
    List<AuditEvent> apply(UUID tenantId, UUID importId, List<Row> rows, Plan plan, Actor actor);
}
