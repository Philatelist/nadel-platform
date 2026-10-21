package app.nadel.meters.internal;

import java.time.LocalDate;
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

    /** Whether any meter was ever under the parent (its periods refer to it: {@code meter_parent_parent_fk}). */
    boolean existsByParentMeterId(UUID parentMeterId);

    /**
     * Whether a meter is under the parent after {@code day}: a period that lasts or ends after that day, of a meter that
     * still works after it (a removed child's open period ends with the child).
     */
    @Query("select count(p) > 0 from MeterParentRecord p, MeterRecord m where p.parentMeterId = :parentId "
            + "and m.id = p.meterId and (p.validTo is null or p.validTo > :day) "
            + "and (m.removedOn is null or m.removedOn > :day)")
    boolean hasChildrenAfter(@Param("parentId") UUID parentId, @Param("day") LocalDate day);
}
