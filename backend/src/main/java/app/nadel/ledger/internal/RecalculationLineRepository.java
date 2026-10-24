package app.nadel.ledger.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface RecalculationLineRepository extends JpaRepository<RecalculationLineRecord, UUID> {

    /** The lines of a recalculation in the order they were booked. */
    List<RecalculationLineRecord> findByRecalculationIdOrderByPositionAsc(UUID recalculationId);
}
