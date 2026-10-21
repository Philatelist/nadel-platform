package app.nadel.meters.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface MeterParentRepository extends JpaRepository<MeterParentRecord, UUID> {

    /** The parent periods of the meter. */
    List<MeterParentRecord> findByMeterId(UUID meterId);

    /** The periods in which a meter was under the parent. */
    List<MeterParentRecord> findByParentMeterId(UUID parentMeterId);

    /** The meters that were under the parent at any time, without loading their periods. */
    @Query("select distinct p.meterId from MeterParentRecord p where p.parentMeterId = :parentId")
    List<UUID> childIdsOf(@Param("parentId") UUID parentId);
}
