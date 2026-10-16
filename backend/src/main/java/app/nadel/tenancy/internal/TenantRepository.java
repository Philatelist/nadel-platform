package app.nadel.tenancy.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
