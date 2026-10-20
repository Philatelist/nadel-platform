package app.nadel.plots.internal;

import app.nadel.audit.Actor;
import app.nadel.audit.AuditEvent;
import app.nadel.audit.FieldChange;
import app.nadel.members.MemberRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The core of recording new owners of a plot, shared by the change-of-owner endpoint ({@link OwnershipCommands}) and
 * the Excel import: the rows that start on the effective date, the cached status that follows the owners, and the
 * {@code OWNERSHIP_CHANGED} audit event with its texts. It decides nothing about locking, flushing or error mapping:
 * the endpoint flushes row by row to map constraint violations to its 409s, the import persists thousands of rows in
 * batches and maps a violation to a stale import.
 */
@Component
class OwnershipRecorder {

    /** A member and the share they start with. */
    record Owner(UUID memberId, Share share) {
    }

    private final ApplicationEventPublisher events;

    OwnershipRecorder(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** The rows that start on {@code from}: one current owner per entry. Nothing is saved. */
    List<Ownership> start(UUID tenantId, UUID plotId, List<Owner> owners, LocalDate from, @Nullable String basis,
            Instant now) {
        return owners.stream()
                .map(o -> Ownership.start(tenantId, plotId, o.memberId(), o.share(), from, basis, now))
                .toList();
    }

    /**
     * Brings the cached status in step with the owners ({@link PlotStatusPolicy#afterOwnershipChange}). Returns true
     * when the status changed, so the caller saves the entry.
     */
    boolean refreshStatus(PlotStatusEntry entry, boolean hasCurrentOwners, Instant now) {
        PlotStatusCode after = PlotStatusPolicy.afterOwnershipChange(hasCurrentOwners, entry.getStatus());
        if (after == entry.getStatus()) {
            return false;
        }
        entry.changeStatus(after, now);
        return true;
    }

    void publishChange(UUID tenantId, Actor actor, Plot plot, LocalDate effectiveFrom, @Nullable String previousOwners,
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
                tenantId,
                actor,
                "OWNERSHIP_CHANGED",
                "OWNERSHIP",
                plot.getId(),
                plot.getNumber(),
                changes));
    }

    /** "Last First 1/2": the audit text of one owner. Personal data: never logged. */
    static String text(MemberRef member, Share share) {
        return member.shortName() + " " + share;
    }

    /** The owners sorted and joined by ", "; null when there are none. */
    static @Nullable String ownersText(Stream<String> owners) {
        List<String> sorted = owners.sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo)).toList();
        return sorted.isEmpty() ? null : String.join(", ", sorted);
    }
}
