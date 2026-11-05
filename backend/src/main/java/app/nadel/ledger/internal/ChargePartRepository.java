package app.nadel.ledger.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface ChargePartRepository extends JpaRepository<ChargePartRecord, UUID> {

    /** The parts of the charges, in no particular order. */
    List<ChargePartRecord> findByChargeIdIn(Collection<UUID> chargeIds);

    /** Whether a charge that is not reversed has a part priced by the tariff version. */
    @Query("""
            select count(p) > 0 from ChargePartRecord p, ChargeRecord c
            where c.id = p.chargeId and p.tariffVersionId = :versionId
              and not exists (select r.id from ChargeRecord r where r.reversesChargeId = c.id)
            """)
    boolean usedByUnreversedCharge(@Param("versionId") UUID versionId);

    /** The versions among {@code versionIds} that price a part of any charge (a reversed one included). */
    @Query("select distinct p.tariffVersionId from ChargePartRecord p where p.tariffVersionId in :versionIds")
    List<UUID> versionIdsWithParts(@Param("versionIds") Collection<UUID> versionIds);

    /**
     * The last service day of the regular charges with a part priced by one of the versions that no reversal cancels;
     * null if none.
     */
    @Query("""
            select max(c.serviceTo) from ChargeRecord c
            where c.kind = :kind
              and exists (select p.id from ChargePartRecord p
                          where p.chargeId = c.id and p.tariffVersionId in :versionIds)
              and not exists (select r.id from ChargeRecord r where r.reversesChargeId = c.id)
            """)
    @Nullable LocalDate lastUnreversedServiceDayOfVersions(@Param("versionIds") Collection<UUID> versionIds,
            @Param("kind") ChargeKind kind);
}
