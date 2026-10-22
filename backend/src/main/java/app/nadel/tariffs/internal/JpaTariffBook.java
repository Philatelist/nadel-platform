package app.nadel.tariffs.internal;

import app.nadel.tariffs.TariffBook;
import app.nadel.tariffs.VersionTerms;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TariffBook} over the tariff tables; RLS limits it to the caller's cooperative. The snapshot is four queries
 * (charge types, tariffs, versions, scope rows), whatever the number of tariffs.
 */
@Component
class JpaTariffBook implements TariffBook {

    private final ChargeTypeRepository chargeTypes;
    private final TariffRepository tariffs;
    private final TariffVersionRepository versions;
    private final TariffScopePlotRepository scopePlots;

    JpaTariffBook(ChargeTypeRepository chargeTypes, TariffRepository tariffs, TariffVersionRepository versions,
            TariffScopePlotRepository scopePlots) {
        this.chargeTypes = chargeTypes;
        this.tariffs = tariffs;
        this.versions = versions;
        this.scopePlots = scopePlots;
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot snapshot() {
        List<ChargeTypeEntry> types = chargeTypes.findAll().stream()
                .sorted(Comparator.comparingInt(ChargeTypeRecord::getPosition).thenComparing(ChargeTypeRecord::getId))
                .map(type -> new ChargeTypeEntry(type.getId(), type.getCode(), type.isActive(),
                        type.getArchivedAt() != null))
                .toList();
        List<TariffRecord> all = tariffs.findAll();
        if (all.isEmpty()) {
            return new Snapshot(types, List.of());
        }
        List<UUID> ids = all.stream().map(TariffRecord::getId).toList();
        Map<UUID, List<VersionRef>> versionsByTariff = versions.findByTariffIdInOrderByTariffIdAscValidFromAsc(ids)
                .stream().collect(Collectors.groupingBy(TariffVersionRecord::getTariffId,
                        Collectors.mapping(JpaTariffBook::ref, Collectors.toList())));
        Map<UUID, List<TariffScopePlotRecord>> scopeByTariff = scopePlots.findOf(ids).stream()
                .collect(Collectors.groupingBy(TariffScopePlotRecord::getTariffId));
        List<TariffEntry> entries = all.stream()
                .sorted(Comparator.comparing((TariffRecord t) -> t.getName().toLowerCase(Locale.ROOT))
                        .thenComparing(TariffRecord::getId))
                .map(tariff -> {
                    List<TariffScopePlotRecord> rows = scopeByTariff.getOrDefault(tariff.getId(), List.of());
                    return new TariffEntry(tariff.getId(), tariff.getChargeTypeId(), tariff.getName(),
                            Scope.valueOf(tariff.getScopeKind().name()),
                            plots(rows, TariffScopePlotRecord.Kind.SELECTED),
                            plots(rows, TariffScopePlotRecord.Kind.EXCLUDED),
                            versionsByTariff.getOrDefault(tariff.getId(), List.of()));
                })
                .toList();
        return new Snapshot(types, entries);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> tariffIds() {
        return tariffs.findAllIds();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockShared(Collection<UUID> tariffIds) {
        if (!tariffIds.isEmpty()) {
            tariffs.lockAllForShare(new HashSet<>(tariffIds));
        }
    }

    private static Set<UUID> plots(List<TariffScopePlotRecord> rows, TariffScopePlotRecord.Kind kind) {
        return rows.stream().filter(row -> row.getKind() == kind).map(TariffScopePlotRecord::getPlotId)
                .collect(Collectors.toSet());
    }

    private static VersionRef ref(TariffVersionRecord version) {
        return new VersionRef(version.getId(), new VersionTerms(version.getValidFrom(), version.getBasis(),
                version.getFrequency(), version.getRate(), version.getDueDate()));
    }
}
