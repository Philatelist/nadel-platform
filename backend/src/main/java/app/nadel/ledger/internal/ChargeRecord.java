package app.nadel.ledger.internal;

import app.nadel.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One row of the ledger (table {@code charge}, V13). Append-only: the application role has no {@code UPDATE} or
 * {@code DELETE} on the table and a trigger refuses both to everyone else, so the entity is {@link Immutable} and only
 * ever inserted ({@code EntityManager.persist}). A mistake is cancelled by a {@link ChargeKind#REVERSAL} row; a charge
 * of a closed month is corrected by an {@link ChargeKind#ADJUSTMENT} row (V19) booked by a recalculation.
 * <p>
 * {@code calculation} holds codes and numbers only (the {@code ChargeCalculation} of the API contract), never display
 * text.
 */
@Entity
@Immutable
@Table(name = "charge")
class ChargeRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(name = "charge_type_id", nullable = false, updatable = false)
    private UUID chargeTypeId;

    @Column(name = "billing_month_id", nullable = false, updatable = false)
    private UUID billingMonthId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ChargeKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ChargeSource source;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "service_from", nullable = false, updatable = false)
    private LocalDate serviceFrom;

    @Column(name = "service_to", nullable = false, updatable = false)
    private LocalDate serviceTo;

    @Column(name = "accrued_on", nullable = false, updatable = false)
    private LocalDate accruedOn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> calculation;

    @Column(name = "source_key", updatable = false)
    private String sourceKey;

    @Column(name = "tariff_id", updatable = false)
    private UUID tariffId;

    @Column(name = "meter_id", updatable = false)
    private UUID meterId;

    @Column(name = "run_id", updatable = false)
    private UUID runId;

    @Column(name = "reverses_charge_id", updatable = false)
    private UUID reversesChargeId;

    @Column(name = "adjusts_charge_id", updatable = false)
    private UUID adjustsChargeId;

    @Column(name = "recalculation_id", updatable = false)
    private UUID recalculationId;

    @Column(updatable = false)
    private String reason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChargeRecord() {
    }

    /**
     * An opening balance: kind and source {@code OPENING}, the service period and the debt age are the one day
     * {@code date}; {@code reason} is the optional reason or document reference (user content), null when none;
     * {@code sourceKey} is {@code OPENING:{importId}:{row}} for a balance of the Excel import (a commit never books a row
     * twice) and null for one entered by hand.
     */
    static ChargeRecord opening(UUID tenantId, UUID plotId, UUID chargeTypeId, UUID billingMonthId, Money amount,
            LocalDate date, @Nullable String reason, @Nullable String sourceKey, Map<String, Object> calculation,
            @Nullable UUID createdBy, Instant now) {
        ChargeRecord charge = new ChargeRecord();
        charge.id = UUID.randomUUID();
        charge.tenantId = tenantId;
        charge.plotId = plotId;
        charge.chargeTypeId = chargeTypeId;
        charge.billingMonthId = billingMonthId;
        charge.kind = ChargeKind.OPENING;
        charge.source = ChargeSource.OPENING;
        charge.amount = amount.value();
        charge.serviceFrom = date;
        charge.serviceTo = date;
        charge.accruedOn = date;
        charge.reason = reason;
        charge.sourceKey = sourceKey;
        charge.calculation = calculation;
        charge.createdBy = createdBy;
        charge.createdAt = now;
        return charge;
    }

    /**
     * A charge of the month run (kind {@code REGULAR}, source {@code TARIFF}): one tariff for one plot and one month,
     * identified by {@code sourceKey} ({@code TARIFF:{tariff}:{plot}:{YYYY-MM}}), so a rerun never books it twice.
     */
    static ChargeRecord tariff(UUID tenantId, UUID plotId, UUID chargeTypeId, UUID billingMonthId, UUID tariffId,
            Money amount, LocalDate serviceFrom, LocalDate serviceTo, LocalDate accruedOn, String sourceKey,
            Map<String, Object> calculation, @Nullable UUID createdBy, Instant now) {
        return tariff(tenantId, plotId, chargeTypeId, billingMonthId, tariffId, amount, serviceFrom, serviceTo,
                accruedOn, sourceKey, calculation, null, createdBy, now);
    }

    /**
     * A tariff charge as {@link #tariff} makes it, booked by the recalculation {@code recalculationId} (feature 012:
     * the charge that replaces a reversed one, or a charge that was missing), or by the month run when null. The
     * recalculation books it with the base key, or with {@code base#r{recalculationId}} when a charge has the base key.
     */
    static ChargeRecord tariff(UUID tenantId, UUID plotId, UUID chargeTypeId, UUID billingMonthId, UUID tariffId,
            Money amount, LocalDate serviceFrom, LocalDate serviceTo, LocalDate accruedOn, String sourceKey,
            Map<String, Object> calculation, @Nullable UUID recalculationId, @Nullable UUID createdBy, Instant now) {
        ChargeRecord charge = new ChargeRecord();
        charge.id = UUID.randomUUID();
        charge.tenantId = tenantId;
        charge.plotId = plotId;
        charge.chargeTypeId = chargeTypeId;
        charge.billingMonthId = billingMonthId;
        charge.recalculationId = recalculationId;
        charge.kind = ChargeKind.REGULAR;
        charge.source = ChargeSource.TARIFF;
        charge.amount = amount.value();
        charge.serviceFrom = serviceFrom;
        charge.serviceTo = serviceTo;
        charge.accruedOn = accruedOn;
        charge.calculation = calculation;
        charge.sourceKey = sourceKey;
        charge.tariffId = tariffId;
        charge.createdBy = createdBy;
        charge.createdAt = now;
        return charge;
    }

    /**
     * A charge of a meter interval (kind {@code REGULAR}, source {@code CONSUMPTION} or {@code LOSSES}): the tariff and
     * the meter it was priced from, identified by {@code sourceKey} ({@code CONSUMPTION:{fromKey}:{toKey}} or
     * {@code LOSSES:...}), so a redelivered event never books it twice. Booked by the system (no author) or by the
     * person who retried it.
     */
    static ChargeRecord reading(UUID tenantId, UUID plotId, UUID chargeTypeId, UUID billingMonthId, ChargeSource source,
            UUID tariffId, UUID meterId, Money amount, LocalDate serviceFrom, LocalDate serviceTo, LocalDate accruedOn,
            String sourceKey, Map<String, Object> calculation, @Nullable UUID createdBy, Instant now) {
        if (source != ChargeSource.CONSUMPTION && source != ChargeSource.LOSSES) {
            throw new IllegalArgumentException("A reading charge is a consumption or a losses charge");
        }
        ChargeRecord charge = new ChargeRecord();
        charge.id = UUID.randomUUID();
        charge.tenantId = tenantId;
        charge.plotId = plotId;
        charge.chargeTypeId = chargeTypeId;
        charge.billingMonthId = billingMonthId;
        charge.kind = ChargeKind.REGULAR;
        charge.source = source;
        charge.amount = amount.value();
        charge.serviceFrom = serviceFrom;
        charge.serviceTo = serviceTo;
        charge.accruedOn = accruedOn;
        charge.calculation = calculation;
        charge.sourceKey = sourceKey;
        charge.tariffId = tariffId;
        charge.meterId = meterId;
        charge.createdBy = createdBy;
        charge.createdAt = now;
        return charge;
    }

    /**
     * A losses charge of a proportional losses run (kind {@code REGULAR}, source {@code LOSSES}): the plot's kWh from
     * every node of the run priced with its electricity tariff, for the days of the run's range, the debt counting from
     * its end; identified by {@code sourceKey} ({@code LOSSES_RUN:{run}:{plot}}) and pointing at the run.
     */
    static ChargeRecord lossesRun(UUID tenantId, UUID plotId, UUID chargeTypeId, UUID billingMonthId, UUID tariffId,
            UUID runId, Money amount, LocalDate serviceFrom, LocalDate serviceTo, String sourceKey,
            Map<String, Object> calculation, @Nullable UUID createdBy, Instant now) {
        ChargeRecord charge = new ChargeRecord();
        charge.id = UUID.randomUUID();
        charge.tenantId = tenantId;
        charge.plotId = plotId;
        charge.chargeTypeId = chargeTypeId;
        charge.billingMonthId = billingMonthId;
        charge.kind = ChargeKind.REGULAR;
        charge.source = ChargeSource.LOSSES;
        charge.amount = amount.value();
        charge.serviceFrom = serviceFrom;
        charge.serviceTo = serviceTo;
        charge.accruedOn = serviceTo;
        charge.calculation = calculation;
        charge.sourceKey = sourceKey;
        charge.tariffId = tariffId;
        charge.runId = runId;
        charge.createdBy = createdBy;
        charge.createdAt = now;
        return charge;
    }

    /**
     * A charge of a common consumer run (kind {@code REGULAR}, source {@code COMMON_CONSUMER}, charge type
     * {@code COMMON_CONSUMERS}): one plot's share of one consumer priced with its tariff, for the days of the run's
     * range, the debt counting from its end; identified by {@code sourceKey}
     * ({@code COMMON_CONSUMER_RUN:{run}:{consumer}:{plot}}) and pointing at the run.
     */
    static ChargeRecord commonConsumerRun(UUID tenantId, UUID plotId, UUID chargeTypeId, UUID billingMonthId,
            UUID tariffId, UUID runId, Money amount, LocalDate serviceFrom, LocalDate serviceTo, String sourceKey,
            Map<String, Object> calculation, @Nullable UUID createdBy, Instant now) {
        ChargeRecord charge = lossesRun(tenantId, plotId, chargeTypeId, billingMonthId, tariffId, runId, amount,
                serviceFrom, serviceTo, sourceKey, calculation, createdBy, now);
        charge.source = ChargeSource.COMMON_CONSUMER;
        return charge;
    }

    /**
     * The reversal of {@code original}: the opposite amount, the same plot, charge type, service period, debt age, meter
     * and run, source {@code MANUAL_REVERSAL}, the reason, pointing at the original.
     */
    static ChargeRecord reversalOf(ChargeRecord original, UUID billingMonthId, String reason,
            Map<String, Object> calculation, @Nullable UUID createdBy, Instant now) {
        return reversal(original, billingMonthId, ChargeSource.MANUAL_REVERSAL, null, reason, calculation, createdBy,
                now);
    }

    /**
     * The reversal of {@code original} booked by a recalculation (feature 012): {@link #reversalOf} with source
     * {@code RECALCULATION} and the recalculation's id, so a reversal made by hand stays recognisable.
     */
    static ChargeRecord recalculationReversalOf(ChargeRecord original, UUID billingMonthId, UUID recalculationId,
            String reason, Map<String, Object> calculation, @Nullable UUID createdBy, Instant now) {
        return reversal(original, billingMonthId, ChargeSource.RECALCULATION, recalculationId, reason, calculation,
                createdBy, now);
    }

    /**
     * An adjustment of {@code original} booked by a recalculation (feature 012): kind {@code ADJUSTMENT}, source
     * {@code RECALCULATION}, {@code amount} (expected minus effective, positive or negative, never zero) booked in
     * {@code billingMonthId} (the earliest open month), with the original's plot, charge type, service period, debt age
     * ({@code accrued_on}), tariff, meter and run, the reason, and pointing at the original and the recalculation;
     * identified by {@code sourceKey} {@code ADJUSTMENT:{recalculationId}:{originalId}}, so a charge is adjusted at
     * most once per recalculation.
     *
     * @throws IllegalArgumentException when {@code amount} is zero or the original is not a {@code REGULAR} charge
     */
    static ChargeRecord adjustment(ChargeRecord original, UUID billingMonthId, UUID recalculationId, Money amount,
            String reason, Map<String, Object> calculation, @Nullable UUID createdBy, Instant now) {
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("An adjustment is never zero");
        }
        if (original.kind != ChargeKind.REGULAR) {
            throw new IllegalArgumentException("An adjustment corrects a regular charge");
        }
        ChargeRecord charge = new ChargeRecord();
        charge.id = UUID.randomUUID();
        charge.tenantId = original.tenantId;
        charge.plotId = original.plotId;
        charge.chargeTypeId = original.chargeTypeId;
        charge.billingMonthId = billingMonthId;
        charge.kind = ChargeKind.ADJUSTMENT;
        charge.source = ChargeSource.RECALCULATION;
        charge.amount = amount.value();
        charge.serviceFrom = original.serviceFrom;
        charge.serviceTo = original.serviceTo;
        charge.accruedOn = original.accruedOn;
        charge.calculation = calculation;
        charge.sourceKey = adjustmentKey(recalculationId, original.id);
        charge.tariffId = original.tariffId;
        charge.meterId = original.meterId;
        charge.runId = original.runId;
        charge.adjustsChargeId = original.id;
        charge.recalculationId = recalculationId;
        charge.reason = reason;
        charge.createdBy = createdBy;
        charge.createdAt = now;
        return charge;
    }

    /** The source key of the adjustment of {@code originalId} by {@code recalculationId}. */
    static String adjustmentKey(UUID recalculationId, UUID originalId) {
        return "ADJUSTMENT:" + recalculationId + ":" + originalId;
    }

    private static ChargeRecord reversal(ChargeRecord original, UUID billingMonthId, ChargeSource source,
            @Nullable UUID recalculationId, String reason, Map<String, Object> calculation, @Nullable UUID createdBy,
            Instant now) {
        ChargeRecord charge = new ChargeRecord();
        charge.id = UUID.randomUUID();
        charge.tenantId = original.tenantId;
        charge.plotId = original.plotId;
        charge.chargeTypeId = original.chargeTypeId;
        charge.billingMonthId = billingMonthId;
        charge.kind = ChargeKind.REVERSAL;
        charge.source = source;
        charge.amount = original.getAmount().negate().value();
        charge.serviceFrom = original.serviceFrom;
        charge.serviceTo = original.serviceTo;
        charge.accruedOn = original.accruedOn;
        charge.calculation = calculation;
        charge.meterId = original.meterId;
        charge.runId = original.runId;
        charge.reversesChargeId = original.id;
        charge.recalculationId = recalculationId;
        charge.reason = reason;
        charge.createdBy = createdBy;
        charge.createdAt = now;
        return charge;
    }

    UUID getId() {
        return id;
    }

    UUID getPlotId() {
        return plotId;
    }

    UUID getChargeTypeId() {
        return chargeTypeId;
    }

    UUID getBillingMonthId() {
        return billingMonthId;
    }

    ChargeKind getKind() {
        return kind;
    }

    ChargeSource getSource() {
        return source;
    }

    Money getAmount() {
        return Money.of(amount);
    }

    LocalDate getServiceFrom() {
        return serviceFrom;
    }

    LocalDate getServiceTo() {
        return serviceTo;
    }

    LocalDate getAccruedOn() {
        return accruedOn;
    }

    @Nullable Map<String, Object> getCalculation() {
        return calculation;
    }

    @Nullable String getSourceKey() {
        return sourceKey;
    }

    @Nullable UUID getTariffId() {
        return tariffId;
    }

    @Nullable UUID getMeterId() {
        return meterId;
    }

    /**
     * The run (a losses run or a common consumer run) that booked the charge, or whose charge a reversal cancels; null
     * for other charges.
     */
    @Nullable UUID getRunId() {
        return runId;
    }

    @Nullable UUID getReversesChargeId() {
        return reversesChargeId;
    }

    /** The charge an {@link ChargeKind#ADJUSTMENT} corrects; null for every other charge. */
    @Nullable UUID getAdjustsChargeId() {
        return adjustsChargeId;
    }

    /**
     * The recalculation that booked the charge (an adjustment, a reversal of a recalculation, or a regular charge it
     * booked); null otherwise.
     */
    @Nullable UUID getRecalculationId() {
        return recalculationId;
    }

    @Nullable String getReason() {
        return reason;
    }

    @Nullable UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
