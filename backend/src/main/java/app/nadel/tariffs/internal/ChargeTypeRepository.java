package app.nadel.tariffs.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface ChargeTypeRepository extends JpaRepository<ChargeTypeRecord, UUID> {

    /** The non-archived charge types in list order. */
    @Query("select c from ChargeTypeRecord c where c.archivedAt is null order by c.position, c.id")
    List<ChargeTypeRecord> findActiveInOrder();

    /**
     * The non-archived charge types with an exclusive row lock ({@code FOR UPDATE}) in list order, until the end of
     * the transaction: a reorder, an archive and a change of a type run one after another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChargeTypeRecord c where c.archivedAt is null order by c.position, c.id")
    List<ChargeTypeRecord> lockActiveInOrder();

    /** The charge type, archived or not, with an exclusive row lock ({@code FOR UPDATE}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChargeTypeRecord c where c.id = :id")
    Optional<ChargeTypeRecord> findByIdForUpdate(@Param("id") UUID id);

    /** The highest position of any charge type, archived ones included; empty while there is none. */
    @Query("select max(c.position) from ChargeTypeRecord c")
    Optional<Integer> findMaxPosition();
}
