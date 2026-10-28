package app.nadel.ledger.internal;

import app.nadel.ledger.internal.AllocationPlanner.Line;
import app.nadel.ledger.internal.AllocationPlanner.Paired;
import app.nadel.ledger.internal.DebtCalculator.Debts;
import app.nadel.ledger.internal.OpenAmounts.AllocationEntry;
import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.ledger.internal.ReleasePlanner.Release;
import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ledger rows of one plot for the pure payment tests (feature 015 §3), in the charge type order of the examples:
 * Membership, Electricity, Land tax. Every row is booked one second after the previous one, so booking order is the
 * order of the calls. {@link #state()} and {@link #debts()} compute through {@link OpenAmounts} and
 * {@link DebtCalculator} exactly as the service will.
 */
final class PaymentLedger {

    static final UUID PLOT = UUID.randomUUID();
    static final UUID MEMBERSHIP = UUID.randomUUID();
    static final UUID ELECTRICITY = UUID.randomUUID();
    static final UUID LAND_TAX = UUID.randomUUID();
    static final Map<UUID, Integer> POSITIONS = Map.of(MEMBERSHIP, 0, ELECTRICITY, 1, LAND_TAX, 2);

    private final List<ChargeEntry> charges = new ArrayList<>();
    private final List<AllocationEntry> allocations = new ArrayList<>();
    private final List<PaymentEntry> payments = new ArrayList<>();
    private Instant clock = Instant.parse("2026-10-01T08:00:00Z");

    static Money money(String amount) {
        return Money.parse(amount);
    }

    static Line line(ChargeEntry charge, String amount) {
        return new Line(charge.id(), money(amount));
    }

    private Instant next() {
        clock = clock.plusSeconds(1);
        return clock;
    }

    /** A regular charge whose service period starts on its debt date. */
    ChargeEntry charge(UUID type, String amount, String accruedOn) {
        return charge(type, amount, accruedOn, accruedOn);
    }

    ChargeEntry charge(UUID type, String amount, String accruedOn, String serviceFrom) {
        ChargeEntry charge = new ChargeEntry(UUID.randomUUID(), PLOT, ChargeKind.REGULAR, type, money(amount),
                LocalDate.parse(accruedOn), serviceFrom == null ? null : LocalDate.parse(serviceFrom), next(), null,
                null);
        charges.add(charge);
        return charge;
    }

    ChargeEntry opening(UUID type, String amount, String date) {
        ChargeEntry charge = new ChargeEntry(UUID.randomUUID(), PLOT, ChargeKind.OPENING, type, money(amount),
                LocalDate.parse(date), LocalDate.parse(date), next(), null, null);
        charges.add(charge);
        return charge;
    }

    ChargeEntry reverse(ChargeEntry original) {
        ChargeEntry reversal = new ChargeEntry(UUID.randomUUID(), PLOT, ChargeKind.REVERSAL, original.chargeTypeId(),
                original.amount().negate(), original.accruedOn(), original.serviceFrom(), next(), original.id(), null);
        charges.add(reversal);
        return reversal;
    }

    ChargeEntry adjust(ChargeEntry original, String amount) {
        ChargeEntry adjustment = new ChargeEntry(UUID.randomUUID(), PLOT, ChargeKind.ADJUSTMENT,
                original.chargeTypeId(), money(amount), original.accruedOn(), original.serviceFrom(), next(), null,
                original.id());
        charges.add(adjustment);
        return adjustment;
    }

    PaymentEntry pay(String amount, String receivedOn) {
        PaymentEntry payment = new PaymentEntry(UUID.randomUUID(), PLOT, PaymentKind.PAYMENT, money(amount),
                LocalDate.parse(receivedOn), next(), null);
        payments.add(payment);
        return payment;
    }

    PaymentEntry reversePayment(PaymentEntry original) {
        PaymentEntry reversal = new PaymentEntry(UUID.randomUUID(), PLOT, PaymentKind.PAYMENT_REVERSAL,
                original.amount().negate(), original.receivedOn(), next(), original.id());
        payments.add(reversal);
        return reversal;
    }

    AllocationEntry allocate(PaymentEntry payment, ChargeEntry charge, String amount) {
        return allocate(payment.id(), charge.id(), money(amount));
    }

    private AllocationEntry allocate(UUID paymentId, UUID chargeId, Money amount) {
        AllocationEntry allocation = new AllocationEntry(UUID.randomUUID(), paymentId, chargeId, PLOT, amount, null,
                next());
        allocations.add(allocation);
        return allocation;
    }

    /** The lines of one payment written as allocations. */
    void apply(PaymentEntry payment, Collection<Line> lines) {
        lines.forEach(line -> allocate(payment.id(), line.chargeId(), line.amount()));
    }

    /** Prepayment lines paired with their payments, written as allocations. */
    void applyPaired(Collection<Paired> paired) {
        paired.forEach(part -> allocate(part.paymentId(), part.chargeId(), part.amount()));
    }

    /** The releases written as release rows. */
    void release(Collection<Release> releases) {
        for (Release release : releases) {
            allocations.add(new AllocationEntry(UUID.randomUUID(), release.paymentId(), release.chargeId(),
                    release.plotId(), release.amount().negate(), release.allocationId(), next()));
        }
    }

    PlotLedgerState state() {
        return OpenAmounts.of(charges, allocations, payments, POSITIONS);
    }

    Debts debts() {
        return DebtCalculator.of(charges, allocations, payments);
    }

    Money open(ChargeEntry charge) {
        return state().charge(charge.id()).orElseThrow().open();
    }

    List<ChargeEntry> charges() {
        return List.copyOf(charges);
    }

    List<AllocationEntry> allocations() {
        return List.copyOf(allocations);
    }

    List<PaymentEntry> payments() {
        return List.copyOf(payments);
    }
}
