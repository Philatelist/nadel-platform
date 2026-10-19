package app.nadel.plots.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.identity.AccessProfile;
import app.nadel.members.MemberDirectory;
import app.nadel.members.MemberRef;
import app.nadel.plots.internal.OwnershipRules.DateProblem;
import app.nadel.plots.internal.OwnershipRules.Period;
import app.nadel.plots.internal.OwnershipRules.Violation;
import app.nadel.problem.ApiProblemException;
import app.nadel.problem.FieldProblem;
import app.nadel.problem.ProblemCodes;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import nadel.contract.model.OwnershipChange;
import nadel.contract.model.OwnershipChangeOwner;
import nadel.contract.model.OwnershipCorrection;
import nadel.contract.model.OwnershipCorrectionCoOwner;
import nadel.contract.model.PlotOwnerships;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes who owns a plot. Every method is one transaction that also publishes the {@link AuditEvent}.
 * <p>
 * The plot row is locked first ({@code FOR UPDATE}), so two changes of one plot run one after another and each sees
 * the other's result; the new owners are then locked {@code FOR SHARE} through {@link MemberDirectory#lockActive}, so
 * a member cannot be archived while the change commits. The cooperative comes from the caller's
 * {@link AccessProfile}, never from the request. The caller must hold {@code PLOTS_VIEW} and {@code OWNERSHIP_EDIT}
 * (checked on the controller).
 */
@Service
class OwnershipCommands {

    private static final String OVERLAP_CONSTRAINT = "ownership_no_overlap_ex";

    private final PlotRepository plots;
    private final PlotStatusRepository statuses;
    private final OwnershipRepository ownerships;
    private final OwnershipQueries queries;
    private final MemberDirectory members;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OwnershipCommands(PlotRepository plots, PlotStatusRepository statuses, OwnershipRepository ownerships,
            OwnershipQueries queries, MemberDirectory members, ApplicationEventPublisher events, Clock clock) {
        this.plots = plots;
        this.statuses = statuses;
        this.ownerships = ownerships;
        this.queries = queries;
        this.members = members;
        this.events = events;
        this.clock = clock;
    }

    /** One owner row of the request, with its share parsed. */
    private record NewOwner(int index, UUID memberId, Share share) {
    }

    /**
     * Ends every current owner on the day before {@code request.effectiveFrom} and starts the listed owners on that
     * day; an empty list leaves the plot without an owner. Returns the owners and the history after the change.
     */
    @Transactional
    PlotOwnerships change(UUID plotId, OwnershipChange request, AccessProfile actor) {
        Plot plot = plots.findByIdForUpdate(plotId).orElseThrow(ApiProblemException::notFound);
        if (plot.getArchivedAt() != null) {
            throw conflict(ProblemCodes.PLOT_ARCHIVED, "The owners of an archived plot cannot be changed.", List.of());
        }
        if (plot.getKind() == PlotKind.COMMON) {
            throw conflict(ProblemCodes.PLOT_COMMON_LAND, "Common land has no owners.", List.of());
        }

        LocalDate effectiveFrom = request.getEffectiveFrom();
        List<NewOwner> owners = parseOwners(request.getOwners());
        requireDistinctMembers(owners);
        Map<UUID, MemberRef> newMembers = lockMembers(owners);

        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();
        List<Ownership> rows = ownerships.findAllByPlotId(plotId);
        List<Ownership> current = rows.stream().filter(o -> o.getValidTo() == null).toList();

        OwnershipRules.checkChangeDate(effectiveFrom, rows.stream().map(OwnershipCommands::periodOf).toList(), today)
                .ifPresent(problem -> {
                    throw dateInvalid(problem);
                });
        LocalDate lastDay = OwnershipRules.lastDayOfPreviousOwners(effectiveFrom);
        List<Period> timeline = Stream.concat(
                rows.stream().map(o -> o.getValidTo() == null
                        ? new Period(o.getId(), o.getMemberId(), o.getShare(), o.getValidFrom(), lastDay)
                        : periodOf(o)),
                owners.stream().map(o -> new Period(o.index(), o.memberId(), o.share(), effectiveFrom, null)))
                .toList();
        List<Violation> violations = OwnershipRules.checkTimeline(timeline, today);
        if (!violations.isEmpty()) {
            throw toProblem(violations.getFirst());
        }

        Map<UUID, MemberRef> previousMembers = members.find(current.stream().map(Ownership::getMemberId).toList());
        String previousOwners = ownersText(current.stream()
                .map(o -> text(previousMembers.get(o.getMemberId()), o.getShare())));

        String basis = blankToNull(request.getBasis());
        saveChange(current, lastDay, now, owners.stream()
                .map(o -> Ownership.start(actor.tenantId(), plotId, o.memberId(), o.share(), effectiveFrom, basis, now))
                .toList());
        refreshStatus(plot, !owners.isEmpty(), now);

        String newOwners = ownersText(owners.stream().map(o -> text(newMembers.get(o.memberId()), o.share())));
        publish(actor, plot, effectiveFrom, previousOwners, newOwners, basis);
        return queries.ofPlot(plotId).orElseThrow(ApiProblemException::notFound);
    }

