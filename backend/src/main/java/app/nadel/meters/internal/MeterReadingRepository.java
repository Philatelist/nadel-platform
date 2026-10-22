package app.nadel.meters.internal;

import app.nadel.meters.internal.MeterReadingRecord.Status;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface MeterReadingRepository extends JpaRepository<MeterReadingRecord, UUID> {

    /** Every reading of the meter, of any status. */
    List<MeterReadingRecord> findByMeterId(UUID meterId);

    /** The readings of the meter with the status, oldest date first. */
    List<MeterReadingRecord> findByMeterIdAndStatusOrderByTakenOn(UUID meterId, Status status);

    /** Whether the meter has a reading of any status (the foreign key of {@code meter_reading} restricts a delete). */
    boolean existsByMeterId(UUID meterId);

    /** Whether the meter has a reading of the status. */
    boolean existsByMeterIdAndStatus(UUID meterId, Status status);

    /** The readings of the status of the meters, oldest date first. */
    List<MeterReadingRecord> findByMeterIdInAndStatusOrderByTakenOn(Collection<UUID> meterIds, Status status);

    /** Every reading of the status in the cooperative, oldest date first, then the oldest submission first. */
    List<MeterReadingRecord> findByStatusOrderByTakenOnAscCreatedAtAscIdAsc(Status status);

    /**
     * The meter of a reading, without loading the reading: a write locks the meter first and only then reads the
     * reading, so it sees the committed state.
     */
    @Query("select r.meterId from MeterReadingRecord r where r.id = :id")
    Optional<UUID> findMeterIdOf(@Param("id") UUID id);

    /** The meters among {@code meterIds} that have a reading of any status. */
    @Query("select distinct r.meterId from MeterReadingRecord r where r.meterId in :meterIds")
    List<UUID> meterIdsWithReadings(@Param("meterIds") Collection<UUID> meterIds);

    /** The latest reading of the status of each of the meters; a meter without one has none. */
    @Query("""
            select r from MeterReadingRecord r
            where r.status = :status and r.meterId in :meterIds
              and r.takenOn = (select max(o.takenOn) from MeterReadingRecord o
                               where o.meterId = r.meterId and o.status = :status)
            """)
    List<MeterReadingRecord> latest(@Param("meterIds") Collection<UUID> meterIds, @Param("status") Status status);

    /**
     * For each of the meters that has one, the date of its latest reading of the status on or before {@code asOf}:
     * rows of {@code [meterId, takenOn]}.
     */
    @Query("""
            select r.meterId, max(r.takenOn) from MeterReadingRecord r
            where r.status = :status and r.meterId in :meterIds and r.takenOn <= :asOf
            group by r.meterId
            """)
    List<Object[]> latestDatesOnOrBefore(@Param("meterIds") Collection<UUID> meterIds, @Param("status") Status status,
            @Param("asOf") LocalDate asOf);
}
