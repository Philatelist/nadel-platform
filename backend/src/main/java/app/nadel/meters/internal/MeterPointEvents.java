package app.nadel.meters.internal;

import app.nadel.meters.MeterKind;
import app.nadel.meters.PlotMeterPointsChanged;
import app.nadel.meters.PlotMeterPointsChanged.Cause;
import java.time.LocalDate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link PlotMeterPointsChanged} when a point of a plot meter changed, inside the transaction of the change
 * (the Spring Modulith outbox stores it with the change). Meters of other kinds publish nothing: only plot meters are
 * charged by readings.
 */
@Component
class MeterPointEvents {

    private final ApplicationEventPublisher events;

    MeterPointEvents(ApplicationEventPublisher events) {
        this.events = events;
    }

    void changed(MeterRecord meter, LocalDate on, Cause cause) {
        if (meter.getKind() == MeterKind.PLOT && meter.getPlotId() != null) {
            events.publishEvent(new PlotMeterPointsChanged(meter.getTenantId(), meter.getId(), meter.getPlotId(),
                    meter.getResource(), on, cause));
        }
    }
}
