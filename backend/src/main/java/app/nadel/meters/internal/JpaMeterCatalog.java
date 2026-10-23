package app.nadel.meters.internal;

import app.nadel.meters.MeterCatalog;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Serves the public {@link MeterCatalog} from the meter table; RLS limits it to the caller's cooperative. */
@Component
class JpaMeterCatalog implements MeterCatalog {

    private final MeterRepository meters;
    private final MeterChains chains;

    JpaMeterCatalog(MeterRepository meters, MeterChains chains) {
        this.meters = meters;
        this.chains = chains;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> plotIdsWithActiveMeter(MeterResource resource) {
        return Set.copyOf(meters.plotIdsWithActiveMeterOf(resource));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MeterFacts> find(UUID meterId) {
        return meters.findById(meterId).map(meter -> new MeterFacts(meter.getId(), meter.getKind(),
                meter.getResource(), meter.getName(), meter.isActive()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> chainOf(UUID meterId) {
        return meters.findById(meterId).map(meter -> chains.chainOf(meter).stream().map(MeterRecord::getId).toList())
                .orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasMeterBetween(UUID plotId, MeterResource resource, LocalDate from, LocalDate to) {
        return meters.existsWorkingBetween(plotId, resource, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> plotMeterIds(Collection<UUID> plotIds, Collection<MeterResource> resources) {
        if (plotIds.isEmpty() || resources.isEmpty()) {
            return List.of();
        }
        return meters.idsOfPlots(plotIds, MeterKind.PLOT, resources);
    }
}
