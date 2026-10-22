package app.nadel.ledger.internal;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface BillingMonthRepository extends JpaRepository<BillingMonthRecord, UUID> {

    /** The month and status of a row, read as scalars (never a stale managed entity). */
    record MonthStatus(UUID id, LocalDate month, BillingMonthRecord.Status status) {
    }

    /** Every month of the cooperative, oldest first. */
    @Query("select m from BillingMonthRecord m order by m.month")
    List<BillingMonthRecord> findAllInOrder();

    /** Every month of the cooperative with its status as scalars, oldest first: the current committed state. */
    @Query("select new app.nadel.ledger.internal.BillingMonthRepository$MonthStatus(m.id, m.month, m.status) "
            + "from BillingMonthRecord m order by m.month")
    List<MonthStatus> statesInOrder();

    /** The month starting on {@code month}, if the cooperative has it. */
    Optional<BillingMonthRecord> findByMonth(LocalDate month);

    /**
     * The status of the month starting on {@code month} with a shared row lock ({@code FOR SHARE}) until the end of
     * the transaction: a booking into it runs before or after a closing (which takes the month {@code FOR UPDATE}),
     * never during one. A scalar, so it is the committed status after the lock was granted. Null when the month does not
     * exist.
     */
    @Query(value = "select status from billing_month where month = :month for share", nativeQuery = true)
    @Nullable String lockStatusForShare(@Param("month") LocalDate month);

    /**
     * The status of the month starting on {@code month} with an exclusive row lock ({@code FOR UPDATE}): a closing
     * waits for the bookings into the month and they wait for it. Null when the month does not exist.
     */
    @Query(value = "select status from billing_month where month = :month for update", nativeQuery = true)
    @Nullable String lockStatusForUpdate(@Param("month") LocalDate month);

    /** The month with a shared row lock ({@code FOR SHARE}); see {@link #lockStatusForShare}. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from BillingMonthRecord m where m.id = :id")
    Optional<BillingMonthRecord> findByIdForShare(@Param("id") UUID id);

    /** Opens the month (status {@code OPEN}) unless the cooperative has it already; idempotent under concurrency. */
    @Modifying
    @Query(value = "insert into billing_month (tenant_id, month) values (:tenantId, :month) "
            + "on conflict (tenant_id, month) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") UUID tenantId, @Param("month") LocalDate month);

    /**
     * Closes the open month starting on {@code month}. The caller holds its {@code FOR UPDATE} lock. Clears the
     * persistence context so no stale instance of the month survives.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update billing_month set status = 'CLOSED', closed_at = :closedAt, closed_by = :closedBy "
            + "where month = :month and status = 'OPEN'", nativeQuery = true)
    int close(@Param("month") LocalDate month, @Param("closedAt") Instant closedAt,
            @Param("closedBy") @Nullable UUID closedBy);
}
