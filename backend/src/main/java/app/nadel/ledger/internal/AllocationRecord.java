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
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One row of table {@code allocation} (V27, feature 015 §2.2): a part of a payment placed on one charge of the same
 * plot (amount above zero, with its {@link AllocationOrigin}), or a <b>release</b> that takes (part of) one such row
 * back (amount below zero, pointing at it, with its {@link ReleaseCause}). Append-only like {@link ChargeRecord}: the
 * entity is {@link Immutable} and only ever inserted; an allocation is never changed, only released.
 * <p>
 * {@code created_by} null means the system (an automatic allocation of prepayment, a release of a booking path run by
 * the system).
 */
@Entity
@Immutable
@Table(name = "allocation")
class AllocationRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private UUID chargeId;

    @Column(name = "plot_id", nullable = false, updatable = false)
    private UUID plotId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "releases_allocation_id", updatable = false)
    private UUID releasesAllocationId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private AllocationOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_cause", updatable = false)
    private ReleaseCause releaseCause;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AllocationRecord() {
    }

    /**
     * An allocation of {@code amount} of the payment to the charge, both of {@code plotId}.
     *
     * @throws IllegalArgumentException when the amount is not above zero
     */
    static AllocationRecord allocate(UUID tenantId, UUID plotId, UUID paymentId, UUID chargeId, Money amount,
            AllocationOrigin origin, @Nullable UUID createdBy, Instant now) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("An allocation is above zero");
        }
        AllocationRecord allocation = new AllocationRecord();
        allocation.id = UUID.randomUUID();
        allocation.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        allocation.plotId = Objects.requireNonNull(plotId, "plotId");
        allocation.paymentId = Objects.requireNonNull(paymentId, "paymentId");
        allocation.chargeId = Objects.requireNonNull(chargeId, "chargeId");
        allocation.amount = amount.value();
        allocation.origin = Objects.requireNonNull(origin, "origin");
        allocation.createdBy = createdBy;
        allocation.createdAt = Objects.requireNonNull(now, "now");
        return allocation;
    }

    /**
     * The release {@link ReleasePlanner} planned: the negated amount, the payment, charge and plot of the allocation
     * it takes back, pointing at it, with the cause.
     */
    static AllocationRecord releaseOf(UUID tenantId, ReleasePlanner.Release release, @Nullable UUID createdBy,
            Instant now) {
        AllocationRecord allocation = new AllocationRecord();
        allocation.id = UUID.randomUUID();
        allocation.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        allocation.plotId = release.plotId();
        allocation.paymentId = release.paymentId();
        allocation.chargeId = release.chargeId();
        allocation.amount = release.amount().negate().value();
        allocation.releasesAllocationId = release.allocationId();
        allocation.releaseCause = release.cause();
        allocation.createdBy = createdBy;
        allocation.createdAt = Objects.requireNonNull(now, "now");
        return allocation;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    UUID getPaymentId() {
        return paymentId;
    }

    UUID getChargeId() {
        return chargeId;
    }

    UUID getPlotId() {
        return plotId;
    }

    /** Above zero for an allocation, below zero for a release. */
    Money getAmount() {
        return Money.of(amount);
    }

    /** The allocation a release takes back; null for an allocation. */
    @Nullable UUID getReleasesAllocationId() {
        return releasesAllocationId;
    }

    /** Who placed an allocation; null for a release. */
    @Nullable AllocationOrigin getOrigin() {
        return origin;
    }

    /** Why a release takes money back; null for an allocation. */
    @Nullable ReleaseCause getReleaseCause() {
        return releaseCause;
    }

    /** The member whose action wrote the row; null for the system. */
    @Nullable UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
