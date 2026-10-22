package app.nadel.meters.internal;

import app.nadel.meters.MeterResource;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
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

    /**
     * The meter with a shared row lock ({@code FOR SHARE}) until the end of the transaction: a move in the tree takes it
     * on the new parent, so the parent is not removed or replaced while a meter is put under it.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from MeterRecord m where m.id = :id")
    Optional<MeterRecord> findByIdForShare(@Param("id") UUID id);

    /**
     * The meters with an exclusive row lock each ({@code FOR UPDATE}), taken in id order so that two transactions that
     * lock overlapping sets never wait for each other in a circle.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MeterRecord m where m.id in :ids order by m.id")
    List<MeterRecord> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

    /**
     * The plot of a meter without loading the meter: a replacement locks the plot {@code FOR SHARE} before it locks the
     * meter, as a registration does. Empty for an unknown meter and for a meter that is not a plot meter.
     */
    @Query("select m.plotId from MeterRecord m where m.id = :id")
    Optional<UUID> findPlotIdOf(@Param("id") UUID id);

    /** The meters that replaced the meter (one at most: only an active meter is replaced). */
    List<MeterRecord> findByReplacesMeterId(UUID replacesMeterId);

    /** The meters that replaced any of the meters, for the replacement links of a list in one query. */
    List<MeterRecord> findByReplacesMeterIdIn(Collection<UUID> replacesMeterIds);

    /** Every meter of the resource, active and removed. */
    List<MeterRecord> findByResource(MeterResource resource);

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

    /** The plots that have an active plot meter of the resource. */
    @Query("select distinct m.plotId from MeterRecord m where m.plotId is not null and m.resource = :resource "
            + "and m.removedOn is null")
    List<UUID> plotIdsWithActiveMeterOf(@Param("resource") MeterResource resource);

    /** Whether the plot had a meter of the resource working on a day from {@code from} to {@code to}. */
    @Query("select count(m) > 0 from MeterRecord m where m.plotId = :plotId and m.resource = :resource "
            + "and m.installedOn <= :to and (m.removedOn is null or m.removedOn >= :from)")
    boolean existsWorkingBetween(@Param("plotId") UUID plotId, @Param("resource") MeterResource resource,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The active plot meters of the given plots, for an import that matches its rows against them. */
    @Query("select m from MeterRecord m where m.plotId in :plotIds and m.removedOn is null")
    List<MeterRecord> findActiveOfPlots(@Param("plotIds") Collection<UUID> plotIds);

    /** The plots among {@code plotIds} that have an active meter. */
    @Query("select distinct m.plotId from MeterRecord m where m.plotId in :plotIds and m.removedOn is null")
    List<UUID> plotIdsWithActiveMeter(@Param("plotIds") Collection<UUID> plotIds);
}
