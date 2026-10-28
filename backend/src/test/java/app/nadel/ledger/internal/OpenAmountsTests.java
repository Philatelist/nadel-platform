package app.nadel.ledger.internal;

import static app.nadel.ledger.internal.PaymentLedger.ELECTRICITY;
import static app.nadel.ledger.internal.PaymentLedger.LAND_TAX;
import static app.nadel.ledger.internal.PaymentLedger.MEMBERSHIP;
import static app.nadel.ledger.internal.PaymentLedger.PLOT;
import static app.nadel.ledger.internal.PaymentLedger.money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.PaymentSource;
import app.nadel.ledger.internal.OpenAmounts.AllocationEntry;
import app.nadel.ledger.internal.OpenAmounts.ChargeEntry;
import app.nadel.ledger.internal.OpenAmounts.PaymentEntry;
import app.nadel.money.Money;
import app.nadel.tariffs.ChargeTypeCatalog.ChargeTypeFacts;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The open amounts of charges and what is left of payments and allocations (feature 015 §2.2, §2.4; technical
 * considerations 2.3): only regular and opening charges receive money, effective = the charge plus its adjustments
 * ({@code 0.00} once reversed), open = effective minus the net allocations; an allocation keeps what its releases leave
 * of it; a reversed payment leaves nothing.
 */
class OpenAmountsTests {

    private static final Instant NOW = Instant.parse("2026-10-28T09:00:00Z");

    private final PaymentLedger ledger = new PaymentLedger();

    @Test
    void onlyRegularAndOpeningChargesAreStates_withTheirAdjustmentsAndAllocations() {
        ChargeEntry regular = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        ledger.adjust(regular, "-100.00");
        ledger.adjust(regular, "30.00");
        ChargeEntry opening = ledger.opening(ELECTRICITY, "1200.00", "2026-01-01");
        ChargeEntry reversed = ledger.charge(LAND_TAX, "120.00", "2026-03-01");
        ledger.reverse(reversed);
        PaymentEntry payment = ledger.pay("700.00", "2026-10-28");
        ledger.allocate(payment, regular, "500.00");
        ledger.allocate(payment, opening, "200.00");

        PlotLedgerState state = ledger.state();

        assertThat(state.charges()).extracting(ChargeState::id).containsExactly(regular.id(), opening.id(),
                reversed.id());
        ChargeState membership = state.charge(regular.id()).orElseThrow();
        assertThat(membership.effective()).isEqualTo(money("530.00"));
        assertThat(membership.allocated()).isEqualTo(money("500.00"));
        assertThat(membership.open()).isEqualTo(money("30.00"));
        assertThat(membership.typePosition()).isZero();
        assertThat(state.charge(opening.id()).orElseThrow().kind()).isEqualTo(ChargeKind.OPENING);
        assertThat(state.charge(opening.id()).orElseThrow().open()).isEqualTo(money("1000.00"));
        ChargeState land = state.charge(reversed.id()).orElseThrow();
        assertThat(land.reversed()).isTrue();
        assertThat(land.allocatable()).isFalse();
        assertThat(land.effective()).isEqualTo(Money.ZERO);
        assertThat(land.typePosition()).isEqualTo(2);
        assertThat(state.charge(UUID.randomUUID())).isEmpty();
    }

    @Test
    void anAllocationKeepsWhatItsReleasesLeaveOfIt_andAPaymentItsNetAllocations() {
        ChargeEntry membership = ledger.charge(MEMBERSHIP, "600.00", "2026-01-01");
        PaymentEntry payment = ledger.pay("700.00", "2026-10-28");
        AllocationEntry allocation = ledger.allocate(payment, membership, "600.00");
        ledger.release(List.of(new ReleasePlanner.Release(allocation.id(), payment.id(), membership.id(), PLOT,
                money("250.00"), ReleaseCause.REALLOCATED)));

        PlotLedgerState state = ledger.state();

        assertThat(state.allocations()).singleElement().satisfies(left -> {
            assertThat(left.id()).isEqualTo(allocation.id());
            assertThat(left.remaining()).isEqualTo(money("350.00"));
            assertThat(left.live()).isTrue();
        });
        assertThat(state.payments()).singleElement().satisfies(paid -> {
            assertThat(paid.allocated()).isEqualTo(money("350.00"));
            assertThat(paid.left()).isEqualTo(money("350.00"));
            assertThat(paid.reversed()).isFalse();
        });
        assertThat(state.charge(membership.id()).orElseThrow().open()).isEqualTo(money("250.00"));
        assertThat(state.prepayment()).isEqualTo(money("350.00"));
    }

