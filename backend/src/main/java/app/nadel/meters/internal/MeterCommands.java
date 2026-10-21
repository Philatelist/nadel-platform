package app.nadel.meters.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.meters.ConsumptionCalculator.Reading;
import app.nadel.meters.InvalidMeterValueException;
import app.nadel.meters.MeterKind;
import app.nadel.meters.MeterResource;
import app.nadel.meters.MeterValue;
import app.nadel.plots.PlotCatalog;
import app.nadel.plots.PlotCatalog.PlotFacts;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.ConstraintViolations;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nadel.contract.model.Meter;
import nadel.contract.model.MeterCreate;
import nadel.contract.model.MeterRemoval;
import nadel.contract.model.MeterUpdate;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers, corrects, removes and deletes meters. Every method is one transaction that also publishes the audit event.
 * The cooperative always comes from the caller's {@link AccessProfile}, never from the request; the caller must hold
 * {@code METERS_VIEW} and {@code METERS_MANAGE} (checked on the controller).
 * <p>
 * Locks: registering a plot meter takes the plot {@code FOR SHARE} first ({@link PlotCatalog#findAndLockShared}), so it
 * runs after a delete, archive or edit of the plot in progress (they hold {@code FOR UPDATE} and ask the
 * {@link MeterPlotGuard}) and those run after it. Every change of an existing meter locks the meter {@code FOR UPDATE}
 * first, the lock the reading writes take too. Two registrations of the same plot and resource at once are kept apart by
 * the partial unique index {@code meter_active_plot_uq}, mapped to {@code METER_ACTIVE_EXISTS}.
 */
@Service
class MeterCommands {

    private static final String ACTIVE_INDEX = "meter_active_plot_uq";

    private final MeterRepository meters;
    private final PlotCatalog plots;
    private final ReadingFacts readings;
    private final MeterQueries queries;
    private final MeterAuditPublisher audit;
    private final Clock clock;

    MeterCommands(MeterRepository meters, PlotCatalog plots, ReadingFacts readings, MeterQueries queries,
            MeterAuditPublisher audit, Clock clock) {
        this.meters = meters;
        this.plots = plots;
        this.readings = readings;
        this.queries = queries;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    Meter register(MeterCreate input, AccessProfile actor) {
        MeterKind kind = MeterKind.valueOf(input.getKind().getValue());
        MeterResource resource = MeterResource.valueOf(input.getResource().getValue());
        String name = blankToNull(input.getName());
        List<FieldProblem> problems = new ArrayList<>();
        if (kind == MeterKind.PLOT) {
            if (input.getPlotId() == null) {
                problems.add(new FieldProblem("plotId", ProblemCodes.REQUIRED));
            }
            if (name != null) {
                problems.add(new FieldProblem("name", ProblemCodes.INVALID));
            }
        } else {
            if (input.getPlotId() != null) {
                problems.add(new FieldProblem("plotId", ProblemCodes.INVALID));
            }
            if (name == null) {
                problems.add(new FieldProblem("name", ProblemCodes.REQUIRED));
            }
            if (resource != MeterResource.ELECTRICITY) {
                problems.add(new FieldProblem("resource", ProblemCodes.INVALID));
            }
        }
        String serial = blankToNull(input.getSerial());
        if (serial == null) {
            problems.add(new FieldProblem("serial", ProblemCodes.REQUIRED));
        }
        MeterValue initial = meterValue(input.getInitialReading(), "initialReading", problems);
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }

        PlotFacts plot = null;
        if (kind == MeterKind.PLOT) {
            plot = plots.findAndLockShared(input.getPlotId())
                    .orElseThrow(() -> invalid(List.of(new FieldProblem("plotId", ProblemCodes.UNKNOWN_PLOT))));
            if (plot.commonLand()) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.METER_COMMON_LAND,
                        "Common land has no plot meters.",
                        List.of(new FieldProblem("plotId", ProblemCodes.METER_COMMON_LAND)));
            }
            if (plot.archived()) {
                throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.PLOT_ARCHIVED,
                        "An archived plot gets no meter.",
                        List.of(new FieldProblem("plotId", ProblemCodes.PLOT_ARCHIVED)));
            }
        }
        ensureNotInFuture(input.getInstalledOn(), "installedOn");
        if (plot != null && meters.existsActive(plot.id(), resource)) {
            throw activeExists();
        }

        MeterRecord meter = MeterRecord.register(actor.tenantId(), kind, resource, plot == null ? null : plot.id(),
                serial, name, input.getInstalledOn(), initial, blankToNull(input.getNote()), clock.instant());
        save(meter);
        audit.publish(actor, "METER_REGISTERED", meter, MeterSnapshot.diff(MeterSnapshot.NONE,
                MeterSnapshot.of(meter, plot == null ? null : plot.number())));
        return queries.card(meter, true);
    }

    /** Replaces the serial number, name and note; a request that changes nothing writes no audit entry. */
    @Transactional
    Meter update(UUID id, MeterUpdate input, AccessProfile actor) {
        MeterRecord meter = meters.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        String name = blankToNull(input.getName());
        List<FieldProblem> problems = new ArrayList<>();
        if (meter.getKind() == MeterKind.PLOT && name != null) {
            problems.add(new FieldProblem("name", ProblemCodes.INVALID));
        }
        if (meter.getKind() != MeterKind.PLOT && name == null) {
            problems.add(new FieldProblem("name", ProblemCodes.REQUIRED));
        }
        String serial = blankToNull(input.getSerial());
        if (serial == null) {
            problems.add(new FieldProblem("serial", ProblemCodes.REQUIRED));
        }
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        String plotNumber = plotNumber(meter);
        MeterSnapshot before = MeterSnapshot.of(meter, plotNumber);
        String note = blankToNull(input.getNote());
        MeterSnapshot after = new MeterSnapshot(before.kind(), before.resource(), before.plot(), serial, name,
                before.installedOn(), before.initialReading(), before.removedOn(), before.finalReading(), note);
        List<FieldChange> changes = MeterSnapshot.diff(before, after);
        if (!changes.isEmpty()) {
            meter.describe(serial, name, note, clock.instant());
            save(meter);
            audit.publish(actor, "METER_UPDATED", meter, changes);
        }
        return queries.card(meter, true);
    }

    /**
     * Ends the working period of the meter on {@code removedOn} with the final reading. The final reading is the last
     * point of the meter: not after today, not before the installation, not before a counted reading and not lower than
     * the counted reading before it (or the initial reading).
     */
    @Transactional
    Meter remove(UUID id, MeterRemoval input, AccessProfile actor) {
        MeterRecord meter = meters.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        if (!meter.isActive()) {
            throw removed();
        }
        List<FieldProblem> problems = new ArrayList<>();
        MeterValue finalValue = meterValue(input.getFinalReading(), "finalReading", problems);
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        LocalDate removedOn = input.getRemovedOn();
        ensureNotInFuture(removedOn, "removedOn");
        if (removedOn.isBefore(meter.getInstalledOn())) {
            throw conflict(ProblemCodes.READING_DATE_OUTSIDE_METER,
                    "A meter is not removed before it was installed.", "removedOn");
        }
        Reading previous = readings.latestApproved(meter.getId())
                .orElse(new Reading(meter.getInstalledOn(), meter.getInitialValue()));
        if (previous.takenOn().isAfter(removedOn)) {
            throw conflict(ProblemCodes.READING_OUT_OF_ORDER, "The meter has a reading after the removal date.",
                    "removedOn");
        }
        if (finalValue.compareTo(previous.value()) < 0) {
            throw conflict(ProblemCodes.READING_OUT_OF_ORDER,
                    "The final reading is lower than the reading before it.", "finalReading");
        }
        MeterSnapshot before = MeterSnapshot.of(meter, null);
        meter.remove(removedOn, finalValue, clock.instant());
        save(meter);
        audit.publish(actor, "METER_REMOVED", meter, MeterSnapshot.diff(before, MeterSnapshot.of(meter, null)));
        return queries.card(meter, true);
    }

    /**
     * Deletes a meter entered by mistake: only an active meter without readings of any status (its initial reading
     * does not count). Its parent rows in the tree go with it ({@code ON DELETE CASCADE}).
     */
    @Transactional
    void delete(UUID id, AccessProfile actor) {
        MeterRecord meter = meters.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        if (!meter.isActive()) {
            throw removed();
        }
        // the foreign key of meter_reading (ON DELETE RESTRICT) stays as the backstop of this check
        if (readings.hasReadings(meter.getId())) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.METER_HAS_READINGS,
                    "A meter with readings is never deleted; remove it instead.");
        }
        MeterSnapshot before = MeterSnapshot.of(meter, plotNumber(meter));
        meters.delete(meter);
        meters.flush();
        audit.publish(actor, "METER_DELETED", meter, MeterSnapshot.diff(before, MeterSnapshot.NONE));
    }

    private @Nullable String plotNumber(MeterRecord meter) {
        return meter.getPlotId() == null ? null
                : plots.find(meter.getPlotId()).map(PlotFacts::number).orElse(null);
    }

    /** Saves and flushes, so a second active meter of a plot and resource surfaces here as the matching 409. */
    private void save(MeterRecord meter) {
        try {
            meters.saveAndFlush(meter);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, ACTIVE_INDEX)) {
                throw activeExists();
            }
            throw e;
        }
    }

    /** A reading date is not after today in the cooperative's time zone (the Europe/Kyiv clock). */
    private void ensureNotInFuture(LocalDate date, String field) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw conflict(ProblemCodes.READING_DATE_IN_FUTURE, "A reading date cannot be in the future.", field);
        }
    }

    /** The value of a reading; the contract pattern already checked its form, so a failure here is a 400 too. */
    private static @Nullable MeterValue meterValue(String text, String field, List<FieldProblem> problems) {
        try {
            return MeterValue.parse(text);
        } catch (InvalidMeterValueException e) {
            problems.add(new FieldProblem(field, ProblemCodes.METER_VALUE_INVALID));
            return null;
        }
    }

    private static ApiProblemException invalid(List<FieldProblem> problems) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                "The request is not valid.", problems);
    }

    private static ApiProblemException conflict(String code, String detail, String field) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail, List.of(new FieldProblem(field, code)));
    }

    private static ApiProblemException activeExists() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.METER_ACTIVE_EXISTS,
                "The plot already has an active meter of this resource.");
    }

    private static ApiProblemException removed() {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.METER_REMOVED,
                "The meter is removed; it keeps its history.");
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
