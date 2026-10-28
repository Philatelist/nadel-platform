package app.nadel.ledger.internal;

import app.nadel.ledger.InvoiceLedger.LaterPayment;
import app.nadel.ledger.InvoiceLedger.TypeMovement;
import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The booking-month rule of an invoice (feature 016 §2.3, technical considerations 2.3, money rules «Рахунки та
 * довідки»), pure: the rows of one plot in, the figures of one billing month out.
 * <ul>
 * <li>A charge and a payment count in their own billing month.</li>
 * <li>An allocation or a release counts in {@code GREATEST(payment month, charge month, openMonthAt(created_at))},
 * where {@code openMonthAt(t)} is the month after the last month closed at or before {@code t} (no bound when no
 * month was closed by then).</li>
 * <li>Only rows created at or before {@code asOf} count.</li>
 * <li>Per charge type: {@code start} = charges before the month − allocations counted before it; {@code charged} =
 * charges of the month; {@code paid} = allocations counted in the month; {@code end = start + charged − paid}.</li>
 * <li>{@code prepaymentAtEnd} = payments booked in or before the month (a reversal negative) − allocations counted in
 * or before it, never below {@code 0.00}.</li>
 * <li>{@code receivedAfter} = the payments booked after the month, created by {@code asOf} and not reversed by a
 * reversal created by then, in the order received.</li>
 * </ul>
 * A closed month never changes: a row counted in or before it was created before the month was closed (a charge or a
 * payment is booked only in an open month, and an allocation created after the closing counts in a later month), and
 * the ledger is append-only. Exact {@code Money} sums, no rounding.
 */
final class MonthMovements {

    /** A closed billing month and when it was closed. */
    record ClosedMonth(YearMonth month, Instant closedAt) {

        ClosedMonth {
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(closedAt, "closedAt");
        }
    }

    /** A charge of the plot (any kind) with its sign, the month it is booked in and when it was booked. */
    record ChargeEntry(UUID chargeTypeId, YearMonth bookedIn, Money amount, Instant createdAt) {

