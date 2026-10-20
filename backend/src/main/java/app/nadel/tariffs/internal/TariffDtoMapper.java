package app.nadel.tariffs.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import nadel.contract.model.Basis;
import nadel.contract.model.Frequency;
import nadel.contract.model.Tariff;
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
     * scope lists and the plot count come ready from the caller; the count is null for a meter-based tariff.
     */
    static Tariff toDto(TariffRecord tariff, ChargeTypeRecord chargeType, List<TariffVersionRecord> versionsOldestFirst,
            LocalDate today, List<TariffScopePlot> selectedPlots, List<TariffExclusion> exclusions,
            @Nullable Long plotCount) {
        var dto = new Tariff(tariff.getId(), tariff.getName(), ChargeTypeDtoMapper.toDto(chargeType),
                scopeKind(tariff), versionsNewestFirst(versionsOldestFirst, today), selectedPlots, exclusions);
        dto.setPlotCount(plotCount);
        return dto;
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

    /** Whether the version is paid by meter readings; such a tariff applies to the plots with a meter. */
    static boolean meterBased(TariffVersionRecord version) {
        return version.getBasis() == app.nadel.tariffs.Basis.PER_KWH
                || version.getBasis() == app.nadel.tariffs.Basis.PER_M3;
    }

    static TariffScopeKind scopeKind(TariffRecord tariff) {
        return TariffScopeKind.fromValue(tariff.getScopeKind().name());
    }

    /**
     * The versions newest first. A version applies until the day before the next version starts, so its end is derived
     * from the list and never stored.
     */
    static List<TariffVersion> versionsNewestFirst(List<TariffVersionRecord> oldestFirst, LocalDate today) {
        List<TariffVersion> result = new ArrayList<>(oldestFirst.size());
        for (int i = 0; i < oldestFirst.size(); i++) {
            LocalDate validTo = i + 1 < oldestFirst.size() ? oldestFirst.get(i + 1).getValidFrom().minusDays(1) : null;
            result.add(version(oldestFirst.get(i), validTo, today));
        }
        Collections.reverse(result);
        return result;
    }

    static TariffVersion version(TariffVersionRecord version, LocalDate validTo, LocalDate today) {
        var dto = new TariffVersion(version.getId(), version.getValidFrom(),
                Basis.fromValue(version.getBasis().name()), Frequency.fromValue(version.getFrequency().name()),
                version.getRate().toString(), version.getValidFrom().isBefore(today));
        dto.setValidTo(validTo);
        dto.setDueDate(version.getDueDate());
        dto.setNote(version.getNote());
        return dto;
    }
}
