package app.nadel.settings.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.settings.CooperativeSettings;
import app.nadel.settings.PlotStatusVisibility;
import app.nadel.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the settings of the current cooperative. A cooperative with no row has the defaults. A change is
 * one transaction that also publishes the {@link AuditEvent}; saving the current value writes nothing.
 * <p>
 * The cooperative comes from the token ({@code TenantContext} or the caller's profile), never from the request. The
 * caller of {@link #update} must hold {@code SETTINGS_MANAGE} (checked on the controller).
 */
@Service
class SettingsService implements CooperativeSettings {

    static final PlotStatusVisibility DEFAULT_VISIBILITY = PlotStatusVisibility.OWN_ONLY;

    private static final String FIELD = "memberPlotStatusVisibility";
    /** A code, not a phrase: the client shows the name of the settings in the reader's language. */
    private static final String LABEL = "settings";

    private final SettingsRepository settings;
    private final ApplicationEventPublisher events;

    SettingsService(SettingsRepository settings, ApplicationEventPublisher events) {
        this.settings = settings;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public PlotStatusVisibility memberPlotStatusVisibility() {
        UUID tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new IllegalStateException("No cooperative bound to the current request"));
        return visibilityOf(tenantId);
    }

    @Transactional(readOnly = true)
    PlotStatusVisibility visibility(AccessProfile viewer) {
        return visibilityOf(viewer.tenantId());
    }

    @Transactional
    PlotStatusVisibility update(PlotStatusVisibility requested, AccessProfile actor) {
        UUID tenantId = actor.tenantId();
        PlotStatusVisibility before = visibilityOf(tenantId);
        if (before != requested) {
            settings.upsert(tenantId, requested.name());
            events.publishEvent(AuditEvent.now(
                    tenantId,
                    new Actor(actor.memberId(), actor.subject(), actor.displayName()),
                    "SETTINGS_UPDATED",
                    "SETTINGS",
                    tenantId,
                    LABEL,
                    List.of(new FieldChange(FIELD, before.name(), requested.name()))));
        }
        return requested;
    }

    private PlotStatusVisibility visibilityOf(UUID tenantId) {
        return settings.findById(tenantId)
                .map(SettingsRow::getMemberPlotStatusVisibility)
                .orElse(DEFAULT_VISIBILITY);
    }
}
