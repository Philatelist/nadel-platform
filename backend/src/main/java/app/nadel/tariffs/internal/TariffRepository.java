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
interface TariffRepository extends JpaRepository<TariffRecord, UUID> {

    /** The tariffs of the non-archived charge types in list order: charge type position, then tariff name. */
    @Query("""
            select t from TariffRecord t, ChargeTypeRecord c
            where c.id = t.chargeTypeId and c.archivedAt is null
            order by c.position, c.id, lower(t.name), t.id
            """)
    List<TariffRecord> findOfActiveChargeTypes();

    /**
     * The tariff with an exclusive row lock ({@code FOR UPDATE}) until the end of the transaction: two changes of one
     * tariff's scope run one after another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TariffRecord t where t.id = :id")
    Optional<TariffRecord> findByIdForUpdate(@Param("id") UUID id);
}
