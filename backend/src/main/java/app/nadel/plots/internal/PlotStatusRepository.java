package app.nadel.plots.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlotStatusRepository extends JpaRepository<PlotStatusEntry, UUID> {
}
