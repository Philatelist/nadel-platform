package app.nadel.ledger.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface CommonConsumerRunLineRepository extends JpaRepository<CommonConsumerRunLineRecord, UUID> {

    /** The lines of the runs, in no particular order. */
    List<CommonConsumerRunLineRecord> findByRunIdIn(Collection<UUID> runIds);

    /**
     * The charged lines of booked runs among {@code consumerMeterIds} whose range meets the days {@code from} to
     * {@code to}: the days a new run may not charge them again (the exclusion constraint
     * {@code common_consumer_run_line_no_overlap_ex} is the backstop). Oldest range first.
     */
    @Query("""
            select l from CommonConsumerRunLineRecord l
            where l.consumerMeterId in :consumers and l.runStatus = 'BOOKED'
              and l.outcome = app.nadel.ledger.internal.CommonConsumerPlanning.Outcome.CHARGED
              and l.fromDate <= :to and l.toDate >= :from
            order by l.fromDate, l.id
            """)
    List<CommonConsumerRunLineRecord> chargedOverlapping(@Param("consumers") Collection<UUID> consumerMeterIds,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Marks the lines of the run reversed with the run; the trigger lets nothing else change. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update common_consumer_run_line set run_status = 'REVERSED' "
            + "where run_id = :runId and run_status = 'BOOKED'", nativeQuery = true)
    int markReversed(@Param("runId") UUID runId);
}
