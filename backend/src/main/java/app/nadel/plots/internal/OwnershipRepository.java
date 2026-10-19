package app.nadel.plots.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OwnershipRepository extends JpaRepository<Ownership, UUID> {

    // no tenant_id filter needed in these queries: RLS hides other cooperatives' rows

    /** The owners of a plot who have not ended their ownership. */
    @Query("select o from Ownership o where o.plotId = :plotId and o.validTo is null")
    List<Ownership> findCurrentByPlotId(@Param("plotId") UUID plotId);

    /** The past owners of a plot, newest first. */
    @Query("select o from Ownership o where o.plotId = :plotId and o.validTo is not null "
            + "order by o.validFrom desc, o.validTo desc, o.id")
    List<Ownership> findHistoryByPlotId(@Param("plotId") UUID plotId);

    /** Every ownership row of a plot, current and past, oldest first. */
    @Query("select o from Ownership o where o.plotId = :plotId order by o.validFrom, o.id")
    List<Ownership> findAllByPlotId(@Param("plotId") UUID plotId);

    /** The current owners of several plots in one query, for a page of the plots list. */
    @Query("select o from Ownership o where o.plotId in :plotIds and o.validTo is null")
    List<Ownership> findCurrentByPlotIds(@Param("plotIds") Collection<UUID> plotIds);
}
