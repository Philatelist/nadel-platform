package app.nadel.tariffs.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tariff of one charge type, such as "For members". Its rates live in {@link TariffVersionRecord}. A tariff no charge
 * was ever made from may be deleted (feature 020); a used one is archived from an end date ({@link #archive}): then
 * {@code validUntil} is its last day and {@code archivedAt} is set (both or neither, {@code tariff_archive_ck}). It also
 * stops applying when its charge type is archived.
 */
@Entity
@Table(name = "tariff")
class TariffRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "charge_type_id", nullable = false, updatable = false)
    private UUID chargeTypeId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_kind", nullable = false)
    private ScopeKind scopeKind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "valid_until")
    private @Nullable LocalDate validUntil;

    @Column(name = "archived_at")
    private @Nullable Instant archivedAt;

    protected TariffRecord() {
    }

    /** A new tariff; it applies to all plots until its scope is changed. */
    static TariffRecord create(UUID tenantId, UUID chargeTypeId, String name, Instant now) {
        TariffRecord tariff = new TariffRecord();
        tariff.id = UUID.randomUUID();
        tariff.tenantId = tenantId;
        tariff.chargeTypeId = chargeTypeId;
        tariff.name = name;
        tariff.scopeKind = ScopeKind.ALL_PLOTS;
        tariff.createdAt = now;
        tariff.updatedAt = now;
        return tariff;
    }

    /** Switches the kind of scope; the scope rows are replaced by the caller. */
    void changeScope(ScopeKind kind, Instant now) {
        this.scopeKind = kind;
        this.updatedAt = now;
    }

    /** Ends the tariff: {@code lastDay} is the last day it applies (the day before the end date). */
    void archive(LocalDate lastDay, Instant now) {
        this.validUntil = lastDay;
        this.archivedAt = now;
        this.updatedAt = now;
    }

    /** Whether the tariff is archived. */
    boolean isArchived() {
        return archivedAt != null;
    }

    @Nullable LocalDate getValidUntil() {
        return validUntil;
    }

    @Nullable Instant getArchivedAt() {
        return archivedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    UUID getChargeTypeId() {
        return chargeTypeId;
    }

    String getName() {
        return name;
    }

    ScopeKind getScopeKind() {
        return scopeKind;
    }
}