    @Test
    void aReversedPaymentLeavesNothing() {
        PaymentEntry payment = ledger.pay("700.00", "2026-10-28");
        ledger.reversePayment(payment);

        PlotLedgerState state = ledger.state();

        assertThat(state.payments()).singleElement().satisfies(paid -> {
            assertThat(paid.reversed()).isTrue();
            assertThat(paid.left()).isEqualTo(Money.ZERO);
        });
        assertThat(state.prepayment()).isEqualTo(Money.ZERO);
    }

    @Test
    void theTypePositionsAreTheIndexesOfTheCatalogOrder_anUnknownTypeComesLast() {
        ChargeTypeFacts first = new ChargeTypeFacts(UUID.randomUUID(), "MEMBERSHIP", "Membership", false, true, false,
                5);
        ChargeTypeFacts second = new ChargeTypeFacts(UUID.randomUUID(), null, "Охорона", true, true, false, 5);

        Map<UUID, Integer> positions = OpenAmounts.positions(List.of(first, second));

        assertThat(positions).containsOnly(Map.entry(first.id(), 0), Map.entry(second.id(), 1));
        PlotLedgerState state = OpenAmounts.of(List.of(new ChargeEntry(UUID.randomUUID(), PLOT, ChargeKind.REGULAR,
                UUID.randomUUID(), money("1.00"), LocalDate.parse("2026-01-01"), null, NOW, null, null)), List.of(),
                List.of(), positions);
        assertThat(state.charges().get(0).typePosition()).isEqualTo(OpenAmounts.UNKNOWN_POSITION);
    }

    @Test
    void theEntriesOfStoredRowsCarryTheirFields() {
        UUID tenant = UUID.randomUUID();
        UUID month = UUID.randomUUID();
        ChargeRecord charge = ChargeRecord.opening(tenant, PLOT, MEMBERSHIP, month, money("1200.00"),
                LocalDate.parse("2026-01-01"), null, null, Map.of(), null, NOW);
        PaymentRecord payment = PaymentRecord.payment(UUID.randomUUID(), tenant, PLOT, PaymentSource.CASH,
                money("500.00"), LocalDate.parse("2026-10-28"), month, UUID.randomUUID(), 42L, null, null, null, null,
                NOW);
        AllocationRecord allocation = AllocationRecord.allocate(tenant, PLOT, payment.getId(), charge.getId(),
                money("500.00"), AllocationOrigin.ENTRY, null, NOW);

        assertThat(OpenAmounts.entry(charge)).isEqualTo(new ChargeEntry(charge.getId(), PLOT, ChargeKind.OPENING,
                MEMBERSHIP, money("1200.00"), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"), NOW, null,
                null));
        assertThat(OpenAmounts.entry(payment)).isEqualTo(new PaymentEntry(payment.getId(), PLOT, PaymentKind.PAYMENT,
                money("500.00"), LocalDate.parse("2026-10-28"), NOW, null));
        assertThat(OpenAmounts.entry(allocation)).isEqualTo(new AllocationEntry(allocation.getId(), payment.getId(),
                charge.getId(), PLOT, money("500.00"), null, NOW));
        assertThat(OpenAmounts.of(List.of(OpenAmounts.entry(charge)), List.of(OpenAmounts.entry(allocation)),
                List.of(OpenAmounts.entry(payment)), Map.of()).charges().get(0).open()).isEqualTo(money("700.00"));
    }

    @Test
    void aStateIsOfAnAllocatableKindAndAReversedChargeHasNothingEffective() {
        LocalDate day = LocalDate.parse("2026-01-01");
        assertThatThrownBy(() -> new ChargeState(UUID.randomUUID(), PLOT, ChargeKind.REVERSAL, MEMBERSHIP, 0, day,
                day, NOW, Money.ZERO, Money.ZERO, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChargeState(UUID.randomUUID(), PLOT, ChargeKind.REGULAR, MEMBERSHIP, 0, day,
                day, NOW, money("1.00"), Money.ZERO, true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ChargeState(UUID.randomUUID(), PLOT, ChargeKind.REGULAR, MEMBERSHIP, 0, day, null, NOW,
                money("1.00"), Money.ZERO, false).serviceStart()).isEqualTo(day);
        assertThat(new AllocationState(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), PLOT, Money.ZERO,
                NOW).live()).isFalse();
    }

    @Test
    void theStateCannotBeChangedFromOutside() {
        ledger.charge(MEMBERSHIP, "1.00", "2026-01-01");
        PlotLedgerState state = ledger.state();

        assertThatThrownBy(() -> state.charges().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> state.payments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
