package app.nadel.documents.internal;

import app.nadel.ledger.InvoiceLedger;
import app.nadel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The figures of one invoice ({@link InvoiceCalculator}, feature 016 §2.3): the rows per charge type in the
 * cooperative's order, the charges booked in the month, the payments received after the month and the totals. Pure
 * value.
 *
 * @param types         one row per charge type with a figure or a line, in the charge type order
 * @param lines         the charges booked in the month, in the charge type order, then by service period
 * @param receivedAfter the payments booked after the month and received before the invoice
 * @param endDebt       the sum of the rows' end debts
 * @param prepayment    the money on no charge at the end of the month («Аванс»)
 * @param received      the sum of {@code receivedAfter}
 * @param due           «До сплати»: {@code endDebt - received}, never below {@code 0.00}
 */
record InvoiceFigures(List<TypeRow> types, List<InvoiceLedger.Line> lines,
        List<InvoiceLedger.LaterPayment> receivedAfter, Money endDebt, Money prepayment, Money received, Money due) {

    InvoiceFigures {
        types = List.copyOf(types);
        lines = List.copyOf(lines);
        receivedAfter = List.copyOf(receivedAfter);
        Objects.requireNonNull(endDebt, "endDebt");
        Objects.requireNonNull(prepayment, "prepayment");
        Objects.requireNonNull(received, "received");
        Objects.requireNonNull(due, "due");
    }

    /** One charge type: debt at the start, charged, paid and debt at the end of the month. */
    record TypeRow(UUID chargeTypeId, Money start, Money charged, Money paid, Money end) {

        TypeRow {
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(charged, "charged");
            Objects.requireNonNull(paid, "paid");
            Objects.requireNonNull(end, "end");
        }
    }

    /** Whether the invoice carries the payment QR code: only when something is due. */
    boolean payable() {
        return due.signum() > 0;
    }
}
