package app.nadel.ledger.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface PaymentRepository extends JpaRepository<PaymentRecord, UUID> {

    /** Every payment and payment reversal of the plots, in no particular order. */
    List<PaymentRecord> findByPlotIdIn(Collection<UUID> plotIds);

    /** The parts of one payment group (and their reversals), in no particular order. */
    List<PaymentRecord> findByGroupId(UUID groupId);

    /** Whether the payment has been reversed. */
    boolean existsByReversesPaymentId(UUID paymentId);
}
