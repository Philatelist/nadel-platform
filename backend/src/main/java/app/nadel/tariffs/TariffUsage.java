package app.nadel.tariffs;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tells whether charges were made from tariffs as a whole (feature 020): a tariff no charge was ever made from may be
 * deleted, a used one is archived from a date after its last charged day.
 * <p>
 * A port like {@link TariffVersionUsage}: the tariffs module defines it and never depends on the module that knows the
 * answer. The ledger depends on tariffs and provides a Spring bean implementing it; the tariffs module asks every bean
 * of this type and joins their answers.
 * <p>
 * The methods run inside the caller's tenant-aware transaction, so an implementation may query its own tables (under
 * row level security) without opening a transaction of its own. A delete or an archive asks with the tariff row locked
 * {@code FOR UPDATE}; whatever creates charges from a tariff holds it {@code FOR SHARE} ({@link TariffBook#lockShared})
 * until it commits, so the answer cannot be overtaken by a charge being written.
 */
public interface TariffUsage {

    /**
     * The tariffs a charge was ever made from: a charge row that names the tariff ({@code charge.tariff_id}) or a
     * charge part priced by one of its versions, whatever happened to the charge since (a reversed charge and the
     * reversal row count too). One call answers for many tariffs (the list).
     *
     * @param tenantId          the cooperative of the tariffs (always the caller's own)
     * @param versionsByTariff  every version of each tariff asked for, by tariff id, softly deleted versions included
     * @return the ids among the keys of {@code versionsByTariff} that were charged from; empty when none
     */
    Set<UUID> everCharged(UUID tenantId, Map<UUID, Set<UUID>> versionsByTariff);

    /**
     * The last service day of the charges made from the tariff that no reversal cancels.
     *
     * @param tenantId   the cooperative of the tariff (always the caller's own)
     * @param tariffId   the tariff
     * @param versionIds every version of the tariff, softly deleted versions included
     * @return the latest {@code service_to} of such a charge; empty when there is none
     */
    Optional<LocalDate> lastChargedDay(UUID tenantId, UUID tariffId, Set<UUID> versionIds);
}
