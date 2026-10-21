package app.nadel.tariffs.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface TariffVersionRepository extends JpaRepository<TariffVersionRecord, UUID> {

    /** The versions of the tariffs, oldest first by start date within a tariff. */
    List<TariffVersionRecord> findByTariffIdInOrderByTariffIdAscValidFromAsc(Collection<UUID> tariffIds);

    List<TariffVersionRecord> findByTariffIdOrderByValidFromAsc(UUID tariffId);

    long countByTariffId(UUID tariffId);
}
