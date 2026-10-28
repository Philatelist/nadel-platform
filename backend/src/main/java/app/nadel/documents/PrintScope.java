package app.nadel.documents;

/**
 * Which invoices of a month go into its print set (feature 016 §2.5, {@code GET /billing-months/{month}/invoices/
 * print.pdf}, {@link DocumentsFacade#printPdf}).
 */
public enum PrintScope {

    /** The plots with at least one current owner who chose paper delivery. */
    PAPER,

    /** Every latest invoice of the month. */
    ALL
}
