package app.nadel.plots.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PlotRepository extends JpaRepository<Plot, UUID> {

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

    /**
     * One page of the plots list in natural order of the number. {@code textPattern} is a LIKE pattern over
     * lower-cased text ({@code %token%}, with {@code !} as the escape character) matched against the number, the
     * street and, only when {@code searchCadastral} is true, the cadastral number. {@code kind} and {@code status} are
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
              and (lower(p.number) like :textPattern escape '!'
                   or lower(p.street) like :textPattern escape '!'
                   or (:searchCadastral = true and lower(p.cadastral_number) like :textPattern escape '!'))
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
              and (lower(p.number) like :textPattern escape '!'
                   or lower(p.street) like :textPattern escape '!'
                   or (:searchCadastral = true and lower(p.cadastral_number) like :textPattern escape '!'))
            """,
            nativeQuery = true)
    Page<Plot> search(@Param("includeArchived") boolean includeArchived, @Param("kind") String kind,
            @Param("status") String status, @Param("textPattern") String textPattern,
            @Param("searchCadastral") boolean searchCadastral, Pageable pageable);
}
