package app.nadel.plots.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    /**
     * The plot of an ownership row, read as a scalar so that no entity enters the persistence context: a correction
     * locks the plot first and must then read the rows fresh.
     */
    @Query("select o.plotId from Ownership o where o.id = :id")
    Optional<UUID> findPlotIdById(@Param("id") UUID id);

    /** The current owners of several plots in one query, for a page of the plots list. */
    @Query("select o from Ownership o where o.plotId in :plotIds and o.validTo is null")
    List<Ownership> findCurrentByPlotIds(@Param("plotIds") Collection<UUID> plotIds);

    /** True when the member owns at least one plot now (a row without an end date). */
    @Query("select count(o) > 0 from Ownership o where o.memberId = :memberId and o.validTo is null")
    boolean existsCurrentByMemberId(@Param("memberId") UUID memberId);

    /** True when the plot has a row that is still current. */
    @Query("select count(o) > 0 from Ownership o where o.plotId = :plotId and o.validTo is null")
    boolean existsCurrentByPlotId(@Param("plotId") UUID plotId);

    /** True when the plot has any ownership row, current or past. */
    @Query("select count(o) > 0 from Ownership o where o.plotId = :plotId")
    boolean existsByPlotId(@Param("plotId") UUID plotId);
}
