package app.nadel.meters.internal;

import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.meters.MeterResource;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nadel.contract.model.MeterParentChange;
import nadel.contract.model.MeterParentPeriod;
import nadel.contract.model.MeterPlacement;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves an electricity meter to another parent in the tree from a date (spec 010, section 2.2). One transaction that
 * also publishes the audit event; the caller must hold {@code METERS_VIEW} and {@code METERS_MANAGE} (checked on the
 * controller). The rules are {@link TreeRules} plus the dates: the meter is active and installed by the effective date,
 * the date is not after today and not before the meter's latest change, and the parent is active and installed by then.
 * <p>
 * Locks: the new parent {@code FOR SHARE} first, then the meter {@code FOR UPDATE} (a parent before its children, the
 * order of a replacement too). A removal or replacement of the parent waits for the move and then sees the meter under
 * it; a move under a parent being removed waits and then sees it removed.
 */
@Service
class TreeCommands {

    private final MeterRepository meters;
    private final MeterParents parents;
    private final MeterAuditPublisher audit;
    private final Clock clock;

    TreeCommands(MeterRepository meters, MeterParents parents, MeterAuditPublisher audit, Clock clock) {
        this.meters = meters;
        this.parents = parents;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    MeterPlacement setParent(UUID id, MeterParentChange input, AccessProfile actor) {
        if (!meters.existsById(id)) {
            throw ApiProblemException.notFound();
        }
        UUID parentId = input.getParentMeterId();
        MeterRecord parent = parentId == null ? null
                : meters.findByIdForShare(parentId).orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                        ProblemCodes.NOT_FOUND, "The parent meter was not found.",
                        List.of(new FieldProblem("parentMeterId", ProblemCodes.NOT_FOUND))));
        MeterRecord meter = meters.findByIdForUpdate(id).orElseThrow(ApiProblemException::notFound);
        LocalDate from = input.getEffectiveFrom();

        if (meter.getResource() != MeterResource.ELECTRICITY) {
            throw invalid("parentMeterId", ProblemCodes.TREE_WATER);
        }
        if (!meter.isActive()) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.METER_REMOVED,
                    "The meter is removed; its place in the tree is kept as it was.");
        }
        if (from.isAfter(LocalDate.now(clock))) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.READING_DATE_IN_FUTURE,
                    "A move in the tree cannot be dated in the future.",
                    List.of(new FieldProblem("effectiveFrom", ProblemCodes.READING_DATE_IN_FUTURE)));
        }
        if (from.isBefore(meter.getInstalledOn())) {
            throw invalid("effectiveFrom", ProblemCodes.READING_DATE_OUTSIDE_METER);
        }
        Optional<String> kindProblem = parent == null ? Optional.empty()
                : TreeRules.parentProblem(meter.getKind(), meter.getResource(), parent.getKind(),
                        parent.getId().equals(meter.getId()));
        if (kindProblem.isPresent()) {
            throw invalid("parentMeterId", kindProblem.get());
        }
        if (parent != null && (!parent.isActive() || parent.getInstalledOn().isAfter(from))) {
            throw invalid("parentMeterId", ProblemCodes.TREE_PARENT_INACTIVE);
        }
        if (parents.latestChange(meter.getId()).filter(from::isBefore).isPresent()) {
            throw invalid("effectiveFrom", ProblemCodes.TREE_DATE_BEFORE_LATEST);
        }

        MeterParents.Move move = parents.move(meter, parentId, from, clock.instant());
        if (move.changed()) {
            audit.publish(actor, "METER_PARENT_CHANGED", meter, List.of(
                    new FieldChange("parent", label(move.previousParent()), parent == null ? null : parent.auditLabel()),
                    new FieldChange("effectiveFrom", null, from.toString())));
        }
        return placement(meter.getId());
    }

    /** The parent periods of the meter as the API shows them, oldest first. */
    private MeterPlacement placement(UUID meterId) {
        List<MeterParentPeriod> periods = parents.periodsOf(meterId).stream()
                .map(row -> {
                    MeterParentPeriod period = new MeterParentPeriod(row.getParentMeterId(), row.getValidFrom());
                    period.setValidTo(row.getValidTo());
                    return period;
                })
                .toList();
        return new MeterPlacement(meterId, periods);
    }

    private @Nullable String label(@Nullable UUID meterId) {
        return meterId == null ? null : meters.findById(meterId).map(MeterRecord::auditLabel).orElse(null);
    }

    private static ApiProblemException invalid(String field, String code) {
        return new ApiProblemException(HttpStatus.CONFLICT, ProblemCodes.METER_TREE_INVALID,
                "The change breaks a rule of the meter tree.", List.of(new FieldProblem(field, code)));
    }
}
