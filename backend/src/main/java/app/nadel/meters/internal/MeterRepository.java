package app.nadel.meters.internal;

import app.nadel.meters.MeterResource;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface MeterRepository extends JpaRepository<MeterRecord, UUID> {

    /**
     * The meter with an exclusive row lock ({@code FOR UPDATE}) until the end of the transaction: every write of a
     * meter or of its readings takes it first, so they run one after another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MeterRecord m where m.id = :id")
    Optional<MeterRecord> findByIdForUpdate(@Param("id") UUID id);

    /** The meters that are not removed. */
    List<MeterRecord> findByRemovedOnIsNull();

    /** Every meter the plot has had, active and removed. */
    List<MeterRecord> findByPlotId(UUID plotId);

    /** Whether the plot has an active meter of the resource (the partial unique index {@code meter_active_plot_uq}). */
    @Query("select count(m) > 0 from MeterRecord m where m.plotId = :plotId and m.resource = :resource "
            + "and m.removedOn is null")
    boolean existsActive(@Param("plotId") UUID plotId, @Param("resource") MeterResource resource);

    /** The plots among {@code plotIds} that have any meter, active or removed. */
    @Query("select distinct m.plotId from MeterRecord m where m.plotId in :plotIds")
    List<UUID> plotIdsWithMeter(@Param("plotIds") Collection<UUID> plotIds);

    /** The plots among {@code plotIds} that have an active meter. */
    @Query("select distinct m.plotId from MeterRecord m where m.plotId in :plotIds and m.removedOn is null")
    List<UUID> plotIdsWithActiveMeter(@Param("plotIds") Collection<UUID> plotIds);

    /** Whether the meter has a reading of any status (the foreign key of {@code meter_reading} restricts a delete). */
    @Query(value = "select exists (select 1 from meter_reading r where r.meter_id = :meterId)", nativeQuery = true)
    boolean hasReadings(@Param("meterId") UUID meterId);

    /** The meters among {@code meterIds} that have a reading of any status. */
    @Query(value = "select distinct r.meter_id from meter_reading r where r.meter_id in (:meterIds)",
            nativeQuery = true)
    List<UUID> meterIdsWithReadings(@Param("meterIds") Collection<UUID> meterIds);

    /**
     * The latest approved reading of each of the meters as rows {@code [meter_id, taken_on, value]}; a meter without
     * one has no row.
     */
    @Query(value = """
            select distinct on (r.meter_id) r.meter_id, r.taken_on, r.value
            from meter_reading r
            where r.status = 'APPROVED' and r.meter_id in (:meterIds)
            order by r.meter_id, r.taken_on desc
            """, nativeQuery = true)
    List<Object[]> latestApprovedReadings(@Param("meterIds") Collection<UUID> meterIds);
}
