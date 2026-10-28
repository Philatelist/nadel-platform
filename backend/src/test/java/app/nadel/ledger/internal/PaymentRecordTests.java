package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.ledger.PaymentSource;
import app.nadel.ledger.internal.ReleasePlanner.Release;
import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The payment and allocation rows (feature 015 §2.1-§2.3, V27): a cash payment carries a receipt number and a bank
 * payment none; a reversal negates its payment and keeps its plot, source, group and date; an allocation is above zero
 * and a release is its negated amount pointing at the allocation it takes back.
 */
class PaymentRecordTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PLOT = UUID.randomUUID();
    private static final UUID JANUARY = UUID.randomUUID();
    private static final UUID FEBRUARY = UUID.randomUUID();
    private static final UUID TREASURER = UUID.randomUUID();
    private static final UUID CHAIRMAN = UUID.randomUUID();
    private static final LocalDate RECEIVED = LocalDate.parse("2026-01-31");
    private static final Instant NOW = Instant.parse("2026-02-03T10:00:00Z");

    private static PaymentRecord cash(String amount, long receipt) {
        UUID id = UUID.randomUUID();
        return PaymentRecord.payment(id, TENANT, PLOT, PaymentSource.CASH, Money.parse(amount), RECEIVED, FEBRUARY,
                id, receipt, null, null, null, TREASURER, NOW);
    }

    @Test
    void aCashPaymentCarriesItsReceiptNumberAndWhatWasEntered() {
        UUID id = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        byte[] note = {1, 2, 3};

        PaymentRecord payment = PaymentRecord.payment(id, TENANT, PLOT, PaymentSource.CASH, Money.parse("700.00"),
                RECEIVED, FEBRUARY, group, 42L, null, payer, note, TREASURER, NOW);

        assertThat(payment.getId()).isEqualTo(id);
        assertThat(payment.getTenantId()).isEqualTo(TENANT);
        assertThat(payment.getPlotId()).isEqualTo(PLOT);
        assertThat(payment.getKind()).isEqualTo(PaymentKind.PAYMENT);
        assertThat(payment.getSource()).isEqualTo(PaymentSource.CASH);
        assertThat(payment.getAmount()).isEqualTo(Money.parse("700.00"));
        assertThat(payment.getReceivedOn()).isEqualTo(RECEIVED);
        assertThat(payment.getBillingMonthId()).isEqualTo(FEBRUARY);
        assertThat(payment.getGroupId()).isEqualTo(group);
        assertThat(payment.getReceiptNumber()).isEqualTo(42L);
        assertThat(payment.getBankReference()).isNull();
        assertThat(payment.getPayerMemberId()).isEqualTo(payer);
        assertThat(payment.getPayerNoteEnc()).isEqualTo(note);
        assertThat(payment.getReversesPaymentId()).isNull();
        assertThat(payment.getReason()).isNull();
        assertThat(payment.getCreatedBy()).isEqualTo(TREASURER);
        assertThat(payment.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void aBankPaymentHasNoReceiptNumberAndMayHaveABankReference() {
        UUID id = UUID.randomUUID();

        PaymentRecord payment = PaymentRecord.payment(id, TENANT, PLOT, PaymentSource.BANK, Money.parse("250.00"),
                RECEIVED, FEBRUARY, id, null, "ПЛ-0042", null, null, TREASURER, NOW);

        assertThat(payment.getReceiptNumber()).isNull();
        assertThat(payment.getBankReference()).isEqualTo("ПЛ-0042");
    }

    @Test
    void aPaymentIsAboveZeroAndExactlyACashPaymentHasAReceiptNumber() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> PaymentRecord.payment(id, TENANT, PLOT, PaymentSource.CASH, Money.ZERO, RECEIVED,
                FEBRUARY, id, 1L, null, null, null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.payment(id, TENANT, PLOT, PaymentSource.CASH, Money.parse("1.00"),
                RECEIVED, FEBRUARY, id, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.payment(id, TENANT, PLOT, PaymentSource.CASH, Money.parse("1.00"),
                RECEIVED, FEBRUARY, id, 0L, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.payment(id, TENANT, PLOT, PaymentSource.BANK, Money.parse("1.00"),
                RECEIVED, FEBRUARY, id, 1L, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.payment(id, TENANT, PLOT, PaymentSource.CASH, Money.parse("1.00"),
                RECEIVED, FEBRUARY, id, 1L, "ref", null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReversalNegatesItsPaymentAndKeepsItsPlotSourceGroupAndDate() {
        PaymentRecord original = cash("700.00", 41L);

        PaymentRecord reversal = PaymentRecord.reversalOf(original, JANUARY, "Помилкова ділянка", CHAIRMAN, NOW);

        assertThat(reversal.getId()).isNotEqualTo(original.getId());
        assertThat(reversal.getKind()).isEqualTo(PaymentKind.PAYMENT_REVERSAL);
        assertThat(reversal.getAmount()).isEqualTo(Money.parse("-700.00"));
        assertThat(reversal.getPlotId()).isEqualTo(PLOT);
        assertThat(reversal.getTenantId()).isEqualTo(TENANT);
        assertThat(reversal.getSource()).isEqualTo(PaymentSource.CASH);
        assertThat(reversal.getGroupId()).isEqualTo(original.getGroupId());
        assertThat(reversal.getReceivedOn()).isEqualTo(RECEIVED);
        assertThat(reversal.getBillingMonthId()).isEqualTo(JANUARY);
        assertThat(reversal.getReversesPaymentId()).isEqualTo(original.getId());
        assertThat(reversal.getReason()).isEqualTo("Помилкова ділянка");
        assertThat(reversal.getReceiptNumber()).isNull();
        assertThat(reversal.getBankReference()).isNull();
        assertThat(reversal.getPayerMemberId()).isNull();
        assertThat(reversal.getPayerNoteEnc()).isNull();
        assertThat(reversal.getCreatedBy()).isEqualTo(CHAIRMAN);
    }

    @Test
    void aReversalNeedsAReasonAndIsNeverReversedItself() {
        PaymentRecord original = cash("700.00", 41L);
        PaymentRecord reversal = PaymentRecord.reversalOf(original, JANUARY, "Помилка", CHAIRMAN, NOW);

        assertThatThrownBy(() -> PaymentRecord.reversalOf(original, JANUARY, " ", CHAIRMAN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRecord.reversalOf(reversal, JANUARY, "Ще раз", CHAIRMAN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAllocationIsAboveZeroWithItsOrigin() {
        UUID payment = UUID.randomUUID();
        UUID charge = UUID.randomUUID();

        AllocationRecord allocation = AllocationRecord.allocate(TENANT, PLOT, payment, charge, Money.parse("600.00"),
                AllocationOrigin.PREPAYMENT_AUTO, null, NOW);

        assertThat(allocation.getTenantId()).isEqualTo(TENANT);
        assertThat(allocation.getPlotId()).isEqualTo(PLOT);
        assertThat(allocation.getPaymentId()).isEqualTo(payment);
        assertThat(allocation.getChargeId()).isEqualTo(charge);
        assertThat(allocation.getAmount()).isEqualTo(Money.parse("600.00"));
        assertThat(allocation.getOrigin()).isEqualTo(AllocationOrigin.PREPAYMENT_AUTO);
        assertThat(allocation.getReleaseCause()).isNull();
        assertThat(allocation.getReleasesAllocationId()).isNull();
        assertThat(allocation.getCreatedBy()).isNull();
        assertThat(allocation.getCreatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> AllocationRecord.allocate(TENANT, PLOT, payment, charge, Money.ZERO,
                AllocationOrigin.ENTRY, null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReleaseIsTheNegatedAmountPointingAtItsAllocationWithItsCause() {
        AllocationRecord allocation = AllocationRecord.allocate(TENANT, PLOT, UUID.randomUUID(), UUID.randomUUID(),
                Money.parse("600.00"), AllocationOrigin.ENTRY, TREASURER, NOW);

        AllocationRecord release = AllocationRecord.releaseOf(TENANT, new Release(allocation.getId(),
                allocation.getPaymentId(), allocation.getChargeId(), PLOT, Money.parse("100.00"),
                ReleaseCause.CHARGE_ADJUSTED), null, NOW);

        assertThat(release.getId()).isNotEqualTo(allocation.getId());
        assertThat(release.getAmount()).isEqualTo(Money.parse("-100.00"));
        assertThat(release.getReleasesAllocationId()).isEqualTo(allocation.getId());
        assertThat(release.getPaymentId()).isEqualTo(allocation.getPaymentId());
        assertThat(release.getChargeId()).isEqualTo(allocation.getChargeId());
        assertThat(release.getPlotId()).isEqualTo(PLOT);
        assertThat(release.getReleaseCause()).isEqualTo(ReleaseCause.CHARGE_ADJUSTED);
        assertThat(release.getOrigin()).isNull();
        assertThat(release.getCreatedBy()).isNull();
    }
}
