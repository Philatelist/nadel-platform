package app.nadel.ledger.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface ChargeRepository extends JpaRepository<ChargeRecord, UUID> {

    /** Every charge of the plot, reversals included, in no particular order. */
    List<ChargeRecord> findByPlotId(UUID plotId);

    /** Whether a reversal of the charge exists (a charge is reversed at most once). */
    boolean existsByReversesChargeId(UUID chargeId);

    /** The plots among {@code plotIds} that have at least one charge of any kind. */
    @Query("select distinct c.plotId from ChargeRecord c where c.plotId in :plotIds")
    List<UUID> plotIdsWithCharges(@Param("plotIds") Collection<UUID> plotIds);
}
