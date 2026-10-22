package app.nadel.plots.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.plots.PlotChangeGuard.Change;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.PlotDetails;
import nadel.contract.model.PlotInput;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, edits, archives, restores and deletes plots. Every method is one transaction that also publishes the audit event: the outbox
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
    private final OwnershipRepository ownerships;
    private final PlotAuditPublisher audit;
    private final PlotChangeGuards guards;

    PlotCommands(PlotRepository plots, PlotStatusRepository statuses, OwnershipRepository ownerships,
            PlotAuditPublisher audit, PlotChangeGuards guards) {
        this.plots = plots;
        this.statuses = statuses;
        this.ownerships = ownerships;
        this.audit = audit;
        this.guards = guards;
    }

    @Transactional
    PlotDetails create(PlotInput input, AccessProfile actor) {
        PlotData data = PlotData.from(input);
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
        Plot plot = plots.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        if (plot.getKind() == PlotKind.PLOT && data.kind() == PlotKind.COMMON && ownerships.existsByPlotId(id)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_HAS_OWNERSHIP_HISTORY,
                    "A plot that has had owners cannot become common land.",
                    List.of(new FieldProblem("kind", ProblemCodes.PLOT_HAS_OWNERSHIP_HISTORY)));
        }
        if (plot.getKind() == PlotKind.PLOT && data.kind() == PlotKind.COMMON) {
            guards.ensureAllowed(Change.BECOME_COMMON_LAND, id, "kind"); // for example a plot with a meter
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

    @Transactional
    PlotDetails archive(UUID id, AccessProfile actor) {
        Plot plot = plots.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        if (plot.getArchivedAt() == null) {
            if (ownerships.existsCurrentByPlotId(id)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_HAS_OWNERS,
                        "A plot with a current owner cannot be archived; change the owner first.");
            }
            guards.ensureAllowed(Change.ARCHIVE, id, null); // for example a plot with an active meter
            PlotSnapshot before = PlotSnapshot.of(plot);
            plot.archive(Instant.now());
            save(plot);
            publish(actor, "PLOT_ARCHIVED", plot, PlotSnapshot.diff(before, PlotSnapshot.of(plot)));
        }
        return card(plot, actor);
    }

    @Transactional
    PlotDetails restore(UUID id, AccessProfile actor) {
        Plot plot = plots.findById(id).orElseThrow(ApiProblemException::notFound);
        if (plot.getArchivedAt() != null) {
            ensureNumberFree(actor.tenantId(), plot.getNumber(), plot.getId());
            PlotSnapshot before = PlotSnapshot.of(plot);
            plot.restore(Instant.now());
            save(plot);
            publish(actor, "PLOT_RESTORED", plot, PlotSnapshot.diff(before, PlotSnapshot.of(plot)));
        }
        return card(plot, actor);
    }

    /**
     * Removes a plot created by mistake. Only an active plot can be deleted: an archived one must be restored first,
     * and a plot with any ownership row, current or past, is refused ({@code PLOT_HAS_OWNERSHIP_HISTORY}), as is a
     * plot that a {@link app.nadel.plots.PlotChangeGuard} refuses (a plot with a meter: {@code PLOT_HAS_METER}).
     * The {@code plot_status} row goes with the plot through the foreign key's cascade.
     */
    @Transactional
    void delete(UUID id, AccessProfile actor) {
        Plot plot = plots.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        if (plot.getArchivedAt() != null) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_ARCHIVED,
                    "An archived plot cannot be deleted; restore it first.");
        }
        // the foreign key of ownership (ON DELETE RESTRICT) stays as the backstop of this check
        if (ownerships.existsByPlotId(id)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_HAS_OWNERSHIP_HISTORY,
                    "A plot that has had owners cannot be deleted.");
        }
        guards.ensureAllowed(Change.DELETE, id, null); // for example a plot with a meter
        PlotSnapshot before = PlotSnapshot.of(plot);
        plots.delete(plot);
        plots.flush();
        publish(actor, "PLOT_DELETED", plot, PlotSnapshot.diff(before, PlotSnapshot.NONE));
    }

    private PlotDetails card(Plot plot, AccessProfile actor) {
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
        audit.publish(actor.tenantId(), new Actor(actor.memberId(), actor.subject(), actor.displayName()), action, plot,
                changes);
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
