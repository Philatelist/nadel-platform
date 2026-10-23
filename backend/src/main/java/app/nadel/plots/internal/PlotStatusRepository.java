package app.nadel.plots.internal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// no tenant_id filter needed: RLS hides other cooperatives' rows
interface PlotStatusRepository extends JpaRepository<PlotStatusEntry, UUID> {

    /** Creates the missing cache rows of existing plots (GREY, no debt); idempotent under concurrency. */
    @Modifying
    @Query(value = "insert into plot_status (plot_id, tenant_id, status, debt) "
            + "select p.id, p.tenant_id, 'GREY', 0 from plot p where p.id in (:plotIds) "
            + "on conflict (plot_id) do nothing", nativeQuery = true)
    int insertMissing(@Param("plotIds") Collection<UUID> plotIds);

    /** The cache rows with an exclusive row lock ({@code FOR UPDATE}) in id order, until the end of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PlotStatusEntry s where s.plotId in :plotIds order by s.plotId")
    List<PlotStatusEntry> lockAllForUpdate(@Param("plotIds") Collection<UUID> plotIds);
}
