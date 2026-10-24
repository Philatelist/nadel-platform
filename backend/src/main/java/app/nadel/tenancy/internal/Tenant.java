package app.nadel.tenancy.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant")
class Tenant {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Changed only by the platform operator; read-only for the application. */
    @Column(name = "edrpou", insertable = false, updatable = false)
    private String edrpou;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {
    }

    UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getEdrpou() {
        return edrpou;
    }
}
