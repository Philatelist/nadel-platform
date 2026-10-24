package app.nadel.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.nadel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rows a recalculation books (feature 012 §2.1 and §2.3): an adjustment keeps the original's plot, charge type,
 * service period and debt age and points at it and at the recalculation; a reversal of a recalculation is a reversal
 * with source {@code RECALCULATION}; a reversal by hand keeps source {@code MANUAL_REVERSAL}.
 */
class ChargeRecordTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PLOT = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final UUID SEPTEMBER = UUID.randomUUID();
    private static final UUID OCTOBER = UUID.randomUUID();
    private static final UUID TARIFF = UUID.randomUUID();
    private static final UUID RECALCULATION = UUID.randomUUID();
    private static final UUID CHAIRMAN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private static ChargeRecord september() {
        return ChargeRecord.tariff(TENANT, PLOT, MEMBERSHIP, SEPTEMBER, TARIFF, Money.parse("30.00"),
                LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-01"),
                "TARIFF:" + TARIFF + ":" + PLOT + ":2026-09", Map.of(), null, NOW);
    }

    @Test
    void anAdjustmentKeepsTheOriginalPeriodAndDebtAgeAndIsBookedInTheOpenMonth() {
        // functional spec 2.3: September 30.00 should have been 25.00 -> adjustment -5.00 in October
        ChargeRecord original = september();

        ChargeRecord adjustment = ChargeRecord.adjustment(original, OCTOBER, RECALCULATION, Money.parse("-5.00"),
                "Rate corrected", Map.of("amount", "-5.00"), CHAIRMAN, NOW);

        assertThat(adjustment.getKind()).isEqualTo(ChargeKind.ADJUSTMENT);
        assertThat(adjustment.getSource()).isEqualTo(ChargeSource.RECALCULATION);
        assertThat(adjustment.getAmount()).isEqualTo(Money.parse("-5.00"));
        assertThat(adjustment.getBillingMonthId()).isEqualTo(OCTOBER);
        assertThat(adjustment.getPlotId()).isEqualTo(PLOT);
        assertThat(adjustment.getChargeTypeId()).isEqualTo(MEMBERSHIP);
        assertThat(adjustment.getServiceFrom()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(adjustment.getServiceTo()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(adjustment.getAccruedOn()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(adjustment.getTariffId()).isEqualTo(TARIFF);
        assertThat(adjustment.getAdjustsChargeId()).isEqualTo(original.getId());
        assertThat(adjustment.getRecalculationId()).isEqualTo(RECALCULATION);
        assertThat(adjustment.getReversesChargeId()).isNull();
        assertThat(adjustment.getReason()).isEqualTo("Rate corrected");
        assertThat(adjustment.getSourceKey()).isEqualTo("ADJUSTMENT:" + RECALCULATION + ":" + original.getId());
        assertThat(adjustment.getCreatedBy()).isEqualTo(CHAIRMAN);
        assertThat(adjustment.getId()).isNotEqualTo(original.getId());
    }

    @Test
    void anAdjustmentIsNeverZeroAndCorrectsARegularChargeOnly() {
        ChargeRecord original = september();
        ChargeRecord adjustment = ChargeRecord.adjustment(original, OCTOBER, RECALCULATION, Money.parse("2.50"),
                "Rate corrected", Map.of(), null, NOW);

        assertThatThrownBy(() -> ChargeRecord.adjustment(original, OCTOBER, RECALCULATION, Money.ZERO, "x", Map.of(),
                null, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeRecord.adjustment(adjustment, OCTOBER, UUID.randomUUID(), Money.parse("1.00"),
                "x", Map.of(), null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReversalOfARecalculationHasItsSourceAndId() {
        ChargeRecord original = september();

        ChargeRecord reversal = ChargeRecord.recalculationReversalOf(original, SEPTEMBER, RECALCULATION,
                "Rate corrected", Map.of(), CHAIRMAN, NOW);

        assertThat(reversal.getKind()).isEqualTo(ChargeKind.REVERSAL);
        assertThat(reversal.getSource()).isEqualTo(ChargeSource.RECALCULATION);
        assertThat(reversal.getAmount()).isEqualTo(Money.parse("-30.00"));
        assertThat(reversal.getReversesChargeId()).isEqualTo(original.getId());
        assertThat(reversal.getRecalculationId()).isEqualTo(RECALCULATION);
        assertThat(reversal.getAccruedOn()).isEqualTo(original.getAccruedOn());
        assertThat(reversal.getSourceKey()).isNull();
    }

    @Test
    void aReversalByHandHasNoRecalculation() {
        ChargeRecord reversal = ChargeRecord.reversalOf(september(), SEPTEMBER, "Mistake", Map.of(), CHAIRMAN, NOW);

        assertThat(reversal.getSource()).isEqualTo(ChargeSource.MANUAL_REVERSAL);
        assertThat(reversal.getRecalculationId()).isNull();
        assertThat(reversal.getAdjustsChargeId()).isNull();
    }
}
