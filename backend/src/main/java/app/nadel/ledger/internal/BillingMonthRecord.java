package app.nadel.ledger.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * An accounting period (table {@code billing_month}, V13): {@code month} is its first day. An {@link Status#OPEN} month
 * accepts new charges, a {@link Status#CLOSED} one never changes again (closing arrives with slice 2). Rows are created
 * on demand by {@link BillingMonths} with a native insert and never deleted; this entity only reads them.
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
}
