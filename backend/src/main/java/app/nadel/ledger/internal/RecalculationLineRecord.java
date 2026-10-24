package app.nadel.ledger.internal;

import app.nadel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One line of a booked recalculation (table {@code recalculation_line}, V19, feature 012): the charge it is about, the
 * booked amount and the adjustments so far, the expected amount, the difference, the action and the charges it booked.
 * Append-only, so {@link Immutable}. {@code reasons} holds codes of the contract's {@code RecalculationChangeReason}.
 */
@Entity
@Immutable
@Table(name = "recalculation_line")
class RecalculationLineRecord {

    /** What the line booked (column {@code action}, the {@code RecalculationAction} of the API contract). */
    enum Action {
        REVERSE_AND_RECHARGE, ADJUST, NEW, REVERSE
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "recalculation_id", nullable = false, updatable = false)
    private UUID recalculationId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "charge_type_id", nullable = false, updatable = false)
    private UUID chargeTypeId;

    @Column(name = "service_from", nullable = false, updatable = false)
    private LocalDate serviceFrom;

    @Column(name = "service_to", nullable = false, updatable = false)
    private LocalDate serviceTo;

    @Column(name = "original_charge_id", updatable = false)
    private UUID originalChargeId;

    @Column(name = "booked_amount", nullable = false, updatable = false)
    private BigDecimal bookedAmount;

    @Column(name = "adjusted_so_far", nullable = false, updatable = false)
    private BigDecimal adjustedSoFar;

    @Column(name = "expected_amount", nullable = false, updatable = false)
    private BigDecimal expectedAmount;

    @Column(nullable = false, updatable = false)
    private BigDecimal difference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Action action;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, updatable = false, columnDefinition = "text[]")
    private List<String> reasons;

    @Column(name = "run_id", updatable = false)
    private UUID runId;

    @Column(name = "reversal_charge_id", updatable = false)
    private UUID reversalChargeId;

    @Column(name = "new_charge_id", updatable = false)
    private UUID newChargeId;

    @Column(name = "adjustment_charge_id", updatable = false)
    private UUID adjustmentChargeId;

    @Column(nullable = false, updatable = false)
    private int position;

    protected RecalculationLineRecord() {
    }

    /** The charges a line booked; each may be null. */
    record Created(@Nullable UUID reversalChargeId, @Nullable UUID newChargeId, @Nullable UUID adjustmentChargeId) {
    }

    /**
     * A line of a booked recalculation (feature 012, slice 3).
     *
     * @param reasons  codes of the contract's {@code RecalculationChangeReason}
     * @param position the line's place in the record (1-based)
     */
    static RecalculationLineRecord of(UUID tenantId, UUID recalculationId, UUID plotId, UUID chargeTypeId,
            LocalDate serviceFrom, LocalDate serviceTo, @Nullable UUID originalChargeId, Money bookedAmount,
            Money adjustedSoFar, Money expectedAmount, Money difference, Action action, List<String> reasons,
            @Nullable UUID runId, Created created, int position) {
        RecalculationLineRecord line = new RecalculationLineRecord();
        line.id = UUID.randomUUID();
        line.tenantId = tenantId;
        line.recalculationId = recalculationId;
        line.plotId = plotId;
        line.chargeTypeId = chargeTypeId;
        line.serviceFrom = serviceFrom;
        line.serviceTo = serviceTo;
        line.originalChargeId = originalChargeId;
        line.bookedAmount = bookedAmount.value();
        line.adjustedSoFar = adjustedSoFar.value();
        line.expectedAmount = expectedAmount.value();
        line.difference = difference.value();
        line.action = action;
        line.reasons = List.copyOf(reasons);
        line.runId = runId;
        line.reversalChargeId = created.reversalChargeId();
        line.newChargeId = created.newChargeId();
        line.adjustmentChargeId = created.adjustmentChargeId();
        line.position = position;
        return line;
    }

    UUID getId() {
        return id;
    }

    UUID getRecalculationId() {
        return recalculationId;
    }

    UUID getPlotId() {
        return plotId;
    }

    UUID getChargeTypeId() {
        return chargeTypeId;
    }

    LocalDate getServiceFrom() {
        return serviceFrom;
    }

    LocalDate getServiceTo() {
        return serviceTo;
    }

    /** The booked charge the line corrects; null for a new charge. */
    @Nullable UUID getOriginalChargeId() {
        return originalChargeId;
    }

    Money getBookedAmount() {
        return Money.of(bookedAmount);
    }

    Money getAdjustedSoFar() {
        return Money.of(adjustedSoFar);
    }

    Money getExpectedAmount() {
        return Money.of(expectedAmount);
    }

    Money getDifference() {
        return Money.of(difference);
    }

    Action getAction() {
        return action;
    }

    List<String> getReasons() {
        return reasons;
    }

    @Nullable UUID getRunId() {
        return runId;
    }

    @Nullable UUID getReversalChargeId() {
        return reversalChargeId;
    }

    @Nullable UUID getNewChargeId() {
        return newChargeId;
    }

    @Nullable UUID getAdjustmentChargeId() {
        return adjustmentChargeId;
    }

    int getPosition() {
        return position;
    }
}
