package app.nadel.tariffs.internal;

import app.nadel.money.Rate;
import app.nadel.tariffs.Basis;
import app.nadel.tariffs.Frequency;
import app.nadel.tariffs.VersionTerms;
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
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One rate of a tariff from a date. Append-only: the application role has no {@code UPDATE} or {@code DELETE} on the
 * table, and the entity is immutable. A change of rate is a new version.
 */
@Entity
@Immutable
@Table(name = "tariff_version")
class TariffVersionRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "tariff_id", nullable = false, updatable = false)
    private UUID tariffId;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Basis basis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Frequency frequency;

    @Column(nullable = false, updatable = false, precision = 14, scale = 4)
    private BigDecimal rate;

    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    @Column(updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TariffVersionRecord() {
    }

    static TariffVersionRecord create(UUID tenantId, UUID tariffId, VersionTerms terms, @Nullable String note,
            Instant now) {
        TariffVersionRecord version = new TariffVersionRecord();
        version.id = UUID.randomUUID();
        version.tenantId = tenantId;
        version.tariffId = tariffId;
        version.validFrom = terms.validFrom();
        version.basis = terms.basis();
        version.frequency = terms.frequency();
        version.rate = terms.rate().value();
        version.dueDate = terms.dueDate();
        version.note = note;
        version.createdAt = now;
        return version;
    }

    UUID getId() {
        return id;
    }

    UUID getTariffId() {
        return tariffId;
    }

    LocalDate getValidFrom() {
        return validFrom;
    }

    Basis getBasis() {
        return basis;
    }

    Frequency getFrequency() {
        return frequency;
    }

    /** The stored rate, which always has four decimals (the column is {@code numeric(14,4)}). */
    Rate getRate() {
        return Rate.of(rate);
    }

    @Nullable LocalDate getDueDate() {
        return dueDate;
    }

    @Nullable String getNote() {
        return note;
    }
}
