package app.nadel.ledger;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One payment for the payments report ({@link LedgerReadModel#payments}, feature 015 §6): a row of kind
 * {@code PAYMENT} (never a reversal row: a reversed payment is listed once with {@code reversed} set) with what it pays
 * on charges now and what is left of it. Ids, codes and {@code Money} only: the payer is a member id the caller names
 * through {@code members}, and the payer note is not here (it is personal data, read through {@link PaymentNotes}).
 *
 * @param paymentId     the payment
 * @param receiptNumber the cash receipt number; null for a bank payment
 * @param bankReference the bank document number as entered; null for cash and when none was entered
 * @param receivedOn    the day the money was received
 * @param billingMonth  the billing month the payment is booked in
 * @param plotId        the plot it was received for
 * @param payerMemberId the member who paid; null when not named
 * @param source        cash or bank
 * @param amount        the amount received (above zero)
 * @param allocated     the net allocations of the payment now (allocations minus releases); zero once reversed
 * @param remaining     what is left of it as prepayment: {@code amount - allocated}, zero once reversed
 * @param reversed      true when a payment reversal cancels it
 * @param groupId       the group of a payment split over several plots (a single payment is its own group)
 */
public record PaymentRow(UUID paymentId, @Nullable Long receiptNumber, @Nullable String bankReference,
        LocalDate receivedOn, YearMonth billingMonth, UUID plotId, @Nullable UUID payerMemberId, PaymentSource source,
        Money amount, Money allocated, Money remaining, boolean reversed, UUID groupId) {

    public PaymentRow {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(receivedOn, "receivedOn");
        Objects.requireNonNull(billingMonth, "billingMonth");
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(allocated, "allocated");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(groupId, "groupId");
    }
}
