package app.nadel.tariffs.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface TariffScopePlotRepository extends JpaRepository<TariffScopePlotRecord, ScopePlotKey> {

    /** Every selected-plot and exclusion row of the tariff. */
    List<TariffScopePlotRecord> findByTariffId(UUID tariffId);

    /** The selected-plot and exclusion rows of these tariffs. */
    @Query("select s from TariffScopePlotRecord s where s.tariffId in :tariffIds")
    List<TariffScopePlotRecord> findOf(@Param("tariffIds") Collection<UUID> tariffIds);

    /** The selected-plot or exclusion rows that name the plot, one per tariff. */
    List<TariffScopePlotRecord> findByPlotId(UUID plotId);
}
