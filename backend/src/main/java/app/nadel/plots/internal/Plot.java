package app.nadel.plots.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.locationtech.jts.geom.Polygon;

@Entity
@Table(name = "plot")
class Plot {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String number;

    private String street;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlotKind kind;

    @Column(name = "area_m2", nullable = false)
    private BigDecimal areaM2;

    @Column(name = "cadastral_number")
    private String cadastralNumber;

    @Column(columnDefinition = "geometry(Polygon,4326)")
    private Polygon boundary;

    protected Plot() {
    }

    UUID getId() {
        return id;
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getNumber() {
        return number;
    }

    String getStreet() {
        return street;
    }

    PlotKind getKind() {
        return kind;
    }

    BigDecimal getAreaM2() {
        return areaM2;
    }

    Polygon getBoundary() {
        return boundary;
    }
}
