package app.nadel.tariffs.internal;

import app.nadel.tariffs.internal.TariffScopePlotRecord.Kind;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The difference between the stored scope of a tariff and the scope that replaces it: which rows go, which are added,
 * and what changed for the audit log. Pure; it holds plot ids and reasons only.
 * <p>
 * A row that stays is kept as it is. An exclusion whose reason changed is a removal plus an addition (rows are never
 * edited), which is also how the audit log records it.
 */
final class TariffScopeChange {

    private final ScopeKind oldKind;
    private final ScopeKind newKind;
    private final Set<UUID> oldSelected = new HashSet<>();
    private final Map<UUID, String> oldExcluded = new HashMap<>();
    private final Set<UUID> newSelected;
    private final Map<UUID, String> newExcluded;

    TariffScopeChange(ScopeKind oldKind, List<TariffScopePlotRecord> storedRows, ScopeKind newKind,
            Set<UUID> newSelected, Map<UUID, String> newExcluded) {
        this.oldKind = oldKind;
        this.newKind = newKind;
        this.newSelected = newSelected;
        this.newExcluded = newExcluded;
        for (TariffScopePlotRecord row : storedRows) {
            if (row.getKind() == Kind.SELECTED) {
                oldSelected.add(row.getPlotId());
            } else {
                oldExcluded.put(row.getPlotId(), Objects.requireNonNull(row.getReason()));
            }
        }
    }

    boolean kindChanged() {
        return oldKind != newKind;
    }

    boolean selectedChanged() {
        return !oldSelected.equals(newSelected);
    }

    /** Whether the saved scope differs from the stored one in any way that is audited. */
    boolean isChange() {
        return kindChanged() || selectedChanged() || !removedExclusions().isEmpty() || !addedExclusions().isEmpty();
    }

    /** Whether the stored row stays as it is. */
    boolean keeps(TariffScopePlotRecord row) {
        return row.getKind() == Kind.SELECTED ? newSelected.contains(row.getPlotId())
                : Objects.equals(row.getReason(), newExcluded.get(row.getPlotId()));
    }

    /** The plots to select that were not selected before. */
    Set<UUID> addedSelected() {
        Set<UUID> added = new HashSet<>(newSelected);
        added.removeAll(oldSelected);
        return added;
    }

    Set<UUID> oldSelected() {
        return oldSelected;
    }

    Set<UUID> newSelected() {
        return newSelected;
    }

    ScopeKind oldKind() {
        return oldKind;
    }

    ScopeKind newKind() {
        return newKind;
    }

    /** Stored exclusions that are gone or have another reason now, by plot (the old reason). */
    Map<UUID, String> removedExclusions() {
        Map<UUID, String> removed = new HashMap<>();
        oldExcluded.forEach((plot, reason) -> {
            if (!reason.equals(newExcluded.get(plot))) {
                removed.put(plot, reason);
            }
        });
        return removed;
    }

    /** Exclusions that are new or have another reason than before, by plot (the new reason). */
    Map<UUID, String> addedExclusions() {
        Map<UUID, String> added = new HashMap<>();
        newExcluded.forEach((plot, reason) -> {
            if (!reason.equals(oldExcluded.get(plot))) {
                added.put(plot, reason);
            }
        });
        return added;
    }

    /** Every plot the change mentions, stored or new. */
    Set<UUID> plots() {
        Set<UUID> all = new HashSet<>(oldSelected);
        all.addAll(newSelected);
        all.addAll(oldExcluded.keySet());
        all.addAll(newExcluded.keySet());
        return all;
    }
}