        ChargeEntry {
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(bookedIn, "bookedIn");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * An allocation (above zero) or a release (below zero) of the plot: the charge type and the billing month of its
     * charge, the billing month of its payment, and when it was created.
     */
    record AllocationEntry(UUID chargeTypeId, YearMonth paymentMonth, YearMonth chargeMonth, Money amount,
            Instant createdAt) {

        AllocationEntry {
            Objects.requireNonNull(chargeTypeId, "chargeTypeId");
            Objects.requireNonNull(paymentMonth, "paymentMonth");
            Objects.requireNonNull(chargeMonth, "chargeMonth");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * A payment row of the plot: a payment (above zero) or a reversal (the negated amount, {@code reverses} set).
     */
    record PaymentEntry(UUID id, @Nullable UUID reverses, YearMonth bookedIn, LocalDate receivedOn, Money amount,
            Instant createdAt) {

        PaymentEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(bookedIn, "bookedIn");
            Objects.requireNonNull(receivedOn, "receivedOn");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** The figures of the month: per charge type, the prepayment at its end and the payments received after it. */
    record Result(List<TypeMovement> types, Money prepaymentAtEnd, List<LaterPayment> receivedAfter) {

        Result {
            types = List.copyOf(types);
            Objects.requireNonNull(prepaymentAtEnd, "prepaymentAtEnd");
            receivedAfter = List.copyOf(receivedAfter);
        }
    }

    private MonthMovements() {
    }

    /**
     * The figures of {@code month} as of {@code asOf}.
     *
     * @param month       the billing month
     * @param asOf        the last moment a row may have been created at to count
     * @param closed      the closed months of the cooperative with their closing time (any order)
     * @param charges     the plot's charges (rows booked after the month may be given; they never count)
     * @param allocations the plot's allocations and releases
     * @param payments    the plot's payments and payment reversals
     * @return the types in the order they first appear (charges first, then allocations)
     */
    static Result of(YearMonth month, Instant asOf, Collection<ClosedMonth> closed, Collection<ChargeEntry> charges,
            Collection<AllocationEntry> allocations, Collection<PaymentEntry> payments) {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(asOf, "asOf");
        Map<UUID, Sums> byType = new LinkedHashMap<>();
        for (ChargeEntry charge : charges) {
            if (charge.createdAt().isAfter(asOf) || charge.bookedIn().isAfter(month)) {
                continue;
            }
            Sums sums = byType.computeIfAbsent(charge.chargeTypeId(), id -> new Sums());
            if (charge.bookedIn().isBefore(month)) {
                sums.start = sums.start.plus(charge.amount());
            } else {
                sums.charged = sums.charged.plus(charge.amount());
            }
        }
        Money allocatedByEnd = Money.ZERO;
        for (AllocationEntry allocation : allocations) {
            if (allocation.createdAt().isAfter(asOf)) {
                continue;
            }
            YearMonth countedIn = countedIn(allocation, closed);
            if (countedIn.isAfter(month)) {
                continue;
            }
            allocatedByEnd = allocatedByEnd.plus(allocation.amount());
            Sums sums = byType.computeIfAbsent(allocation.chargeTypeId(), id -> new Sums());
            if (countedIn.isBefore(month)) {
                sums.start = sums.start.minus(allocation.amount());
            } else {
                sums.paid = sums.paid.plus(allocation.amount());
            }
        }
        List<TypeMovement> types = new ArrayList<>();
        byType.forEach((typeId, sums) -> types.add(new TypeMovement(typeId, sums.start, sums.charged, sums.paid,
                sums.start.plus(sums.charged).minus(sums.paid))));

        Money paidByEnd = Money.ZERO;
        Set<UUID> reversedByAsOf = new HashSet<>();
        for (PaymentEntry payment : payments) {
            if (payment.createdAt().isAfter(asOf)) {
                continue;
            }
            if (payment.reverses() != null) {
                reversedByAsOf.add(payment.reverses());
            }
            if (!payment.bookedIn().isAfter(month)) {
                paidByEnd = paidByEnd.plus(payment.amount());
            }
        }
        Money prepayment = paidByEnd.minus(allocatedByEnd);
        List<LaterPayment> later = payments.stream()
                .filter(payment -> payment.reverses() == null && !payment.createdAt().isAfter(asOf)
                        && payment.bookedIn().isAfter(month) && !reversedByAsOf.contains(payment.id()))
                .sorted(Comparator.comparing(PaymentEntry::receivedOn).thenComparing(PaymentEntry::createdAt)
                        .thenComparing(payment -> payment.id().toString()))
                .map(payment -> new LaterPayment(payment.id(), payment.receivedOn(), payment.bookedIn(),
                        payment.amount()))
                .toList();
        return new Result(types, prepayment.signum() < 0 ? Money.ZERO : prepayment, later);
    }

    /**
     * The month an allocation or a release counts in: the latest of its payment's month, its charge's month and the
     * month that was open when it was created.
     */
    static YearMonth countedIn(AllocationEntry allocation, Collection<ClosedMonth> closed) {
        YearMonth counted = later(allocation.paymentMonth(), allocation.chargeMonth());
        YearMonth open = openMonthAt(allocation.createdAt(), closed);
        return open == null ? counted : later(counted, open);
    }

    /**
     * The month after the last month closed at or before {@code at}; null when no month was closed by then (no
     * bound).
     */
    static @Nullable YearMonth openMonthAt(Instant at, Collection<ClosedMonth> closed) {
        YearMonth last = null;
        for (ClosedMonth month : closed) {
            if (!month.closedAt().isAfter(at) && (last == null || month.month().isAfter(last))) {
                last = month.month();
            }
        }
        return last == null ? null : last.plusMonths(1);
    }

    private static YearMonth later(YearMonth a, YearMonth b) {
        return a.isAfter(b) ? a : b;
    }

    /** The running sums of one charge type. */
    private static final class Sums {
        private Money start = Money.ZERO;
        private Money charged = Money.ZERO;
        private Money paid = Money.ZERO;
    }
}