    /** One co-owner row of a correction, with its share parsed. */
    private record CoOwnerShare(int index, Ownership row, Share share) {
    }

    /**
     * Corrects one owner record: the request replaces the member, share, dates and basis of the target, and
     * {@code coOwnerShares} replaces the shares of other records of the same plot, all in one step. The timeline of
     * the whole plot after the correction must satisfy {@link OwnershipRules}. Returns the owners and the history
     * after the correction. A correction that changes nothing is accepted and not audited.
     */
    @Transactional
    PlotOwnerships correct(UUID ownershipId, OwnershipCorrection request, AccessProfile actor) {
        // the plot id is read as a scalar: the plot is locked first and the rows are then read fresh
        UUID plotId = ownerships.findPlotIdById(ownershipId).orElseThrow(ApiProblemException::notFound);
        Plot plot = plots.findByIdForUpdate(plotId).orElseThrow(ApiProblemException::notFound);
        if (plot.getArchivedAt() != null) {
            throw conflict(ProblemCodes.PLOT_ARCHIVED, "The owners of an archived plot cannot be changed.", List.of());
        }
        if (plot.getKind() == PlotKind.COMMON) {
            throw conflict(ProblemCodes.PLOT_COMMON_LAND, "Common land has no owners.", List.of());
        }
        List<Ownership> rows = ownerships.findAllByPlotId(plotId);
        Ownership target = rows.stream().filter(o -> o.getId().equals(ownershipId)).findFirst()
                .orElseThrow(ApiProblemException::notFound);

        List<FieldProblem> invalid = new ArrayList<>();
        Share share = parseShare(request.getShare(), "share", invalid);
        List<CoOwnerShare> coOwners = parseCoOwners(request.getCoOwnerShares(), rows, target, invalid);
        String reason = blankToNull(request.getReason());
        if (reason == null) {
            invalid.add(new FieldProblem("reason", ProblemCodes.REQUIRED));
        }
        if (!invalid.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", invalid);
        }

        MemberRef newMember = lockCorrectionMember(request.getMemberId(), target);
        LocalDate validFrom = request.getValidFrom();
        LocalDate validTo = request.getValidTo();
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw conflict(ProblemCodes.OWNERSHIP_DATE_INVALID, "The end date cannot be before the start date.",
                    List.of(new FieldProblem("validTo", ProblemCodes.OWNERSHIP_DATE_INVALID)));
        }
        if (newMember.archived() && validTo == null) {
            // an archived member may keep a historical record but never becomes a current owner
            throw conflict(ProblemCodes.MEMBER_ARCHIVED, "An archived member cannot be a current owner.",
                    List.of(new FieldProblem("validTo", ProblemCodes.MEMBER_ARCHIVED)));
        }
        String basis = blankToNull(request.getBasis());

