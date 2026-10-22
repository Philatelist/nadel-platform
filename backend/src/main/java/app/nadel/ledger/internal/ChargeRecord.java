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
 * ever inserted ({@code EntityManager.persist}). A mistake is cancelled by a {@link ChargeKind#REVERSAL} row.
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

    @Column(name = "reverses_charge_id", updatable = false)
    private UUID reversesChargeId;

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
     * {@code date}; {@code reason} is the optional reason or document reference (user content), null when none.
     */
    static ChargeRecord opening(UUID tenantId, UUID plotId, UUID chargeTypeId, UUID billingMonthId, Money amount,
            LocalDate date, @Nullable String reason, Map<String, Object> calculation, @Nullable UUID createdBy,
            Instant now) {
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
        ChargeRecord charge = new ChargeRecord();
        charge.id = UUID.randomUUID();
        charge.tenantId = tenantId;
        charge.plotId = plotId;
        charge.chargeTypeId = chargeTypeId;
        charge.billingMonthId = billingMonthId;
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
     * The reversal of {@code original}: the opposite amount, the same plot, charge type, service period and debt age,
     * source {@code MANUAL_REVERSAL}, the reason, pointing at the original.
     */
    static ChargeRecord reversalOf(ChargeRecord original, UUID billingMonthId, String reason,
            Map<String, Object> calculation, @Nullable UUID createdBy, Instant now) {
        ChargeRecord charge = new ChargeRecord();
        charge.id = UUID.randomUUID();
        charge.tenantId = original.tenantId;
        charge.plotId = original.plotId;
        charge.chargeTypeId = original.chargeTypeId;
        charge.billingMonthId = billingMonthId;
        charge.kind = ChargeKind.REVERSAL;
        charge.source = ChargeSource.MANUAL_REVERSAL;
        charge.amount = original.getAmount().negate().value();
        charge.serviceFrom = original.serviceFrom;
        charge.serviceTo = original.serviceTo;
        charge.accruedOn = original.accruedOn;
        charge.calculation = calculation;
        charge.reversesChargeId = original.id;
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

    @Nullable UUID getReversesChargeId() {
        return reversesChargeId;
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
