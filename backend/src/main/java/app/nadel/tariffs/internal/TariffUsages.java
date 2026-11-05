package app.nadel.tariffs.internal;

import app.nadel.tariffs.TariffUsage;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Asks every {@link TariffUsage} bean about whole tariffs (feature 020) and joins their answers: a tariff counts as
 * charged when any says so, and its last charged day is the latest any knows. The version ids each answer needs are
 * read here, softly deleted versions included. Runs in the caller's transaction.
 */
@Component
class TariffUsages {

    private final TariffVersionRepository versions;
    private final List<TariffUsage> usages;

    TariffUsages(TariffVersionRepository versions, List<TariffUsage> usages) {
        this.versions = versions;
        this.usages = usages;
    }

    /** The tariffs among {@code tariffIds} that a charge was ever made from (the opposite of {@code deletable}). */
    Set<UUID> everCharged(UUID tenantId, Collection<UUID> tariffIds) {
        if (tariffIds.isEmpty()) {
            return Set.of();
        }
        Map<UUID, Set<UUID>> versionsByTariff = allVersions(tariffIds);
        Set<UUID> charged = new HashSet<>();
        for (TariffUsage usage : usages) {
            charged.addAll(usage.everCharged(tenantId, versionsByTariff));
        }
        return charged;
    }

    /** Whether a charge was ever made from the tariff. */
    boolean everCharged(UUID tenantId, UUID tariffId) {
        return everCharged(tenantId, List.of(tariffId)).contains(tariffId);
    }

    /** The last service day of the unreversed charges made from the tariff; empty when there is none. */
    Optional<LocalDate> lastChargedDay(UUID tenantId, UUID tariffId) {
        Set<UUID> versionIds = allVersions(List.of(tariffId)).get(tariffId);
        return usages.stream().map(usage -> usage.lastChargedDay(tenantId, tariffId, versionIds))
                .flatMap(Optional::stream).max(LocalDate::compareTo);
    }

    /** Every version id of each tariff, softly deleted ones included; a tariff without versions maps to an empty set. */
    private Map<UUID, Set<UUID>> allVersions(Collection<UUID> tariffIds) {
        Map<UUID, Set<UUID>> byTariff = new HashMap<>();
        tariffIds.forEach(id -> byTariff.put(id, new HashSet<>()));
        for (Object[] row : versions.findAllVersionIdsOf(Set.copyOf(tariffIds))) {
            byTariff.computeIfAbsent((UUID) row[0], id -> new HashSet<>()).add((UUID) row[1]);
        }
        return byTariff;
    }
}