        List<Ownership> changed = new ArrayList<>();
        Map<UUID, String> before = new LinkedHashMap<>();
        Map<UUID, Share> shareAfter = new LinkedHashMap<>();
        boolean targetChanged = !(target.getMemberId().equals(request.getMemberId())
                && target.getShare().equals(share) && target.getValidFrom().equals(validFrom)
                && Objects.equals(target.getValidTo(), validTo)
                && Objects.equals(target.getBasis(), basis));
        if (targetChanged) {
            changed.add(target);
        }
        for (CoOwnerShare coOwner : coOwners) {
            if (!coOwner.row().getShare().equals(coOwner.share())) {
                changed.add(coOwner.row());
                shareAfter.put(coOwner.row().getId(), coOwner.share());
            }
        }
        if (changed.isEmpty()) {
            return queries.ofPlot(plotId).orElseThrow(ApiProblemException::notFound);
        }

        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();
        List<Period> timeline = rows.stream().map(o -> {
            if (o == target) {
                return new Period(o.getId(), request.getMemberId(), share, validFrom, validTo);
            }
            Share replaced = shareAfter.get(o.getId());
            return replaced == null ? periodOf(o)
                    : new Period(o.getId(), o.getMemberId(), replaced, o.getValidFrom(), o.getValidTo());
        }).toList();
        List<Violation> violations = OwnershipRules.checkTimeline(timeline, today);
        if (!violations.isEmpty()) {
            throw toCorrectionProblem(violations.getFirst(), timeline, target);
        }

        Map<UUID, MemberRef> names = new LinkedHashMap<>(members.find(rows.stream().map(Ownership::getMemberId)
                .distinct().toList()));
        names.put(newMember.id(), newMember);
        changed.forEach(o -> before.put(o.getId(), text(names.get(o.getMemberId()), o)));
        String basisBefore = target.getBasis();

        if (targetChanged) {
            target.correct(request.getMemberId(), share, validFrom, validTo, basis, now);
        }
        coOwners.stream().filter(c -> shareAfter.containsKey(c.row().getId()))
                .forEach(c -> c.row().reshare(c.share(), now));
        saveCorrection(changed);
        refreshStatus(plot, rows.stream().anyMatch(o -> o.getValidTo() == null), now);

