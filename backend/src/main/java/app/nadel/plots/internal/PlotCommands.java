package app.nadel.plots.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.identity.Permission;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.PlotDetails;
import nadel.contract.model.PlotInput;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and edits plots. Every method is one transaction that also publishes the {@link AuditEvent}: the outbox
 * stores the publication in the same transaction, so a change is never committed without its audit entry being owed.
 * <p>
 * The cooperative always comes from the caller's {@link AccessProfile}, never from the request. The caller must hold
 * {@code PLOTS_EDIT} (checked on the controller).
 */
@Service
class PlotCommands {

    private static final String NUMBER_INDEX = "plot_tenant_number_uq";
    private static final String CADASTRAL_INDEX = "plot_tenant_cadastral_uq";

    private final PlotRepository plots;
    private final PlotStatusRepository statuses;
    private final ApplicationEventPublisher events;

    PlotCommands(PlotRepository plots, PlotStatusRepository statuses, ApplicationEventPublisher events) {
        this.plots = plots;
        this.statuses = statuses;
        this.events = events;
    }

    @Transactional
    PlotDetails create(PlotInput input, AccessProfile actor) {
        PlotData data = PlotData.from(input);
        if (!actor.can(Permission.PLOTS_VIEW)) {
            // notes are read-protected, so an editor who cannot read them cannot write them either
            data = withNotes(data, null);
        }
        ensureNumberFree(actor.tenantId(), data.number(), null);
        ensureCadastralFree(actor.tenantId(), data.cadastralNumber(), null);
        Plot plot = save(Plot.create(actor.tenantId(), data, Instant.now()));
        // a plot has no owner yet, so it starts GREY without debt; the ledger changes it later
        statuses.save(PlotStatusEntry.initial(plot.getId(), plot.getTenantId()));
        publish(actor, "PLOT_CREATED", plot, PlotSnapshot.diff(PlotSnapshot.NONE, PlotSnapshot.of(plot)));
        return PlotDtoMapper.toDetails(actor, new PlotRow(plot, PlotStatusCode.GREY, null));
    }

    @Transactional
    PlotDetails update(UUID id, PlotInput input, AccessProfile actor) {
        PlotData data = PlotData.from(input);
        Plot plot = plots.findById(id).orElseThrow(ApiProblemException::notFound);
        if (!actor.can(Permission.PLOTS_VIEW)) {
            data = withNotes(data, plot.getNotes()); // keep what is stored: the editor cannot see it
        }
        if (plot.getArchivedAt() == null) {
            ensureNumberFree(actor.tenantId(), data.number(), plot.getId());
        }
        ensureCadastralFree(actor.tenantId(), data.cadastralNumber(), plot.getId());
        PlotSnapshot before = PlotSnapshot.of(plot);
        plot.apply(data);
        List<FieldChange> changes = PlotSnapshot.diff(before, PlotSnapshot.of(plot));
        if (!changes.isEmpty()) {
            plot.touch(Instant.now());
            save(plot);
            publish(actor, "PLOT_UPDATED", plot, changes);
        }
        PlotRow row = plots.findRowById(plot.getId()).orElseThrow(ApiProblemException::notFound);
        return PlotDtoMapper.toDetails(actor, row);
    }

    /** Saves and flushes, so a unique violation of an index surfaces here as the matching 409. */
    private Plot save(Plot plot) {
        try {
            return plots.saveAndFlush(plot);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, NUMBER_INDEX)) {
                throw numberTaken();
            }
            if (violates(e, CADASTRAL_INDEX)) {
                throw cadastralTaken();
            }
            throw e;
        }
    }

    /** Fast, friendly check; the unique index remains the guarantee against a concurrent request. */
    private void ensureNumberFree(UUID tenantId, String number, UUID selfId) {
        Optional<Plot> holder = plots.findActiveByNumber(tenantId, number);
        if (holder.isPresent() && !holder.get().getId().equals(selfId)) {
            throw numberTaken();
        }
    }

    private void ensureCadastralFree(UUID tenantId, String cadastralNumber, UUID selfId) {
        if (cadastralNumber == null) {
            return;
        }
        Optional<Plot> holder = plots.findByCadastralNumber(tenantId, cadastralNumber);
        if (holder.isPresent() && !holder.get().getId().equals(selfId)) {
            throw cadastralTaken();
        }
    }

    private void publish(AccessProfile actor, String action, Plot plot, List<FieldChange> changes) {
        events.publishEvent(AuditEvent.now(
                actor.tenantId(),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()),
                action,
                "PLOT",
                plot.getId(),
                plot.getNumber(),
                changes));
    }

    private static PlotData withNotes(PlotData data, String notes) {
        return new PlotData(data.number(), data.street(), data.kind(), data.areaM2(), data.cadastralNumber(),
                data.hasBuilding(), notes);
    }

    private static ApiProblemException numberTaken() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_NUMBER_TAKEN,
                "Another active plot already has this number.",
                List.of(new FieldProblem("number", ProblemCodes.PLOT_NUMBER_TAKEN)));
    }

    private static ApiProblemException cadastralTaken() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.CADASTRAL_NUMBER_TAKEN,
                "Another plot already has this cadastral number.",
                List.of(new FieldProblem("cadastralNumber", ProblemCodes.CADASTRAL_NUMBER_TAKEN)));
    }

    private static boolean violates(Throwable e, String index) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && index.equals(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(index)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
