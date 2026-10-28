package app.nadel.meters.internal;

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
interface WaterConnectionRepository extends JpaRepository<WaterConnectionRecord, UUID> {

    /** Every connection the plots have had, in no particular order (the Excel import's plan). */
    List<WaterConnectionRecord> findByPlotIdIn(Collection<UUID> plotIds);

    /** Every connection the plot has had, newest first. */
    List<WaterConnectionRecord> findByPlotIdOrderByValidFromDesc(UUID plotId);

    /**
     * The connections of the plots locked {@code FOR UPDATE} (a disconnection ends one; two at once run one after
     * another), in id order so that two transactions never wait for each other in a circle.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from WaterConnectionRecord c where c.plotId in :plotIds order by c.id")
    List<WaterConnectionRecord> findByPlotIdsForUpdate(@Param("plotIds") Collection<UUID> plotIds);

    /** The connections of the plots that have a connected day from {@code from} to {@code to}, both included. */
    @Query("select c from WaterConnectionRecord c where c.plotId in :plotIds and c.validFrom <= :to "
            + "and (c.validTo is null or c.validTo >= :from) order by c.plotId, c.validFrom")
    List<WaterConnectionRecord> findOverlapping(@Param("plotIds") Collection<UUID> plotIds,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Every connection of the cooperative that has a connected day from {@code from} to {@code to}, both included (the
     * water report, feature 013), by plot and start.
     */
    @Query("select c from WaterConnectionRecord c where c.validFrom <= :to "
            + "and (c.validTo is null or c.validTo >= :from) order by c.plotId, c.validFrom")
    List<WaterConnectionRecord> findAllOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The connections to the meter that have a connected day from {@code from} to {@code to}, both included. */
    @Query("select c from WaterConnectionRecord c where c.sourceMeterId = :source and c.validFrom <= :to "
            + "and (c.validTo is null or c.validTo >= :from) order by c.plotId, c.validFrom")
    List<WaterConnectionRecord> findOverlappingOfMeter(@Param("source") UUID sourceMeterId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * The connections to the cooperative's water supply that have a connected day from {@code from} to {@code to},
     * both included.
     */
    @Query("select c from WaterConnectionRecord c where c.sourceMeterId is null and c.validFrom <= :to "
            + "and (c.validTo is null or c.validTo >= :from) order by c.plotId, c.validFrom")
    List<WaterConnectionRecord> findOverlappingOfSupply(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The connections that have not ended before the day: lasting, or ending on it or later. */
    @Query("select c from WaterConnectionRecord c where c.validTo is null or c.validTo >= :day")
    List<WaterConnectionRecord> findNotEndedBefore(@Param("day") LocalDate day);

    /** The plots among {@code plotIds} that are connected on the day. */
    @Query("select distinct c.plotId from WaterConnectionRecord c where c.plotId in :plotIds and c.validFrom <= :day "
            + "and (c.validTo is null or c.validTo >= :day)")
    List<UUID> plotsConnectedOn(@Param("plotIds") Collection<UUID> plotIds, @Param("day") LocalDate day);

    /** Whether any plot is or was connected to the meter. */
    boolean existsBySourceMeterId(UUID sourceMeterId);

    /** The plots among {@code plotIds} that have a lasting connection (no end date): the plot guard. */
    @Query("select distinct c.plotId from WaterConnectionRecord c where c.plotId in :plotIds and c.validTo is null")
    List<UUID> plotIdsWithLastingConnection(@Param("plotIds") Collection<UUID> plotIds);

    /** The lasting connection of a plot. */
    Optional<WaterConnectionRecord> findByPlotIdAndValidToIsNull(UUID plotId);
}
