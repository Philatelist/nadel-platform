package app.nadel.ledger.internal;

import app.nadel.tariffs.TariffUsage;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's answer to the tariffs port {@link TariffUsage} (feature 020), from its own tables only: it never reads
 * the tariff tables, the caller passes the version ids. A tariff was charged when a {@code charge} row names it or a
 * {@code charge_part} is priced by one of its versions, reversed charges and reversal rows included (the ledger is
 * append-only, so nothing is ever forgotten). The last charged day counts only the regular charges that no reversal
 * cancels.
 * <p>
 * Race-free by the port's lock contract: the caller holds the tariff {@code FOR UPDATE} while it asks, and every path
 * that writes a charge from a tariff holds it {@code FOR SHARE} until it commits. Runs in the caller's transaction,
 * under RLS.
 */
@Component
class LedgerTariffUsage implements TariffUsage {

    private final ChargeRepository charges;
    private final ChargePartRepository parts;

    LedgerTariffUsage(ChargeRepository charges, ChargePartRepository parts) {
        this.charges = charges;
        this.parts = parts;
    }

    /**
     * Two batched queries: the tariffs a charge names, then, for the tariffs not yet found, the versions a charge part
     * is priced by, mapped back to their tariffs.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Set<UUID> everCharged(UUID tenantId, Map<UUID, Set<UUID>> versionsByTariff) {
        if (versionsByTariff.isEmpty()) {
            return Set.of();
        }
        Set<UUID> charged = new HashSet<>(charges.tariffIdsWithCharges(versionsByTariff.keySet()));
        Map<UUID, UUID> tariffOfVersion = new HashMap<>();
        versionsByTariff.forEach((tariffId, versionIds) -> {
            if (!charged.contains(tariffId)) {
                versionIds.forEach(versionId -> tariffOfVersion.put(versionId, tariffId));
            }
        });
        if (!tariffOfVersion.isEmpty()) {
            parts.versionIdsWithParts(tariffOfVersion.keySet()).forEach(versionId -> {
                UUID tariffId = tariffOfVersion.get(versionId);
                if (tariffId != null) {
                    charged.add(tariffId);
                }
            });
        }
        return charged;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<LocalDate> lastChargedDay(UUID tenantId, UUID tariffId, Set<UUID> versionIds) {
        LocalDate byTariff = charges.lastUnreversedServiceDayOfTariff(tariffId, ChargeKind.REGULAR);
        LocalDate byVersions = versionIds.isEmpty() ? null
                : parts.lastUnreversedServiceDayOfVersions(versionIds, ChargeKind.REGULAR);
        return Optional.ofNullable(later(byTariff, byVersions));
    }

    private static @Nullable LocalDate later(@Nullable LocalDate a, @Nullable LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || !b.isAfter(a) ? a : b;
    }
}
