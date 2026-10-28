package app.nadel.ledger.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface PaymentRepository extends JpaRepository<PaymentRecord, UUID> {

    /** Every payment and payment reversal of the plots, in no particular order. */
    List<PaymentRecord> findByPlotIdIn(Collection<UUID> plotIds);

    /** The parts of one payment group (and their reversals), in no particular order. */
    List<PaymentRecord> findByGroupId(UUID groupId);

    /** Whether the payment has been reversed. */
    boolean existsByReversesPaymentId(UUID paymentId);

    /** The reversal of the payment, if it was reversed. */
    Optional<PaymentRecord> findByReversesPaymentId(UUID paymentId);

    /** The reversals of any of the payments. */
    List<PaymentRecord> findByReversesPaymentIdIn(Collection<UUID> paymentIds);

    /** The number of payments of the kind in each of the groups, as {@code [groupId, count]} rows. */
    @Query("select p.groupId, count(p) from PaymentRecord p where p.kind = :kind and p.groupId in :groupIds "
            + "group by p.groupId")
    List<Object[]> countByGroup(@Param("kind") PaymentKind kind, @Param("groupIds") Collection<UUID> groupIds);

    /** The plots among {@code plotIds} that have at least one payment (or payment reversal). */
    @Query("select distinct p.plotId from PaymentRecord p where p.plotId in :plotIds")
    List<UUID> plotIdsWithPayments(@Param("plotIds") Collection<UUID> plotIds);
}
