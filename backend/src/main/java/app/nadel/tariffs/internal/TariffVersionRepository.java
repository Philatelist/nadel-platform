package app.nadel.tariffs.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface TariffVersionRepository extends JpaRepository<TariffVersionRecord, UUID> {

    /** The versions of the tariffs, oldest first by start date within a tariff. */
    List<TariffVersionRecord> findByTariffIdInOrderByTariffIdAscValidFromAsc(Collection<UUID> tariffIds);

    List<TariffVersionRecord> findByTariffIdOrderByValidFromAsc(UUID tariffId);

    long countByTariffId(UUID tariffId);

    /**
     * Every version of the tariffs as pairs of tariff id and version id, the softly deleted ones included (native, so
     * the {@code deleted_at is null} restriction of the entity does not apply): the use check of a whole tariff.
     */
    @Query(value = "select tariff_id, id from tariff_version where tariff_id in (:tariffIds)", nativeQuery = true)
    List<Object[]> findAllVersionIdsOf(@Param("tariffIds") Collection<UUID> tariffIds);

    /**
     * Deletes every version of the tariff, the softly deleted ones included (feature 020: deleting a tariff no charge
     * was made from). Native, past the entity's restriction; a version a charge part points at stops it (SQLSTATE
     * 23503).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "delete from tariff_version where tariff_id = :tariffId", nativeQuery = true)
    int deleteAllOfTariff(@Param("tariffId") UUID tariffId);
}
