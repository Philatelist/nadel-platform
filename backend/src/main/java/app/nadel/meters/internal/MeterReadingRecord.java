package app.nadel.meters.internal;

import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.MeterValue;
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
import org.jspecify.annotations.Nullable;

/**
 * One reading of a meter (table {@code meter_reading}, V12): a value on the date it was taken. Date and value never
 * change; a correction is a new row that supersedes this one, so the history keeps both. Only {@link Status#APPROVED}
 * readings count for consumption, and a meter has at most one of them per date (partial unique index
 * {@code meter_reading_approved_uq}): the old row is superseded and flushed before the new one is inserted.
 */
@Entity
@Table(name = "meter_reading")
class MeterReadingRecord {

    /** The status of a reading (the {@code ReadingStatus} of the API contract). */
    enum Status {
        APPROVED, PENDING, REJECTED, SUPERSEDED
    }

    /** Who brought a reading in (the {@code ReadingSource} of the API contract). */
    enum Source {
        ENTERED, IMPORTED, MEMBER
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "meter_id", nullable = false, updatable = false)
    private UUID meterId;

    @Column(name = "taken_on", nullable = false, updatable = false)
    private LocalDate takenOn;

    @Column(nullable = false, updatable = false)
    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "entered_by_member_id", updatable = false)
    private UUID enteredByMemberId;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "superseded_by_id")
    private UUID supersededById;

    @Column(name = "decided_by_member_id")
    private UUID decidedByMemberId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MeterReadingRecord() {
    }

    /**
     * A reading entered by the board or the electrician: it counts at once. The caller has locked the meter and
     * checked the date and the value.
     */
    static MeterReadingRecord entered(UUID tenantId, UUID meterId, LocalDate takenOn, MeterValue value,
            @Nullable UUID enteredByMemberId, Instant now) {
        MeterReadingRecord reading = new MeterReadingRecord();
        reading.id = UUID.randomUUID();
        reading.tenantId = tenantId;
        reading.meterId = meterId;
        reading.takenOn = takenOn;
        reading.value = value.value();
        reading.source = Source.ENTERED;
        reading.status = Status.APPROVED;
        reading.enteredByMemberId = enteredByMemberId;
        reading.createdAt = now;
        reading.updatedAt = now;
        return reading;
    }

    /**
     * Takes the reading out of the count. The successor is linked by {@link #supersededBy} once it exists: the old row
     * must leave {@code APPROVED} (and be flushed) before the new approved row of the same date is inserted.
     */
    void supersede(Instant now) {
        this.status = Status.SUPERSEDED;
        this.updatedAt = now;
    }

    /** Links the reading that took the place of this superseded one. */
    void supersededBy(UUID successorId) {
        this.supersededById = successorId;
    }

    UUID getId() {
        return id;
    }

    UUID getMeterId() {
        return meterId;
    }

    LocalDate getTakenOn() {
        return takenOn;
    }

    MeterValue getValue() {
        return MeterValue.of(value);
    }

    Source getSource() {
        return source;
    }

    Status getStatus() {
        return status;
    }

    @Nullable String getRejectReason() {
        return rejectReason;
    }

    @Nullable UUID getSupersededById() {
        return supersededById;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    boolean isApproved() {
        return status == Status.APPROVED;
    }

    /** The reading as the calculation sees it. */
    Reading toReading() {
        return new Reading(takenOn, getValue());
    }
}
