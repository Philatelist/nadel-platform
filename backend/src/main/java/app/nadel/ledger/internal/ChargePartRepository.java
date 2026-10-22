package app.nadel.ledger.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface ChargePartRepository extends JpaRepository<ChargePartRecord, UUID> {

    /** Whether a charge that is not reversed has a part priced by the tariff version. */
    @Query("""
            select count(p) > 0 from ChargePartRecord p, ChargeRecord c
            where c.id = p.chargeId and p.tariffVersionId = :versionId
              and not exists (select r.id from ChargeRecord r where r.reversesChargeId = c.id)
            """)
    boolean usedByUnreversedCharge(@Param("versionId") UUID versionId);
}
