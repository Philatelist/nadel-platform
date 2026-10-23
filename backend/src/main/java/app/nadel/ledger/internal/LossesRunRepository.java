package app.nadel.ledger.internal;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface LossesRunRepository extends JpaRepository<LossesRunRecord, UUID> {

    /** Every run, newest first. */
    @Query("select r from LossesRunRecord r order by r.createdAt desc, r.id")
    List<LossesRunRecord> findAllNewestFirst();

    /**
     * The booked runs whose range ends on a day from {@code from} to {@code to}: the runs a recalculation of those
     * days covers (feature 012). Oldest end first.
     */
    @Query("""
            select r from LossesRunRecord r
            where r.status = app.nadel.ledger.internal.LossesRunRecord.Status.BOOKED
              and r.toDate between :from and :to
            order by r.toDate, r.id
            """)
    List<LossesRunRecord> findBookedEndingBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The run with an exclusive row lock ({@code FOR UPDATE}): two reversals of a run run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LossesRunRecord r where r.id = :id")
    Optional<LossesRunRecord> findByIdForUpdate(@Param("id") UUID id);
}
