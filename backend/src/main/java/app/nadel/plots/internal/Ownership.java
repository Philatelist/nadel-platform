package app.nadel.plots.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One owner of a plot with a share and a period: current while {@code validTo} is empty, otherwise the last day of
 * ownership. Records are never deleted (the application role cannot); a change closes the period of the old row.
 */
@Entity
@Table(name = "ownership")
class Ownership {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "plot_id", nullable = false)
    private UUID plotId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "share_numerator", nullable = false)
    private short shareNumerator;

    @Column(name = "share_denominator", nullable = false)
    private short shareDenominator;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    private String basis;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Ownership() {
    }

    /** A new current owner: no end date yet. */
    static Ownership start(UUID tenantId, UUID plotId, UUID memberId, Share share, LocalDate validFrom, String basis,
            Instant now) {
        var ownership = new Ownership();
        ownership.id = UUID.randomUUID();
        ownership.tenantId = tenantId;
        ownership.plotId = plotId;
        ownership.memberId = memberId;
        ownership.shareNumerator = (short) share.numerator();
        ownership.shareDenominator = (short) share.denominator();
        ownership.validFrom = validFrom;
        ownership.basis = basis;
        ownership.createdAt = now;
        ownership.updatedAt = now;
        return ownership;
    }

    /** Ends the period on {@code lastDay} (inclusive). */
    void endOn(LocalDate lastDay, Instant now) {
        validTo = lastDay;
        updatedAt = now;
    }

    /** Replaces the fields a correction may change (everything except the plot and the tenant). */
    void correct(UUID memberId, Share share, LocalDate validFrom, LocalDate validTo, String basis, Instant now) {
        this.memberId = memberId;
        this.shareNumerator = (short) share.numerator();
        this.shareDenominator = (short) share.denominator();
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.basis = basis;
        this.updatedAt = now;
    }

    /** Replaces the share only (the shares of the co-owners of a corrected record). */
    void reshare(Share share, Instant now) {
        this.shareNumerator = (short) share.numerator();
        this.shareDenominator = (short) share.denominator();
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    UUID getPlotId() {
        return plotId;
    }

    UUID getMemberId() {
        return memberId;
    }

    Share getShare() {
        return new Share(shareNumerator, shareDenominator);
    }

    LocalDate getValidFrom() {
        return validFrom;
    }

    LocalDate getValidTo() {
        return validTo;
    }

    String getBasis() {
        return basis;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
