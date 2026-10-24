package app.nadel.ledger.internal;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface RecalculationRepository extends JpaRepository<RecalculationRecord, UUID> {

    /** A page of the recalculations, newest first; the id breaks ties so paging is stable. */
    Page<RecalculationRecord> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
