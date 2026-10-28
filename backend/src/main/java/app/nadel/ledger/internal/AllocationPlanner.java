package app.nadel.ledger.internal;

import app.nadel.money.Money;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How money is placed on the open charges of a plot (feature 015 §2.2, technical considerations 2.3). Pure: no
 * Spring, no database.
 * <ul>
 * <li>{@link #ORDER}: the charges with an open amount are filled by their debt date ({@code accrued_on}), oldest
 * first; on the same day by the cooperative's order of charge types (a type the order does not know last); then by the
 * start of the service period ({@code accrued_on} when there is none); then in booking order ({@code created_at}, then
 * the id). Ids are compared as text, which is how PostgreSQL orders a {@code uuid}.</li>
 * <li>{@link #propose}: each charge in that order receives {@code min(remaining, open)}; what is left is the
 * remainder (the prepayment the payment leaves).</li>
 * <li>{@link #validate}: the treasurer's lines.</li>
 * <li>{@link #pairWithPayments}: the lines of a prepayment allocation named after the payments that still have money,
 * oldest first, since every allocation row names its payment.</li>
 * </ul>
 * Only exact subtraction and comparison of amounts; nothing is rounded.
 */
final class AllocationPlanner {

    /** The order in which open charges are filled. */
    static final Comparator<ChargeState> ORDER = Comparator.comparing(ChargeState::accruedOn)
            .thenComparingInt(ChargeState::typePosition)
            .thenComparing(ChargeState::serviceStart)
            .thenComparing(ChargeState::createdAt)
            .thenComparing(charge -> charge.id().toString());

    /** The order in which payments give their money to a prepayment allocation. */
    static final Comparator<PaymentState> PAYMENT_ORDER = Comparator.comparing(PaymentState::receivedOn)
            .thenComparing(PaymentState::createdAt)
            .thenComparing(payment -> payment.id().toString());

    /** An amount placed on one charge. */
    record Line(UUID chargeId, Money amount) {

        Line {
            Objects.requireNonNull(chargeId, "chargeId");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * The proposal for an amount.
     *
     * @param lines     the charges that receive money, in {@link #ORDER}, each with its amount (above zero)
     * @param allocated the sum of the lines
     * @param remainder what is not placed: the amount minus {@code allocated}
     */
    record Proposal(List<Line> lines, Money allocated, Money remainder) {

        Proposal {
            lines = List.copyOf(lines);
        }
    }

    /** What is wrong with the treasurer's lines (the codes of the API's problem details). */
    enum Problem {
        /** The lines together exceed the payment (400 {@code ALLOCATION_EXCEEDS_PAYMENT}); no line index. */
        ALLOCATION_EXCEEDS_PAYMENT,
        /** A line exceeds what is open on its charge (400 {@code ALLOCATION_EXCEEDS_CHARGE}). */
        ALLOCATION_EXCEEDS_CHARGE,
        /** A line names no allocatable charge of the plot (400 {@code VALIDATION_FAILED}, field code). */
        NOT_FOUND,
        /** A line of zero or less (400 {@code VALIDATION_FAILED}, field code). */
        AMOUNT_NOT_POSITIVE,
        /** A charge named on an earlier line again (400 {@code VALIDATION_FAILED}, field code). */
        DUPLICATE
    }

    /**
     * One problem.
     *
     * @param line    the index of the line; null for a problem of all lines together
     * @param problem what is wrong
     */
    record Violation(@Nullable Integer line, Problem problem) {
    }

    /** An amount of one payment placed on one charge. */
    record Paired(UUID paymentId, UUID chargeId, Money amount) {
    }

    private AllocationPlanner() {
    }

    /** The charges money can be placed on (not reversed, open above zero), in {@link #ORDER}. */
    static List<ChargeState> openInOrder(Collection<ChargeState> charges) {
        return charges.stream().filter(charge -> charge.allocatable() && charge.open().signum() > 0).sorted(ORDER)
                .toList();
    }

    /**
     * The proposal for placing {@code amount} on the charges.
     *
     * @throws IllegalArgumentException when the amount is negative
     */
    static Proposal propose(Money amount, Collection<ChargeState> charges) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("A negative amount is never allocated");
        }
        List<Line> lines = new ArrayList<>();
        Money remaining = amount;
        for (ChargeState charge : openInOrder(charges)) {
            if (remaining.signum() == 0) {
                break;
            }
            Money part = min(remaining, charge.open());
            lines.add(new Line(charge.id(), part));
            remaining = remaining.minus(part);
        }
        return new Proposal(lines, amount.minus(remaining), remaining);
    }

    /**
     * The problems of the treasurer's lines for a payment of {@code amount} (or the prepayment being allocated) on the
     * plot whose charges are given: every charge named once, every line above zero, each an allocatable charge of the
     * plot that is not reversed ({@code NOT_FOUND} otherwise) and not above its open amount, and the lines together
     * not above the amount. Empty when the lines are fine. The problem of all lines comes first, then the lines in
     * order.
     */
    static List<Violation> validate(Money amount, List<Line> lines, Collection<ChargeState> charges) {
        Map<UUID, ChargeState> byId = new HashMap<>();
        charges.forEach(charge -> byId.put(charge.id(), charge));
        List<Violation> perLine = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Money total = Money.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.amount().signum() > 0) {
                total = total.plus(line.amount());
            }
            ChargeState charge = byId.get(line.chargeId());
            if (!seen.add(line.chargeId())) {
                perLine.add(new Violation(i, Problem.DUPLICATE));
            } else if (line.amount().signum() <= 0) {
                perLine.add(new Violation(i, Problem.AMOUNT_NOT_POSITIVE));
            } else if (charge == null || !charge.allocatable()) {
                perLine.add(new Violation(i, Problem.NOT_FOUND));
            } else if (line.amount().value().compareTo(charge.open().value()) > 0) {
                perLine.add(new Violation(i, Problem.ALLOCATION_EXCEEDS_CHARGE));
            }
        }
        List<Violation> violations = new ArrayList<>();
        if (total.value().compareTo(amount.value()) > 0) {
            violations.add(new Violation(null, Problem.ALLOCATION_EXCEEDS_PAYMENT));
        }
        violations.addAll(perLine);
        return List.copyOf(violations);
    }

    /**
     * The lines (already {@linkplain #validate valid} against the prepayment) as amounts of the payments that still
     * have money left, the oldest payment first ({@link #PAYMENT_ORDER}); a line larger than what is left of one
     * payment continues with the next. The lines keep their order.
     *
     * @throws IllegalArgumentException when a line is not above zero or the lines exceed what the payments have left
     */
    static List<Paired> pairWithPayments(List<Line> lines, Collection<PaymentState> payments) {
        List<PaymentState> withMoney = payments.stream().filter(payment -> payment.left().signum() > 0)
                .sorted(PAYMENT_ORDER).toList();
        List<Paired> paired = new ArrayList<>();
        int next = 0;
        Money left = withMoney.isEmpty() ? Money.ZERO : withMoney.get(0).left();
        for (Line line : lines) {
            if (line.amount().signum() <= 0) {
                throw new IllegalArgumentException("A line is above zero");
            }
            Money remaining = line.amount();
            while (remaining.signum() > 0) {
                if (left.signum() == 0) {
                    next++;
                    if (next >= withMoney.size()) {
                        throw new IllegalArgumentException("The lines exceed what the payments have left");
                    }
                    left = withMoney.get(next).left();
                }
                Money part = min(remaining, left);
                paired.add(new Paired(withMoney.get(next).id(), line.chargeId(), part));
                remaining = remaining.minus(part);
                left = left.minus(part);
            }
        }
        return List.copyOf(paired);
    }

    /** The smaller of two amounts. */
    static Money min(Money a, Money b) {
        return a.value().compareTo(b.value()) <= 0 ? a : b;
    }
}
