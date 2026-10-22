package app.nadel.ledger.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * The tariff version behind (a part of) a charge (table {@code charge_part}, V13): the days it counted for, the exact
 * quantity {@code unitsNum / unitsDen} (sotkas, one plot, later kWh) and the rate. Append-only like {@code charge}, so
 * it is {@link Immutable} and only ever inserted. {@code TariffVersionUsage} answers from these rows.
 */
@Entity
@Immutable
@Table(name = "charge_part")
class ChargePartRecord {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private UUID chargeId;

    @Column(name = "tariff_version_id", nullable = false, updatable = false)
    private UUID tariffVersionId;

    @Column(nullable = false, updatable = false)
    private int days;

    @Column(name = "units_num", nullable = false, updatable = false)
    private BigDecimal unitsNum;

    @Column(name = "units_den", nullable = false, updatable = false)
    private BigDecimal unitsDen;

    @Column(nullable = false, updatable = false)
    private BigDecimal rate;

    protected ChargePartRecord() {
    }

    static ChargePartRecord of(UUID tenantId, UUID chargeId, UUID tariffVersionId, long days, BigDecimal units,
            BigDecimal rate) {
        return of(tenantId, chargeId, tariffVersionId, days, units, BigDecimal.ONE, rate);
    }

    /** A part whose quantity is the exact fraction {@code unitsNum / unitsDen} (kWh or m3 of a meter interval). */
    static ChargePartRecord of(UUID tenantId, UUID chargeId, UUID tariffVersionId, long days, BigDecimal unitsNum,
            BigDecimal unitsDen, BigDecimal rate) {
        ChargePartRecord part = new ChargePartRecord();
        part.id = UUID.randomUUID();
        part.tenantId = tenantId;
        part.chargeId = chargeId;
        part.tariffVersionId = tariffVersionId;
        part.days = Math.toIntExact(days);
        part.unitsNum = unitsNum;
        part.unitsDen = unitsDen;
        part.rate = rate;
        return part;
    }

    UUID getChargeId() {
        return chargeId;
    }

    UUID getTariffVersionId() {
        return tariffVersionId;
    }
}
