package app.nadel.ledger;

/** How {@link LedgerReadModel#monthTotals} groups the charges into months. */
public enum MonthGrouping {

    /**
     * By the billing month a row is booked in ({@code charge.billing_month_id}, money rules 3.1): the grouping of the
     * billing months screen, so the totals agree with it cent for cent. An adjustment booked in March for a January
     * charge counts in March.
     */
    BILLING_MONTH,

    /**
     * By the month of the first service day of a row ({@code charge.service_from}). An adjustment and a reversal keep
     * the service period of the charge they correct, so an adjustment booked in March for a January charge counts in
     * January. Months have no status in this grouping.
     */
    SERVICE_MONTH
}
