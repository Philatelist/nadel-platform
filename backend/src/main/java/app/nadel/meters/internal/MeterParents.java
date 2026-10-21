package app.nadel.meters.internal;

import app.nadel.meters.MeterKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Writes the parent periods of the electricity tree ({@code meter_parent}): a move of a meter to another parent from a
 * date, and the place of a replaced meter that the new meter takes from the replacement day (spec 010, section 2.2).
 * Runs in the caller's transaction.
 * <p>
 * Lock order: a parent before its children. A replacement holds the old meter {@code FOR UPDATE} and locks the meters
 * under it after it, in id order, before their periods are read; a move holds the new parent {@code FOR SHARE} and then
 * the moved meter {@code FOR UPDATE}. Nobody can put a new meter under the old one meanwhile: that needs a lock on it.
 * <p>
 * The periods of one meter never overlap ({@code meter_parent_no_overlap_ex}): rows that end or go away are flushed
 * before new rows are inserted.
 */
@Component
class MeterParents {

    private final MeterParentRepository parents;
    private final MeterRepository meters;

    @PersistenceContext
    private EntityManager entityManager;

    MeterParents(MeterParentRepository parents, MeterRepository meters) {
        this.parents = parents;
        this.meters = meters;
    }

    /** The outcome of a move: the parent the meter had on the effective date before it, and whether anything changed. */
    record Move(@Nullable UUID previousParent, boolean changed) {
    }

    /** The parent periods of the meter, oldest first. */
    List<MeterParentRecord> periodsOf(UUID meterId) {
        return parents.findByMeterId(meterId).stream()
                .sorted(Comparator.comparing(MeterParentRecord::getValidFrom))
                .toList();
    }

    /**
     * The day of the meter's latest change in the tree: the start of its latest period, or the day after it ended when
     * the meter was taken out of the tree then. Empty for a meter that never had a parent.
     */
    Optional<LocalDate> latestChange(UUID meterId) {
        List<MeterParentRecord> rows = periodsOf(meterId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(latestChange(rows.getLast()));
    }

    /** Whether any meter was ever under the meter; its periods then refer to it. */
    boolean wasEverParent(UUID meterId) {
        return parents.existsByParentMeterId(meterId);
    }

    /** Whether a meter that still works after {@code day} is under the meter after that day. */
    boolean hasChildrenAfter(UUID meterId, LocalDate day) {
        return parents.hasChildrenAfter(meterId, day);
    }

    /**
     * Puts {@code meter} under {@code parentId} from {@code from}, or out of the tree from that day when
     * {@code parentId} is null. The caller holds the locks, has checked the rules and that {@code from} is not before
     * the meter's {@link #latestChange}.
     * <ul>
     * <li>No change when the meter already has that parent (or none) on {@code from}.</li>
     * <li>On the start day of the latest period the change corrects that period: it takes the new parent, or is
     * dropped when the meter leaves the tree, or joins the period before it when that one had the new parent and ended
     * the day before.</li>
     * <li>On the day after a period that ended (the meter was taken out of the tree then) a return to the same parent
     * lets that period last again.</li>
     * <li>Otherwise a lasting period ends on the day before and a new period starts on {@code from}.</li>
     * </ul>
     */
    Move move(MeterRecord meter, @Nullable UUID parentId, LocalDate from, Instant now) {
        List<MeterParentRecord> rows = periodsOf(meter.getId());
        MeterParentRecord latest = rows.isEmpty() ? null : rows.getLast();
        if (latest != null && latestChange(latest).isAfter(from)) {
            throw new IllegalStateException("A move is not dated before the meter's latest change in the tree");
        }
        boolean lasting = latest != null && latest.getValidTo() == null;
        UUID previousParent = lasting ? latest.getParentMeterId() : null;
        if (Objects.equals(previousParent, parentId)) {
            return new Move(previousParent, false);
        }
        MeterParentRecord created = null;
        if (lasting && latest.getValidFrom().equals(from)) {
            MeterParentRecord before = rows.size() > 1 ? rows.get(rows.size() - 2) : null;
            boolean joinsBefore = parentId != null && before != null && parentId.equals(before.getParentMeterId())
                    && before.getValidTo() != null && before.getValidTo().plusDays(1).equals(from);
            if (parentId == null || joinsBefore) {
                parents.delete(latest);
                entityManager.flush();
                if (joinsBefore) {
                    before.reopen(now);
                }
            } else {
                latest.reparent(parentId, now);
            }
        } else if (latest != null && !lasting && parentId != null && parentId.equals(latest.getParentMeterId())
                && latest.getValidTo().plusDays(1).equals(from)) {
            latest.reopen(now);
        } else {
            if (lasting) {
                latest.endOn(from.minusDays(1), now);
            }
            if (parentId != null) {
                created = MeterParentRecord.of(meter.getTenantId(), meter.getId(), parentId, from, null, now);
            }
        }
        entityManager.flush();
        if (created != null) {
            entityManager.persist(created);
            entityManager.flush();
        }
        return new Move(previousParent, true);
    }

    private static LocalDate latestChange(MeterParentRecord latest) {
        return latest.getValidTo() == null ? latest.getValidFrom() : latest.getValidTo().plusDays(1);
    }

    /**
     * Moves the place of {@code old} in the tree to {@code replacement} from {@code day}, the day {@code old} was removed
     * and {@code replacement} installed.
     * <ul>
     * <li>The parent of {@code old}: a period that reaches {@code day} ends on {@code day} (the old meter worked that
     * day) and continues for {@code replacement} from {@code day} to where it ended; a period that starts after
     * {@code day} belongs to {@code replacement} alone.</li>
     * <li>The meters under {@code old}, only when it is not a plot meter: a period that started before {@code day} ends on
     * the day before and continues under {@code replacement} from {@code day} (one parent at a time); a period that
     * starts on or after {@code day} is put under {@code replacement}.</li>
     * </ul>
     * Earlier periods keep their parent, so a past date range shows the tree as it was.
     */
    void takeOver(MeterRecord old, MeterRecord replacement, LocalDate day, Instant now) {
        List<MeterParentRecord> created = new ArrayList<>();
        for (MeterParentRecord row : parents.findByMeterId(old.getId())) {
            if (!row.reaches(day)) {
                continue;
            }
            if (row.getValidFrom().isAfter(day)) {
                created.add(MeterParentRecord.of(old.getTenantId(), replacement.getId(), row.getParentMeterId(),
                        row.getValidFrom(), row.getValidTo(), now));
                parents.delete(row);
            } else {
                created.add(MeterParentRecord.of(old.getTenantId(), replacement.getId(), row.getParentMeterId(), day,
                        row.getValidTo(), now));
                row.endOn(day, now);
            }
        }
        if (old.getKind() != MeterKind.PLOT) {
            List<UUID> children = parents.childIdsOf(old.getId());
            if (!children.isEmpty()) {
                meters.findAllByIdForUpdate(children);
                for (MeterParentRecord row : parents.findByParentMeterId(old.getId())) {
                    if (!row.reaches(day)) {
                        continue;
                    }
                    if (row.getValidFrom().isBefore(day)) {
                        created.add(MeterParentRecord.of(old.getTenantId(), row.getMeterId(), replacement.getId(), day,
                                row.getValidTo(), now));
                        row.endOn(day.minusDays(1), now);
                    } else {
                        row.reparent(replacement.getId(), now);
                    }
                }
            }
        }
        // the ended periods first: a child's new period would overlap its old one (meter_parent_no_overlap_ex)
        entityManager.flush();
        created.forEach(entityManager::persist);
        entityManager.flush();
    }
}
