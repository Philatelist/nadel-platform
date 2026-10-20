package app.nadel.plots.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes the audit event of a change to a plot. Shared by the plot endpoints ({@link PlotCommands}) and the Excel
 * import, so a plot created or edited by either way is audited identically.
 */
@Component
class PlotAuditPublisher {

    private final ApplicationEventPublisher events;

    PlotAuditPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    void publish(UUID tenantId, Actor actor, String action, Plot plot, List<FieldChange> changes) {
        events.publishEvent(event(tenantId, actor, action, plot, changes));
    }

    /**
     * The audit event of a change, not yet published: the Excel import collects thousands of them and publishes them
     * as batches.
     */
    AuditEvent event(UUID tenantId, Actor actor, String action, Plot plot, List<FieldChange> changes) {
        return AuditEvent.now(
                tenantId,
                actor,
                action,
                "PLOT",
                plot.getId(),
                plot.getNumber(),
                changes);
    }
}
