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

    /** Which of these plots have at least one ownership row, current or past. */
    @Query("select distinct o.plotId from Ownership o where o.plotId in :plotIds")
    List<UUID> findPlotIdsWithOwnership(@Param("plotIds") Collection<UUID> plotIds);

    /** Every ownership row, current and past, of several plots in one query (the Excel import). */
    @Query("select o from Ownership o where o.plotId in :plotIds order by o.plotId, o.validFrom, o.id")
    List<Ownership> findAllByPlotIds(@Param("plotIds") Collection<UUID> plotIds);

    /** The current owners of several plots in one query, for a page of the plots list. */
    @Query("select o from Ownership o where o.plotId in :plotIds and o.validTo is null")
    List<Ownership> findCurrentByPlotIds(@Param("plotIds") Collection<UUID> plotIds);

    /** The plots a member owns now. */
    @Query("select o.plotId from Ownership o where o.memberId = :memberId and o.validTo is null")
    List<UUID> findCurrentPlotIdsByMemberId(@Param("memberId") UUID memberId);

    /** The records of a member that have not ended. */
    @Query("select o from Ownership o where o.memberId = :memberId and o.validTo is null")
    List<Ownership> findCurrentByMemberId(@Param("memberId") UUID memberId);

    /** The records of a member that have ended, the most recently ended first. */
    @Query("select o from Ownership o where o.memberId = :memberId and o.validTo is not null "
            + "order by o.validTo desc, o.validFrom desc, o.id")
    List<Ownership> findPastByMemberId(@Param("memberId") UUID memberId);

    /** True when the member currently owns the plot (a former owner does not). */
    @Query("select count(o) > 0 from Ownership o where o.plotId = :plotId and o.memberId = :memberId "
            + "and o.validTo is null")
    boolean existsCurrentByPlotIdAndMemberId(@Param("plotId") UUID plotId, @Param("memberId") UUID memberId);

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
