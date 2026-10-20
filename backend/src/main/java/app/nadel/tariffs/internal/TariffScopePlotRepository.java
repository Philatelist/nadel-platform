package app.nadel.tariffs.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface TariffScopePlotRepository extends JpaRepository<TariffScopePlotRecord, ScopePlotKey> {

    /** The selected-plot rows of these tariffs. */
    @Query("select s from TariffScopePlotRecord s where s.kind = 'SELECTED' and s.tariffId in :tariffIds")
    List<TariffScopePlotRecord> findSelectedOf(@Param("tariffIds") Collection<UUID> tariffIds);
}
