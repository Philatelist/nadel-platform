package app.nadel.tariffs.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import nadel.contract.model.Basis;
import nadel.contract.model.Frequency;
import nadel.contract.model.Tariff;
import nadel.contract.model.TariffConflict;
import nadel.contract.model.TariffExclusion;
import nadel.contract.model.TariffScopeKind;
import nadel.contract.model.TariffScopePlot;
import nadel.contract.model.TariffVersion;
import org.jspecify.annotations.Nullable;

/** Turns tariffs and their versions into API output. */
final class TariffDtoMapper {

    private TariffDtoMapper() {
    }

    /**
     * The tariff with all versions, newest first; {@code versionsOldestFirst} is every version of the tariff. The
     * scope lists, the plot count, the conflicts and whether it may be deleted (no charge was ever made from it) come
     * ready from the caller.
     */
    static Tariff toDto(TariffRecord tariff, ChargeTypeRecord chargeType, List<TariffVersionRecord> versionsOldestFirst,
            LocalDate today, List<TariffScopePlot> selectedPlots, List<TariffExclusion> exclusions,
            @Nullable Long plotCount, List<TariffConflict> conflicts, boolean deletable) {
        var dto = new Tariff(tariff.getId(), tariff.getName(), ChargeTypeDtoMapper.toDto(chargeType),
                scopeKind(tariff), versionsNewestFirst(versionsOldestFirst, tariff.getValidUntil(), today),
                selectedPlots, exclusions, conflicts, deletable);
        dto.setPlotCount(plotCount);
        dto.setValidUntil(tariff.getValidUntil());
        dto.setArchivedAt(archivedAt(tariff));
        return dto;
    }

    /** When the tariff was archived, in UTC; null for an active tariff. */
    static @Nullable OffsetDateTime archivedAt(TariffRecord tariff) {
        Instant archivedAt = tariff.getArchivedAt();
        return archivedAt == null ? null : archivedAt.atOffset(ZoneOffset.UTC);
    }

    /**
     * The last day of the version at {@code index}: the day before the next version starts; for the last version the
     * tariff's {@code validUntil} (null while the tariff is active).
     */
    static @Nullable LocalDate validTo(List<TariffVersionRecord> oldestFirst, int index,
            @Nullable LocalDate validUntil) {
        return index + 1 < oldestFirst.size() ? oldestFirst.get(index + 1).getValidFrom().minusDays(1) : validUntil;
    }

    /** Index of the version valid on {@code today}, else of the earliest later one. {@code oldestFirst} is not empty. */
    static int shownIndex(List<TariffVersionRecord> oldestFirst, LocalDate today) {
        int current = -1;
        for (int i = 0; i < oldestFirst.size(); i++) {
            if (!oldestFirst.get(i).getValidFrom().isAfter(today)) {
                current = i;
            }
        }
        return current >= 0 ? current : 0;
    }

    static TariffScopeKind scopeKind(TariffRecord tariff) {
        return TariffScopeKind.fromValue(tariff.getScopeKind().name());
    }

    /**
     * The versions newest first. A version applies until the day before the next version starts, so its end is derived
     * from the list and never stored; the last one ends on {@code validUntil} of an archived tariff (null: no end).
     */
    static List<TariffVersion> versionsNewestFirst(List<TariffVersionRecord> oldestFirst,
            @Nullable LocalDate validUntil, LocalDate today) {
        List<TariffVersion> result = new ArrayList<>(oldestFirst.size());
        for (int i = 0; i < oldestFirst.size(); i++) {
            result.add(version(oldestFirst.get(i), validTo(oldestFirst, i, validUntil), today));
        }
        Collections.reverse(result);
        return result;
    }

    static TariffVersion version(TariffVersionRecord version, @Nullable LocalDate validTo, LocalDate today) {
        var dto = new TariffVersion(version.getId(), version.getValidFrom(),
                Basis.fromValue(version.getBasis().name()), Frequency.fromValue(version.getFrequency().name()),
                version.getRate().toString(), version.getValidFrom().isBefore(today));
        dto.setValidTo(validTo);
        dto.setDueDate(version.getDueDate());
        dto.setNote(version.getNote());
        return dto;
    }
}
