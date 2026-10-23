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
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

/**
 * One common consumer run (table {@code common_consumer_run}, V18): its range, the billing month its charges are booked
 * in, their total and who booked it. It never changes except by its reversal ({@link #reverse}), which the trigger
 * {@code common_consumer_run_guard} enforces: the status goes from {@code BOOKED} to {@code REVERSED} with the time, the
 * person and the reason, nothing else. The reason is user content.
 */
@Entity
@DynamicUpdate
@Table(name = "common_consumer_run")
class CommonConsumerRunRecord {

    /** {@code BOOKED} its charges stand; {@code REVERSED} they were all reversed. */
    enum Status {
        BOOKED, REVERSED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "from_date", nullable = false, updatable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false, updatable = false)
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "billing_month_id", nullable = false, updatable = false)
    private UUID billingMonthId;

    @Column(nullable = false, updatable = false)
    private BigDecimal total;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversed_by")
    private UUID reversedBy;

    @Column(name = "reverse_reason")
    private String reverseReason;

    protected CommonConsumerRunRecord() {
    }

    static CommonConsumerRunRecord booked(UUID tenantId, LocalDate from, LocalDate to, UUID billingMonthId, Money total,
            @Nullable UUID createdBy, Instant now) {
        CommonConsumerRunRecord run = new CommonConsumerRunRecord();
        run.id = UUID.randomUUID();
        run.tenantId = tenantId;
        run.fromDate = from;
        run.toDate = to;
        run.status = Status.BOOKED;
        run.billingMonthId = billingMonthId;
        run.total = total.value();
        run.createdBy = createdBy;
        run.createdAt = now;
        return run;
    }

    /** Marks the booked run reversed. */
    void reverse(@Nullable UUID by, String reason, Instant now) {
        if (status != Status.BOOKED) {
            throw new IllegalStateException("Only a booked run is reversed");
        }
        status = Status.REVERSED;
        reversedAt = now;
        reversedBy = by;
        reverseReason = reason;
    }

    UUID getId() {
        return id;
    }

    LocalDate getFromDate() {
        return fromDate;
    }

    LocalDate getToDate() {
        return toDate;
    }

    Status getStatus() {
        return status;
    }

    UUID getBillingMonthId() {
        return billingMonthId;
    }

    Money getTotal() {
        return Money.of(total);
    }

    @Nullable UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    @Nullable Instant getReversedAt() {
        return reversedAt;
    }

    @Nullable UUID getReversedBy() {
        return reversedBy;
    }

    @Nullable String getReverseReason() {
        return reverseReason;
    }

    /** The range as an audit label and a code: {@code from/to} in ISO dates. */
    String label() {
        return fromDate + "/" + toDate;
    }
}
