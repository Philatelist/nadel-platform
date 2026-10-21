package app.nadel.meters.internal;

import app.nadel.meters.MeterCatalog;
import app.nadel.meters.MeterResource;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Serves the public {@link MeterCatalog} from the meter table; RLS limits it to the caller's cooperative. */
@Component
class JpaMeterCatalog implements MeterCatalog {

    private final MeterRepository meters;

    JpaMeterCatalog(MeterRepository meters) {
        this.meters = meters;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> plotIdsWithActiveMeter(MeterResource resource) {
        return Set.copyOf(meters.plotIdsWithActiveMeterOf(resource));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasMeterBetween(UUID plotId, MeterResource resource, LocalDate from, LocalDate to) {
        return meters.existsWorkingBetween(plotId, resource, from, to);
    }
}
