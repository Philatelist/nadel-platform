package app.nadel.tariffs.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface TariffRepository extends JpaRepository<TariffRecord, UUID> {

    /** The tariffs of the non-archived charge types in list order: charge type position, then tariff name. */
    @Query("""
            select t from TariffRecord t, ChargeTypeRecord c
            where c.id = t.chargeTypeId and c.archivedAt is null
            order by c.position, c.id, lower(t.name), t.id
            """)
    List<TariffRecord> findOfActiveChargeTypes();
}
