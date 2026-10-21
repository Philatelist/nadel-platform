package app.nadel.meters.internal;

import app.nadel.meters.MeterKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the parent periods of the electricity tree ({@code meter_parent}) when a meter is replaced: the new meter takes
 * the place of the old one from the replacement day (spec 010, section 2.2). Runs in the caller's transaction.
 * <p>
 * Lock order: the caller holds the old meter {@code FOR UPDATE}; the meters under it are locked after it, in id order,
 * before their periods are read (a parent before its children, the order a move in the tree takes too). Nobody can put
 * a new meter under the old one meanwhile: that needs the old meter's lock.
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
