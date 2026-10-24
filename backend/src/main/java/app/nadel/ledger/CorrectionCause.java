package app.nadel.ledger;

/**
 * What started the recalculation that booked a correction (column {@code recalculation.cause}, money rules 5.3); the
 * {@code RecalculationCause} of the API contract.
 */
public enum CorrectionCause {

    /** A manual recalculation ("Нарахування → Перерахунок"). */
    MANUAL,

    /** A tariff version added in the past, or a used version corrected or deleted. */
    TARIFF_VERSION,

    /** A used reading corrected or deleted, or a reading entered inside a charged interval. */
    READING
}
