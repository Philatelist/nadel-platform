package app.nadel.tariffs.internal;

import app.nadel.problem.ApiProblemException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import nadel.contract.model.Tariff;
import nadel.contract.model.TariffSummary;
import nadel.contract.model.TariffVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads tariffs. The cooperative is the one of the transaction (RLS); nothing is taken from the request. */
@Service
class TariffQueries {

    private final TariffRepository tariffs;
    private final TariffVersionRepository versions;
    private final ChargeTypeRepository chargeTypes;
    private final TariffAssembler assembler;
    private final Clock clock;

    TariffQueries(TariffRepository tariffs, TariffVersionRepository versions, ChargeTypeRepository chargeTypes,
            TariffAssembler assembler, Clock clock) {
        this.tariffs = tariffs;
        this.versions = versions;
        this.chargeTypes = chargeTypes;
        this.assembler = assembler;
        this.clock = clock;
    }

    /**
     * The tariffs of the non-archived charge types in list order, each with the version valid today (Kyiv) or else the
     * earliest future one, and the number of plots it applies to.
     */
    @Transactional(readOnly = true)
    List<TariffSummary> list() {
        LocalDate today = LocalDate.now(clock);
        List<TariffRecord> all = tariffs.findOfActiveChargeTypes();
        if (all.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = all.stream().map(TariffRecord::getId).toList();
        Map<UUID, List<TariffVersionRecord>> byTariff = versions.findByTariffIdInOrderByTariffIdAscValidFromAsc(ids)
                .stream().collect(Collectors.groupingBy(TariffVersionRecord::getTariffId));
        Map<UUID, Long> counts = assembler.plotCounts(all);

        List<TariffSummary> result = new ArrayList<>(all.size());
        for (TariffRecord tariff : all) {
            List<TariffVersionRecord> oldestFirst = byTariff.getOrDefault(tariff.getId(), List.of());
            if (oldestFirst.isEmpty()) {
                continue; // a tariff is created with its first version, so this cannot happen
            }
            int shown = TariffDtoMapper.shownIndex(oldestFirst, today);
            TariffVersionRecord version = oldestFirst.get(shown);
            LocalDate validTo = shown + 1 < oldestFirst.size() ? oldestFirst.get(shown + 1).getValidFrom().minusDays(1)
                    : null;
            TariffVersion dto = TariffDtoMapper.version(version, validTo, today);
            var summary = new TariffSummary(tariff.getId(), tariff.getName(), tariff.getChargeTypeId(),
                    TariffDtoMapper.scopeKind(tariff), dto);
            if (!TariffDtoMapper.meterBased(version)) {
                summary.setPlotCount(counts.get(tariff.getId()));
            }
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
