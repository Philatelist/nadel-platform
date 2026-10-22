package app.nadel.ledger.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One case the app could not charge (table {@code not_charged_item}, V15), with the reason and codes or ids only in
 * {@code params} (for a conflict {@code tariffIds}). Not a ledger row: while it is {@link Status#OPEN} its reason and
 * params follow the latest attempt, and it is closed once ({@link Status#RESOLVED} when charged, {@link Status#OBSOLETE}
 * when nothing is left to charge). At most one open item per {@code itemKey} (unique index
 * {@code not_charged_item_open_key_uq}).
 */
@Entity
@Table(name = "not_charged_item")
class NotChargedItemRecord {

    /** What could not be charged (the {@code NotChargedKind} of the contract). */
    enum Kind {
        TARIFF_MONTH, INTERVAL, LOSSES_NODE, COMMON_CONSUMER
    }

    /** Why (the {@code NotChargedReason} of the contract). */
    enum Reason {
        NO_TARIFF_FOR_ALL_DAYS, CONFLICTING_TARIFFS, OUTSIDE_SCOPE, EXCLUDED, UNKNOWN_NODE_VALUE, UNKNOWN_CONSUMER_VALUE
    }

    /** {@code OPEN} is in the queue; the other two are history. */
    enum Status {
        OPEN, RESOLVED, OBSOLETE
    }

    static final String TARIFF_IDS = "tariffIds";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "item_key", nullable = false, updatable = false)
    private String itemKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind;

    @Column(name = "plot_id", updatable = false)
    private UUID plotId;

    @Column(name = "charge_type_id", updatable = false)
    private UUID chargeTypeId;

    @Column(name = "meter_id", updatable = false)
    private UUID meterId;

    @Column(updatable = false)
    private LocalDate month;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reason reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> params;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotChargedItemRecord() {
    }

    /** The key of the month item of a plot and a charge type: {@code TARIFF_MONTH:{plot}:{chargeType}:{YYYY-MM}}. */
    static String tariffMonthKey(UUID plotId, UUID chargeTypeId, YearMonth month) {
        return "TARIFF_MONTH:" + plotId + ":" + chargeTypeId + ":" + month;
    }

    /** An open item: the tariffs of a charge type for a plot and a month could not be charged. */
    static NotChargedItemRecord tariffMonth(UUID tenantId, UUID plotId, UUID chargeTypeId, YearMonth month,
            Reason reason, List<UUID> tariffIds, Instant now) {
        NotChargedItemRecord item = new NotChargedItemRecord();
        item.id = UUID.randomUUID();
        item.tenantId = tenantId;
        item.itemKey = tariffMonthKey(plotId, chargeTypeId, month);
        item.kind = Kind.TARIFF_MONTH;
        item.plotId = plotId;
        item.chargeTypeId = chargeTypeId;
        item.month = month.atDay(1);
        item.fromDate = month.atDay(1);
        item.toDate = month.atEndOfMonth();
        item.reason = reason;
        item.params = params(tariffIds);
        item.status = Status.OPEN;
        item.createdAt = now;
        item.updatedAt = now;
        return item;
    }

    /** A new attempt failed again: the reason and the tariffs of that attempt. Only an open item changes. */
    void failedAgain(Reason newReason, List<UUID> tariffIds, Instant now) {
        if (status != Status.OPEN) {
            throw new IllegalStateException("A closed item does not change");
        }
        Map<String, Object> newParams = params(tariffIds);
        if (newReason != reason || !newParams.equals(params)) {
            reason = newReason;
            params = newParams;
            updatedAt = now;
        }
    }

    /** Closes the open item: charged ({@link Status#RESOLVED}) or nothing left to charge ({@link Status#OBSOLETE}). */
    void close(Status closing, Instant now) {
        if (status != Status.OPEN || closing == Status.OPEN) {
            throw new IllegalStateException("Only an open item is closed, and only to a closed status");
        }
        status = closing;
        resolvedAt = now;
        updatedAt = now;
    }

    private static Map<String, Object> params(List<UUID> tariffIds) {
        return Map.of(TARIFF_IDS, tariffIds.stream().map(UUID::toString).toList());
    }

    UUID getId() {
        return id;
    }

    String getItemKey() {
        return itemKey;
    }

    Kind getKind() {
        return kind;
    }

    @Nullable UUID getPlotId() {
        return plotId;
    }

    @Nullable UUID getChargeTypeId() {
        return chargeTypeId;
    }

    @Nullable UUID getMeterId() {
        return meterId;
    }

    @Nullable YearMonth getMonth() {
        return month == null ? null : YearMonth.from(month);
    }

    @Nullable LocalDate getFromDate() {
        return fromDate;
    }

    @Nullable LocalDate getToDate() {
        return toDate;
    }

    Reason getReason() {
        return reason;
    }

    /** The tariffs named by the item, in the stored order. */
    List<UUID> tariffIds() {
        Object ids = params == null ? null : params.get(TARIFF_IDS);
        if (!(ids instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(Object::toString).map(UUID::fromString).toList();
    }

    Status getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
