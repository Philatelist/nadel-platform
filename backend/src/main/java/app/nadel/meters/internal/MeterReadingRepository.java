package app.nadel.meters.internal;

import app.nadel.meters.internal.MeterReadingRecord.Status;
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
}
