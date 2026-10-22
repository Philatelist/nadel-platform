package app.nadel.ledger.internal;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface BillingMonthRepository extends JpaRepository<BillingMonthRecord, UUID> {

    /** Every month of the cooperative, oldest first. */
    @Query("select m from BillingMonthRecord m order by m.month")
    List<BillingMonthRecord> findAllInOrder();

    /**
     * The month starting on {@code month} with a shared row lock ({@code FOR SHARE}) until the end of the transaction:
     * a booking into it runs before or after a closing (which takes the month {@code FOR UPDATE}), never during one.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from BillingMonthRecord m where m.month = :month")
    Optional<BillingMonthRecord> findByMonthForShare(@Param("month") LocalDate month);

    /** The month with a shared row lock ({@code FOR SHARE}); see {@link #findByMonthForShare}. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from BillingMonthRecord m where m.id = :id")
    Optional<BillingMonthRecord> findByIdForShare(@Param("id") UUID id);

    /** Opens the month (status {@code OPEN}) unless the cooperative has it already; idempotent under concurrency. */
    @Modifying
    @Query(value = "insert into billing_month (tenant_id, month) values (:tenantId, :month) "
            + "on conflict (tenant_id, month) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") UUID tenantId, @Param("month") LocalDate month);
}
