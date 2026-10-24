package app.nadel.ledger;

/**
 * Who or what booked a correction ({@link LedgerReadModel#corrections}), derived from the stored row:
 * <ul>
 * <li>{@link #RECALCULATION}: the row has source {@code RECALCULATION} (every adjustment, and the reversals a
 * recalculation books, also those of a run of an open month it reverses as a whole);</li>
 * <li>{@link #RUN_REVERSAL}: a {@code MANUAL_REVERSAL} row of a charge of a losses or common consumer run that was booked
 * by the reversal of the whole run ({@code POST /losses-runs/{id}/reverse}, {@code POST /common-consumer-runs/{id}/
 * reverse}): its run is {@code REVERSED} and was reversed at the very instant the row was created (the run reversal
 * writes both with one timestamp);</li>
 * <li>{@link #MANUAL_REVERSAL}: every other {@code MANUAL_REVERSAL} row, a reversal of one charge by hand
 * ({@code POST /charges/{id}/reverse}), also of a single charge of a run reversed before its run.</li>
 * </ul>
 */
public enum CorrectionSource {

    /** One charge reversed by hand. */
    MANUAL_REVERSAL,

    /** Booked by a recalculation (feature 012): an adjustment or a reversal. */
    RECALCULATION,

    /** Booked by the reversal of a whole losses or common consumer run. */
    RUN_REVERSAL
}
