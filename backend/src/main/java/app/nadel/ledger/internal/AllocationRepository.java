package app.nadel.ledger.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface AllocationRepository extends JpaRepository<AllocationRecord, UUID> {

    /** Every allocation and release of the plots, in no particular order. */
    List<AllocationRecord> findByPlotIdIn(Collection<UUID> plotIds);

    /** Every allocation and release of the payment, in no particular order. */
    List<AllocationRecord> findByPaymentId(UUID paymentId);
}
