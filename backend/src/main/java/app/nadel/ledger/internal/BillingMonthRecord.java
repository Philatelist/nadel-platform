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
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An accounting period (table {@code billing_month}, V13): {@code month} is its first day. An {@link Status#OPEN} month
 * accepts new charges, a {@link Status#CLOSED} one never changes again (the trigger {@code billing_month_guard} of V14
 * refuses any change of a closed month and every delete). Rows are created on demand with a native insert and closed
 * with a native update ({@link BillingMonthRepository}), never deleted; this entity only reads them. A managed instance
 * may be stale after a closing in another transaction: decisions that depend on the status read it as a scalar under a
 * row lock ({@link BillingMonthRepository#lockStatusForShare}, {@link BillingMonthRepository#lockStatusForUpdate}).
 */
@Entity
@Table(name = "billing_month")
class BillingMonthRecord {

    /** The status of a month. */
    enum Status {
        OPEN, CLOSED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private LocalDate month;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "run_at")
    private Instant runAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    protected BillingMonthRecord() {
    }

    UUID getId() {
        return id;
    }

    YearMonth yearMonth() {
        return YearMonth.from(month);
    }

    boolean isClosed() {
        return status == Status.CLOSED;
    }

    /** Whether the month run of recurring tariffs was booked (slice 3 sets {@code run_at}). */
    boolean isRunDone() {
        return runAt != null;
    }

    @Nullable Instant getClosedAt() {
        return closedAt;
    }

    @Nullable UUID getClosedBy() {
        return closedBy;
    }
}
