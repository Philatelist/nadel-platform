package app.nadel.ledger.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface CommonConsumerRunRepository extends JpaRepository<CommonConsumerRunRecord, UUID> {

    /** Every run, newest first. */
    @Query("select r from CommonConsumerRunRecord r order by r.createdAt desc, r.id")
    List<CommonConsumerRunRecord> findAllNewestFirst();

    /** The run with an exclusive row lock ({@code FOR UPDATE}): two reversals of a run run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CommonConsumerRunRecord r where r.id = :id")
    Optional<CommonConsumerRunRecord> findByIdForUpdate(@Param("id") UUID id);
}
