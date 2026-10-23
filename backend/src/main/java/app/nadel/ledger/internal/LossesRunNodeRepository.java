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
interface LossesRunNodeRepository extends JpaRepository<LossesRunNodeRecord, UUID> {

    /** The nodes of the runs, in no particular order. */
    List<LossesRunNodeRecord> findByRunIdIn(Collection<UUID> runIds);

    /**
     * The charged nodes of booked runs among {@code nodeMeterIds} whose range meets the days {@code from} to
     * {@code to}: the days a new run may not charge them again (the exclusion constraint
     * {@code losses_run_node_no_overlap_ex} is the backstop). Oldest range first.
     */
    @Query("""
            select n from LossesRunNodeRecord n
            where n.nodeMeterId in :nodes and n.runStatus = 'BOOKED'
              and n.outcome = app.nadel.ledger.internal.LossesCalculator.Outcome.CHARGED
              and n.fromDate <= :to and n.toDate >= :from
            order by n.fromDate, n.id
            """)
    List<LossesRunNodeRecord> chargedOverlapping(@Param("nodes") Collection<UUID> nodeMeterIds,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Marks the nodes of the run reversed with the run; the trigger lets nothing else change. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update losses_run_node set run_status = 'REVERSED' where run_id = :runId and run_status = 'BOOKED'",
            nativeQuery = true)
    int markReversed(@Param("runId") UUID runId);
}