        String oldOwners = ownersText(changed.stream().map(o -> before.get(o.getId())));
        String newOwners = ownersText(changed.stream().map(o -> text(names.get(o.getMemberId()), o)));
        publishCorrection(actor, plot, oldOwners, newOwners, targetChanged ? basisBefore : null,
                targetChanged ? basis : null, reason);
        return queries.ofPlot(plotId).orElseThrow(ApiProblemException::notFound);
    }

    private static @Nullable Share parseShare(String text, String field, List<FieldProblem> invalid) {
        try {
            return Share.parse(text);
        } catch (InvalidShareException e) {
            invalid.add(new FieldProblem(field, ProblemCodes.SHARE_INVALID, e.getMessage()));
            return null;
        }
    }

    /**
     * Parses the co-owner rows and resolves each id among the rows of the plot. An id of another plot (or of another
     * cooperative, hidden by RLS), the target itself, or an id listed twice is a 400 on its own row.
     */
    private static List<CoOwnerShare> parseCoOwners(@Nullable List<OwnershipCorrectionCoOwner> requested,
            List<Ownership> rows, Ownership target, List<FieldProblem> invalid) {
        List<CoOwnerShare> coOwners = new ArrayList<>();
        if (requested == null) {
            return coOwners;
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < requested.size(); i++) {
            OwnershipCorrectionCoOwner row = requested.get(i);
            Share share = parseShare(row.getShare(), "coOwnerShares[" + i + "].share", invalid);
            Ownership found = rows.stream().filter(o -> o.getId().equals(row.getOwnershipId())).findFirst()
                    .orElse(null);
            if (found == null || found == target) {
                invalid.add(new FieldProblem("coOwnerShares[" + i + "].ownershipId",
                        ProblemCodes.OWNERSHIP_NOT_FOUND));
            } else if (!seen.add(found.getId())) {
                invalid.add(new FieldProblem("coOwnerShares[" + i + "].ownershipId", ProblemCodes.INVALID));
            } else if (share != null) {
                coOwners.add(new CoOwnerShare(i, found, share));
            }
        }
        return coOwners;
    }

    /**
     * Locks the member the record will belong to. Unknown is a 400; an archived member is a 409 unless it is the
     * record's own member, so a historical record of a member archived later can still be corrected.
     */
    private MemberRef lockCorrectionMember(UUID memberId, Ownership target) {
        MemberRef member = members.lockActive(List.of(memberId)).get(memberId);
        if (member == null) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", List.of(new FieldProblem("memberId", ProblemCodes.MEMBER_NOT_FOUND)));
        }
        if (member.archived() && !memberId.equals(target.getMemberId())) {
            throw conflict(ProblemCodes.MEMBER_ARCHIVED, "An archived member cannot become an owner.",
                    List.of(new FieldProblem("memberId", ProblemCodes.MEMBER_ARCHIVED)));
        }
        return member;
    }

    /** Flushes the corrected rows; the exclusion constraint is the backstop of the timeline check. */
    private void saveCorrection(Collection<Ownership> changed) {
        try {
            ownerships.saveAllAndFlush(changed);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, OVERLAP_CONSTRAINT)) {
                throw overlapOn("validFrom");
            }
            throw e;
        }
    }

    /**
     * Maps the first violation of the corrected timeline to the field the person can fix. An overlap of one member is
     * reported on the start date when the target starts inside the other period, otherwise on the end date. A sum of
     * shares that is not one whole is reported on {@code share} unless the correction moved a date of the target and
     * that move causes the first failing day, see {@link #sharesProblem}.
     */
    private static ApiProblemException toCorrectionProblem(Violation violation, List<Period> timeline,
            Ownership old) {
        UUID targetId = old.getId();
        return switch (violation.kind()) {
            case SHARES_NOT_ONE -> sharesProblem(timeline, old);
            case MEMBER_OVERLAP -> overlapOn(overlapField(timeline, targetId));
            case STARTS_IN_FUTURE -> conflict(ProblemCodes.OWNERSHIP_DATE_INVALID, "The date cannot be in the future.",
                    List.of(new FieldProblem("validFrom", ProblemCodes.OWNERSHIP_DATE_INVALID)));
            case ENDS_IN_FUTURE -> conflict(ProblemCodes.OWNERSHIP_DATE_INVALID, "The date cannot be in the future.",
                    List.of(new FieldProblem("validTo", ProblemCodes.OWNERSHIP_DATE_INVALID)));
        };
    }

    /**
     * Attributes a sum of shares that is not one whole to the date that caused it. On the first failing day: if the
     * target now covers a day it did not cover before while other owners cover it too, the corrected period overlaps
     * them (OWNERSHIP_OVERLAP) on the start date when the day is before the old start, otherwise on the end date; if
     * the target covered the day before but no longer does, a date was moved away from it (SHARES_NOT_ONE) on the date
     * on that side. Everything else, including unchanged dates, stays on {@code share}.
     */
    private static ApiProblemException sharesProblem(List<Period> timeline, Ownership old) {
        Period target = timeline.stream().filter(p -> old.getId().equals(p.key())).findFirst().orElseThrow();
        Period before = new Period(old.getId(), old.getMemberId(), old.getShare(), old.getValidFrom(),
                old.getValidTo());
        LocalDate day = OwnershipRules.firstDayWithWrongShares(timeline).orElse(null);
        if (day != null && !before.coversDay(day) && target.coversDay(day)
                && timeline.stream().anyMatch(p -> p != target && p.coversDay(day))) {
            return overlapOn(day.isBefore(old.getValidFrom()) ? "validFrom" : "validTo");
        }
        if (day != null && before.coversDay(day) && !target.coversDay(day)) {
            String field = day.isBefore(target.validFrom()) ? "validFrom" : "validTo";
            return conflict(ProblemCodes.SHARES_NOT_ONE, "The shares must add up to 1.",
                    List.of(new FieldProblem(field, ProblemCodes.SHARES_NOT_ONE)));
        }
        return conflict(ProblemCodes.SHARES_NOT_ONE, "The shares must add up to 1.",
                List.of(new FieldProblem("share", ProblemCodes.SHARES_NOT_ONE)));
    }

    private static String overlapField(List<Period> timeline, UUID targetId) {
        Period target = timeline.stream().filter(p -> targetId.equals(p.key())).findFirst().orElseThrow();
        boolean startsInsideAnother = timeline.stream()
                .filter(p -> p != target && p.memberId().equals(target.memberId()))
                .anyMatch(p -> p.coversDay(target.validFrom()));
        return startsInsideAnother ? "validFrom" : "validTo";
    }

    private static ApiProblemException overlapOn(String field) {
        return conflict(ProblemCodes.OWNERSHIP_OVERLAP, "A member cannot own the plot in overlapping periods.",
                List.of(new FieldProblem(field, ProblemCodes.OWNERSHIP_OVERLAP)));
    }

    private void publishCorrection(AccessProfile actor, Plot plot, @Nullable String oldOwners,
            @Nullable String newOwners, @Nullable String oldBasis, @Nullable String newBasis, String reason) {
        List<FieldChange> changes = new ArrayList<>();
        if (!Objects.equals(oldOwners, newOwners)) {
            changes.add(new FieldChange("owners", oldOwners, newOwners));
        }
        if (!Objects.equals(oldBasis, newBasis)) {
            changes.add(new FieldChange("basis", oldBasis, newBasis));
        }
        changes.add(new FieldChange("reason", null, reason));
        events.publishEvent(AuditEvent.now(
                actor.tenantId(),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()),
                "OWNERSHIP_CORRECTED",
                "OWNERSHIP",
                plot.getId(),
                plot.getNumber(),
                changes));
    }

    /** "Last First 1/2 (2020-01-01–2026-02-28)", the period open at the end while current. Never logged. */
    private static String text(MemberRef member, Ownership o) {
        return text(member, o.getShare()) + " (" + o.getValidFrom() + "\u2013"
                + (o.getValidTo() == null ? "" : o.getValidTo()) + ")";
    }

    /** Parses every share; every invalid one is reported at once as a 400. */
    private static List<NewOwner> parseOwners(List<OwnershipChangeOwner> requested) {
        List<NewOwner> owners = new ArrayList<>();
        List<FieldProblem> invalid = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            OwnershipChangeOwner row = requested.get(i);
            try {
                owners.add(new NewOwner(i, row.getMemberId(), Share.parse(row.getShare())));
            } catch (InvalidShareException e) {
                invalid.add(new FieldProblem("owners[" + i + "].share", ProblemCodes.SHARE_INVALID, e.getMessage()));
            }
        }
        if (!invalid.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", invalid);
        }
        return owners;
    }

    /** A member listed twice is refused on the second row. */
    private static void requireDistinctMembers(List<NewOwner> owners) {
        Set<UUID> seen = new HashSet<>();
        for (NewOwner owner : owners) {
            if (!seen.add(owner.memberId())) {
                throw conflict(ProblemCodes.DUPLICATE_OWNER, "A member can be listed only once.",
                        List.of(memberField(owner.index(), ProblemCodes.DUPLICATE_OWNER)));
            }
        }
    }

    /**
     * Locks the listed members and refuses unknown ones (400, which also covers members of other cooperatives, hidden
     * by RLS) and archived ones (409), each on its own row.
     */
    private Map<UUID, MemberRef> lockMembers(List<NewOwner> owners) {
        Map<UUID, MemberRef> found = members.lockActive(owners.stream().map(NewOwner::memberId).distinct().toList());
        List<FieldProblem> unknown = owners.stream()
                .filter(o -> !found.containsKey(o.memberId()))
                .map(o -> memberField(o.index(), ProblemCodes.MEMBER_NOT_FOUND))
                .toList();
        if (!unknown.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED,
                    "The request is not valid.", unknown);
        }
        List<FieldProblem> archived = owners.stream()
                .filter(o -> found.get(o.memberId()).archived())
                .map(o -> memberField(o.index(), ProblemCodes.MEMBER_ARCHIVED))
                .toList();
        if (!archived.isEmpty()) {
            throw conflict(ProblemCodes.MEMBER_ARCHIVED, "An archived member cannot become an owner.", archived);
        }
        return found;
    }

    /**
     * Closes the current rows first and flushes, then inserts the new ones: the exclusion constraint is checked per
     * statement, and Hibernate would otherwise run the inserts before the updates, so a member who stays an owner
     * (old row to D-1, new row from D) would meet their own old row. The constraint is the backstop of the timeline
     * check; a violation of it is the same 409 as an overlap found in memory.
     */
    private void saveChange(List<Ownership> current, LocalDate lastDay, Instant now, List<Ownership> added) {
        try {
            current.forEach(o -> o.endOn(lastDay, now));
            ownerships.flush();
            ownerships.saveAllAndFlush(added);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, OVERLAP_CONSTRAINT)) {
                throw overlap();
            }
            throw e;
        }
    }

    /** Keeps the cached status in step with the owners, in the same transaction. */
    private void refreshStatus(Plot plot, boolean hasCurrentOwners, Instant now) {
        PlotStatusEntry entry = statuses.findById(plot.getId())
                .orElseGet(() -> statuses.save(PlotStatusEntry.initial(plot.getId(), plot.getTenantId())));
        PlotStatusCode after = PlotStatusPolicy.afterOwnershipChange(hasCurrentOwners, entry.getStatus());
        if (after != entry.getStatus()) {
            entry.changeStatus(after, now);
            statuses.saveAndFlush(entry);
        }
    }

    private void publish(AccessProfile actor, Plot plot, LocalDate effectiveFrom, @Nullable String previousOwners,
            @Nullable String newOwners, @Nullable String basis) {
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange("effectiveFrom", null, effectiveFrom.toString()));
        if (previousOwners != null || newOwners != null) {
            changes.add(new FieldChange("owners", previousOwners, newOwners));
        }
        if (basis != null) {
            changes.add(new FieldChange("basis", null, basis));
        }
        events.publishEvent(AuditEvent.now(
                actor.tenantId(),
                new Actor(actor.memberId(), actor.subject(), actor.displayName()),
                "OWNERSHIP_CHANGED",
                "OWNERSHIP",
                plot.getId(),
                plot.getNumber(),
                changes));
    }

    private static Period periodOf(Ownership o) {
        return new Period(o.getId(), o.getMemberId(), o.getShare(), o.getValidFrom(), o.getValidTo());
    }

    /** "Last First 1/2": the audit text of one owner. Personal data: never logged. */
    private static String text(MemberRef member, Share share) {
        return member.shortName() + " " + share;
    }

    /** The owners sorted and joined by ", "; null when there are none. */
    private static @Nullable String ownersText(Stream<String> owners) {
        List<String> sorted = owners.sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo)).toList();
        return sorted.isEmpty() ? null : String.join(", ", sorted);
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static ApiProblemException toProblem(Violation violation) {
        return switch (violation.kind()) {
            case SHARES_NOT_ONE -> conflict(ProblemCodes.SHARES_NOT_ONE, "The shares must add up to 1.",
                    List.of(new FieldProblem("owners", ProblemCodes.SHARES_NOT_ONE)));
            case MEMBER_OVERLAP -> overlap();
            case STARTS_IN_FUTURE, ENDS_IN_FUTURE -> dateInvalid(DateProblem.IN_FUTURE);
        };
    }

    private static ApiProblemException dateInvalid(DateProblem problem) {
        String detail = switch (problem) {
            case IN_FUTURE -> "The date cannot be in the future.";
            case NOT_AFTER_CURRENT_START -> "The date must be later than the start date of every current owner.";
            case NOT_AFTER_PAST_END -> "The date must be later than the end date of every past owner.";
        };
        return conflict(ProblemCodes.OWNERSHIP_DATE_INVALID, detail,
                List.of(new FieldProblem("effectiveFrom", ProblemCodes.OWNERSHIP_DATE_INVALID)));
    }

    private static ApiProblemException overlap() {
        return conflict(ProblemCodes.OWNERSHIP_OVERLAP, "A member cannot own the plot in overlapping periods.",
                List.of(new FieldProblem("owners", ProblemCodes.OWNERSHIP_OVERLAP)));
    }

    private static FieldProblem memberField(int index, String code) {
        return new FieldProblem("owners[" + index + "].memberId", code);
    }

    private static ApiProblemException conflict(String code, String detail, List<FieldProblem> errors) {
        return new ApiProblemException(HttpStatus.CONFLICT, code, detail, errors);
    }

    private static boolean violates(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && constraint.equals(cve.getConstraintName())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains(constraint)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
