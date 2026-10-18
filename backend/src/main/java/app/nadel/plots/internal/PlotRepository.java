package app.nadel.plots.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PlotRepository extends JpaRepository<Plot, UUID> {

    // no tenant_id filter needed: RLS hides other cooperatives' rows
    @Query("""
            select new app.nadel.plots.internal.PlotRow(p, s.status, s.debt)
            from Plot p left join PlotStatusEntry s on s.plotId = p.id
            where p.archivedAt is null
            order by p.number, p.id
            """)
    List<PlotRow> findActiveWithStatus();
}
