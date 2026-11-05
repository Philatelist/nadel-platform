package app.nadel.tariffs.internal;

import app.nadel.meters.MeterResource;
import app.nadel.problem.ApiProblemException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.Tariff;
import nadel.contract.model.TariffConflict;
import nadel.contract.model.TariffListStatus;
import nadel.contract.model.TariffSummary;
import nadel.contract.model.TariffVersion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads tariffs. The cooperative is the one of the transaction (RLS); nothing is taken from the request. */
@Service
class TariffQueries {

    private final TariffRepository tariffs;
    private final TariffVersionRepository versions;
    private final ChargeTypeRepository chargeTypes;
    private final TariffAssembler assembler;
    private final TariffConflicts conflicts;
    private final TariffUsages usages;
    private final Clock clock;

    TariffQueries(TariffRepository tariffs, TariffVersionRepository versions, ChargeTypeRepository chargeTypes,
            TariffAssembler assembler, TariffConflicts conflicts, TariffUsages usages, Clock clock) {
        this.tariffs = tariffs;
        this.versions = versions;
        this.chargeTypes = chargeTypes;
        this.assembler = assembler;
        this.conflicts = conflicts;
        this.usages = usages;
        this.clock = clock;
    }

    /**
     * The tariffs of the non-archived charge types in list order, each with the version valid today (Kyiv) or else the
     * earliest future one, and the number of plots it applies to (for a version charged by meter, the plots with an
     * active meter of its resource), its conflicts with other tariffs of the charge type ({@link TariffConflicts}),
     * {@code deletable} (no charge was ever made from it; one batched question through {@link TariffUsages}), the
     * numbers of the plots in its scope and, for an archived tariff, {@code validUntil} and {@code archivedAt}.
     *
     * @param status       {@code ACTIVE} the tariffs that are not archived ({@code archived_at} null), {@code ARCHIVED}
     *                     the archived ones; null means {@code ACTIVE}
     * @param chargeTypeId only the tariffs of this charge type; null for all (an unknown or foreign id gives none)
     */
    @Transactional(readOnly = true)
    List<TariffSummary> list(@Nullable TariffListStatus status, @Nullable UUID chargeTypeId) {
        LocalDate today = LocalDate.now(clock);
        boolean archived = status == TariffListStatus.ARCHIVED;
        List<TariffRecord> all = tariffs.findOfActiveChargeTypes().stream()
                .filter(tariff -> tariff.isArchived() == archived)
                .filter(tariff -> chargeTypeId == null || chargeTypeId.equals(tariff.getChargeTypeId()))
                .toList();
        if (all.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = all.stream().map(TariffRecord::getId).toList();
        Map<UUID, List<TariffVersionRecord>> byTariff = versions.findByTariffIdInOrderByTariffIdAscValidFromAsc(ids)
                .stream().collect(Collectors.groupingBy(TariffVersionRecord::getTariffId));
        Map<TariffRecord, @Nullable MeterResource> meterOf = new LinkedHashMap<>();
        Map<UUID, Integer> shownIndex = new HashMap<>();
        for (TariffRecord tariff : all) {
            List<TariffVersionRecord> oldestFirst = byTariff.getOrDefault(tariff.getId(), List.of());
            if (!oldestFirst.isEmpty()) { // a tariff is created with its first version, so it always has one
                int shown = TariffDtoMapper.shownIndex(oldestFirst, today);
                shownIndex.put(tariff.getId(), shown);
                meterOf.put(tariff, MeterBasis.resourceOf(oldestFirst.get(shown).getBasis()));
            }
        }
        Map<UUID, Long> counts = assembler.plotCounts(meterOf);
        Map<UUID, List<String>> numbers = assembler.plotNumbers(meterOf.keySet());
        Map<UUID, List<TariffConflict>> conflictsOf = conflicts.of(ids);
        // RLS shows the caller's cooperative only, so every tariff of the list belongs to it
        Set<UUID> charged = usages.everCharged(all.getFirst().getTenantId(), ids);

        List<TariffSummary> result = new ArrayList<>(all.size());
        for (TariffRecord tariff : meterOf.keySet()) {
            List<TariffVersionRecord> oldestFirst = byTariff.get(tariff.getId());
            int shown = shownIndex.get(tariff.getId());
            TariffVersionRecord version = oldestFirst.get(shown);
            TariffVersion dto = TariffDtoMapper.version(version,
                    TariffDtoMapper.validTo(oldestFirst, shown, tariff.getValidUntil()), today);
            var summary = new TariffSummary(tariff.getId(), tariff.getName(), tariff.getChargeTypeId(),
                    TariffDtoMapper.scopeKind(tariff), dto, conflictsOf.get(tariff.getId()),
                    !charged.contains(tariff.getId()), numbers.get(tariff.getId()));
            summary.setPlotCount(counts.get(tariff.getId()));
            summary.setValidUntil(tariff.getValidUntil());
            summary.setArchivedAt(TariffDtoMapper.archivedAt(tariff));
            result.add(summary);
        }
        return result;
    }

    /** The tariff with its charge type (also an archived one) and every version, newest first. */
    @Transactional(readOnly = true)
    Tariff get(UUID id) {
        TariffRecord tariff = tariffs.findById(id).orElseThrow(ApiProblemException::notFound);
        ChargeTypeRecord chargeType = chargeTypes.findById(tariff.getChargeTypeId())
                .orElseThrow(ApiProblemException::notFound);
        return assembler.assemble(tariff, chargeType);
    }
}
