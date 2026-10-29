package app.nadel.ledger;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a printed cash receipt needs of one payment group ({@link LedgerReadModel#cashReceipt}, feature 016 §2.8): the
 * receipt number, the day received, who entered it, the source, the payer and every part of the group (one per plot,
 * with its amount, its reversal and where its money is now). The number and the amounts never change; the placement is
 * the one of the moment it is read. Ids, codes and {@code Money} only: the payer and the author are member ids the
 * caller names through {@code members} and {@code audit.ActorNames}, the payer note is read through
 * {@link PaymentNotes}.
 *
 * @param groupId       the group (a single payment is its own group)
 * @param receiptNumber the receipt number of a cash payment; null for a bank payment
 * @param source        cash or bank: a bank payment has no receipt, the caller refuses it
 * @param receivedOn    the day the money was received
 * @param enteredBy     the member who entered the payment; null when not known
 * @param payerMemberId the member who paid; null when not named
 * @param parts         the parts of the group (kind {@code PAYMENT}) in the order they were entered, at least one
 */
public record CashReceiptData(UUID groupId, @Nullable Long receiptNumber, PaymentSource source, LocalDate receivedOn,
        @Nullable UUID enteredBy, @Nullable UUID payerMemberId, List<Part> parts) {

    public CashReceiptData {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(receivedOn, "receivedOn");
        parts = List.copyOf(parts);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("A payment group has at least one part");
        }
    }

    /** The amount received: the sum of the parts, reversed ones included (the receipt's amount never changes). */
    public Money total() {
        return parts.stream().map(Part::amount).reduce(Money.ZERO, Money::plus);
    }

    /** Whether every part of the group has been reversed. */
    public boolean fullyReversed() {
        return parts.stream().allMatch(part -> part.reversedAt() != null);
    }

    /**
     * One part of the group: the payment of one plot.
     *
     * @param paymentId   the payment row (kind {@code PAYMENT})
     * @param plotId      the plot it was received for
     * @param amount      the amount received for the plot (above zero)
     * @param reversedAt  when its reversal was booked; null when it is not reversed
     * @param placements  where its money is now: per charge the net of its allocations and releases above
     *                    {@code 0.00}, in the order money is placed (oldest debt first); empty once reversed
     * @param unallocated what is left of it on no charge (prepayment): {@code amount - placements}; {@code 0.00} once
     *                    reversed
     */
    public record Part(UUID paymentId, UUID plotId, Money amount, @Nullable Instant reversedAt,
            List<Placement> placements, Money unallocated) {

        public Part {
            Objects.requireNonNull(paymentId, "paymentId");
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(amount, "amount");
            placements = List.copyOf(placements);
            Objects.requireNonNull(unallocated, "unallocated");
        }
    }

    /**
     * Money of a part on one charge now.
     *
     * @param chargeId     the charge (a regular charge or an opening balance)
     * @param chargeTypeId its charge type; the caller names it through {@code tariffs.ChargeTypeCatalog}
     * @param opening      true for an opening balance
     * @param billingMonth the billing month the charge is booked in
     * @param serviceFrom  the first service day of the charge
     * @param serviceTo    the last service day of the charge
     * @param amount       the net of the part's allocations to the charge (above zero)
     */
    public record Placement(UUID chargeId, UUID chargeTypeId, boolean opening, YearMonth billingMonth,
            LocalDate serviceFrom, LocalDate serviceTo, Money amount) {

        public Placement {
            Objects.requireNonNull(chargeId, "chargeId");
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(billingMonth, "billingMonth");
            Objects.requireNonNull(serviceFrom, "serviceFrom");
            Objects.requireNonNull(serviceTo, "serviceTo");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
