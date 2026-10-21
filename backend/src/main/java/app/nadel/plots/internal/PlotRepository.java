package app.nadel.plots.internal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PlotRepository extends JpaRepository<Plot, UUID> {

    /** The number of active plots that are not of the given kind (RLS limits it to the cooperative). */
    long countByArchivedAtIsNullAndKindNot(PlotKind kind);

    /**
     * The plot with an exclusive row lock ({@code FOR UPDATE}) until the end of the transaction. Every write that
     * depends on the plot's ownership takes this lock first, so such writes on one plot run one after another.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Plot p where p.id = :id")
    Optional<Plot> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The plot with a shared row lock ({@code FOR SHARE}) until the end of the transaction: a module that writes data
     * depending on the plot (a plot meter) waits for a delete, archive or edit of the plot in progress, and they wait
     * for it.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Plot p where p.id = :id")
    Optional<Plot> findByIdForShare(@Param("id") UUID id);

    /** The active plots whose lower-cased number is among {@code numbers} (already lower-cased); RLS limits them. */
    @Query("select p from Plot p where lower(p.number) in :numbers and p.archivedAt is null")
    List<Plot> findActiveWithLowerNumbers(@Param("numbers") Collection<String> numbers);

    /**
     * The given plots' ids by street (Ukrainian ICU collation, so "Ґ" follows "Г" and case is ignored; no street last),
     * then natural order of the number.
     */
    @Query(value = "select p.id from plot p where p.id in (:ids) "
            + "order by p.street collate \"uk-x-icu\" nulls last, p.number_sort_key, p.number, p.id",
            nativeQuery = true)
    List<UUID> findIdsInStreetOrder(@Param("ids") Collection<UUID> ids);

    /**
     * The given plots with an exclusive row lock ({@code FOR UPDATE}), in id order, for the Excel import: the same
     * lock a change of owner takes first, so the two serialise.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Plot p where p.id in :ids order by p.id")
    List<Plot> lockAllForUpdate(@Param("ids") Collection<UUID> ids);

    /**
     * The given plots with a shared row lock ({@code FOR SHARE}), in id order, for the Excel import of meters: the lock
     * a registration of a meter takes, so a delete, archive or edit of the plot waits for the import.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Plot p where p.id in :ids order by p.id")
    List<Plot> lockAllForShare(@Param("ids") Collection<UUID> ids);

    // no tenant_id filter needed: RLS hides other cooperatives' rows
    @Query("""
            select new app.nadel.plots.internal.PlotRow(p, s.status, s.debt)
            from Plot p left join PlotStatusEntry s on s.plotId = p.id
            where p.archivedAt is null
            order by p.number, p.id
            """)
    List<PlotRow> findActiveWithStatus();

    @Query("""
            select new app.nadel.plots.internal.PlotRow(p, s.status, s.debt)
            from Plot p left join PlotStatusEntry s on s.plotId = p.id
            where p.id = :id
            """)
    Optional<PlotRow> findRowById(@Param("id") UUID id);

    @Query("""
            select new app.nadel.plots.internal.PlotRow(p, s.status, s.debt)
            from Plot p left join PlotStatusEntry s on s.plotId = p.id
            where p.id in :ids
            """)
    List<PlotRow> findRowsByIds(@Param("ids") Collection<UUID> ids);

    /** The given plots' ids in natural order of the number (the order of the plots list). */
    @Query(value = "select p.id from plot p where p.id in (:ids) order by p.number_sort_key, p.number, p.id",
            nativeQuery = true)
    List<UUID> findIdsInNaturalOrder(@Param("ids") Collection<UUID> ids);

    /** The active plot with this number, ignoring case; at most one (unique index {@code plot_tenant_number_uq}). */
    @Query("select p from Plot p where p.tenantId = :tenantId and lower(p.number) = lower(:number) "
            + "and p.archivedAt is null")
    Optional<Plot> findActiveByNumber(@Param("tenantId") UUID tenantId, @Param("number") String number);

    /** The active plots whose lower-cased number is among {@code numbers} (already lower-cased by the caller). */
    @Query("select p from Plot p where p.tenantId = :tenantId and lower(p.number) in :numbers "
            + "and p.archivedAt is null")
    List<Plot> findActiveByLowerNumbers(@Param("tenantId") UUID tenantId,
            @Param("numbers") Collection<String> numbers);

    /** The plots, archived or not, that have one of these cadastral numbers. */
    @Query("select p from Plot p where p.tenantId = :tenantId and p.cadastralNumber in :cadastralNumbers")
    List<Plot> findByCadastralNumbers(@Param("tenantId") UUID tenantId,
            @Param("cadastralNumbers") Collection<String> cadastralNumbers);

    /** The plot, archived or not, with this cadastral number; at most one (unique index {@code plot_tenant_cadastral_uq}). */
    @Query("select p from Plot p where p.tenantId = :tenantId and p.cadastralNumber = :cadastralNumber")
    Optional<Plot> findByCadastralNumber(@Param("tenantId") UUID tenantId,
            @Param("cadastralNumber") String cadastralNumber);

    /**
     * One page of the plots list in natural order of the number. {@code textPattern} is a LIKE pattern over
     * lower-cased text ({@code %token%}, with {@code !} as the escape character) matched against the number, the
     * street and, only when {@code searchCadastral} is true, the cadastral number. When {@code searchOwners} is true
     * the plot also matches when one of its current owners is among {@code ownerIds}, which must not be empty (pass a
     * placeholder id while the branch is off). When {@code ownedOnly} is true only plots among {@code ownedPlotIds}
     * are listed, and that list must not be empty either (placeholder while off). {@code kind} and {@code status} are
     * optional filters; the status is the displayed one (common land is always GREY, a plot without a status row is
     * GREY). The order is fixed here, so the {@code Pageable} must be unsorted. Rows of other cooperatives are hidden
     * by RLS.
     */
    @Query(value = """
            select p.* from plot p
            left join plot_status s on s.plot_id = p.id
            where (:includeArchived = true or p.archived_at is null)
              and (cast(:kind as text) is null or p.kind = cast(:kind as text))
              and (cast(:status as text) is null
                   or coalesce(case when p.kind = 'COMMON' then 'GREY' else s.status end, 'GREY')
                      = cast(:status as text))
              and (:ownedOnly = false or p.id in (:ownedPlotIds))
              and (lower(p.number) like :textPattern escape '!'
                   or lower(p.street) like :textPattern escape '!'
                   or (:searchCadastral = true and lower(p.cadastral_number) like :textPattern escape '!')
                   or (:searchOwners = true and exists (
                         select 1 from ownership o
                         where o.plot_id = p.id and o.valid_to is null and o.member_id in (:ownerIds))))
            order by p.number_sort_key, p.number, p.id
            """,
            countQuery = """
            select count(*) from plot p
            left join plot_status s on s.plot_id = p.id
            where (:includeArchived = true or p.archived_at is null)
              and (cast(:kind as text) is null or p.kind = cast(:kind as text))
              and (cast(:status as text) is null
                   or coalesce(case when p.kind = 'COMMON' then 'GREY' else s.status end, 'GREY')
                      = cast(:status as text))
              and (:ownedOnly = false or p.id in (:ownedPlotIds))
              and (lower(p.number) like :textPattern escape '!'
                   or lower(p.street) like :textPattern escape '!'
                   or (:searchCadastral = true and lower(p.cadastral_number) like :textPattern escape '!')
                   or (:searchOwners = true and exists (
                         select 1 from ownership o
                         where o.plot_id = p.id and o.valid_to is null and o.member_id in (:ownerIds))))
            """,
            nativeQuery = true)
    Page<Plot> search(@Param("includeArchived") boolean includeArchived, @Param("kind") String kind,
            @Param("status") String status, @Param("textPattern") String textPattern,
            @Param("searchCadastral") boolean searchCadastral, @Param("searchOwners") boolean searchOwners,
            @Param("ownerIds") Collection<UUID> ownerIds, @Param("ownedOnly") boolean ownedOnly,
            @Param("ownedPlotIds") Collection<UUID> ownedPlotIds, Pageable pageable);
}
