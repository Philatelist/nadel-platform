package app.nadel.tariffs;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The tariffs of the cooperative as other modules need them to charge (the ledger's month run, feature 011), so they
 * never touch the tariff tables. The caller has bound the cooperative (a transaction of the tenant-aware transaction
 * manager): tariffs of other cooperatives are invisible.
 */
public interface TariffBook {

    /** Which plots a tariff applies to. */
    enum Scope {
        /** Every active plot that is not common land, minus the excluded ones. */
        ALL_PLOTS,
        /** The selected plots only. */
        SELECTED_PLOTS
    }

    /**
     * A charge type as charging needs it.
     *
     * @param code     the code of a seeded type; null for a custom type
     * @param active   whether the type is active
     * @param archived whether the type is archived
     */
    record ChargeTypeEntry(UUID id, @Nullable String code, boolean active, boolean archived) {

        /** Whether its tariffs charge anything: active and not archived. */
        public boolean charges() {
            return active && !archived;
        }
    }

    /** A version of a tariff with its id. */
    record VersionRef(UUID id, VersionTerms terms) {

        public VersionRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(terms, "terms");
        }
    }

    /**
     * A tariff with its scope and every version.
     *
     * @param name          user content
     * @param selectedPlots the selected plots ({@link Scope#SELECTED_PLOTS}); empty otherwise
     * @param excludedPlots the excluded plots ({@link Scope#ALL_PLOTS}); empty otherwise
     * @param versions      every version, oldest first
     */
    record TariffEntry(UUID id, UUID chargeTypeId, String name, Scope scope, Set<UUID> selectedPlots,
            Set<UUID> excludedPlots, List<VersionRef> versions) {

        public TariffEntry {
            selectedPlots = Set.copyOf(selectedPlots);
            excludedPlots = Set.copyOf(excludedPlots);
            versions = versions.stream().sorted(Comparator.comparing(v -> v.terms().validFrom())).toList();
        }

        /** The terms of every version, oldest first (the input of {@link ChargeCalculator}). */
        public List<VersionTerms> terms() {
            return versions.stream().map(VersionRef::terms).toList();
        }

        /** The day the first version starts; versions never end, so the tariff applies from then on. */
        public Optional<LocalDate> firstDay() {
            return versions.stream().map(v -> v.terms().validFrom()).findFirst();
        }

        /** Whether the plot is in the scope (selected, or not excluded from all plots). */
        public boolean covers(UUID plotId) {
            return scope == Scope.ALL_PLOTS ? !excludedPlots.contains(plotId) : selectedPlots.contains(plotId);
        }

        /** The id of the version that starts on {@code validFrom} (two versions of a tariff never start together). */
        public Optional<UUID> versionStartingOn(LocalDate validFrom) {
            return versions.stream().filter(v -> v.terms().validFrom().equals(validFrom)).map(VersionRef::id)
                    .findFirst();
        }
    }

    /**
     * Every charge type and every tariff of the cooperative.
     *
     * @param chargeTypes every charge type, archived ones included, in list order
     * @param tariffs     every tariff, ordered by name (ignoring case), then id
     */
    record Snapshot(List<ChargeTypeEntry> chargeTypes, List<TariffEntry> tariffs) {

        public Snapshot {
            chargeTypes = List.copyOf(chargeTypes);
            tariffs = List.copyOf(tariffs);
        }

        public Optional<ChargeTypeEntry> chargeType(UUID id) {
            return chargeTypes.stream().filter(type -> type.id().equals(id)).findFirst();
        }

        public Optional<TariffEntry> tariff(UUID id) {
            return tariffs.stream().filter(tariff -> tariff.id().equals(id)).findFirst();
        }

        /** The tariffs of the charge type, ordered by name. */
        public List<TariffEntry> tariffsOf(UUID chargeTypeId) {
            return tariffs.stream().filter(tariff -> tariff.chargeTypeId().equals(chargeTypeId)).toList();
        }
    }

    /** The committed charge types and tariffs of the cooperative, read in the caller's transaction. */
    Snapshot snapshot();

    /** The ids of every tariff of the cooperative. */
    List<UUID> tariffIds();

    /**
     * Locks the tariffs {@code FOR SHARE} until the end of the caller's transaction, in id order (so two callers never
     * wait for each other in a circle). A correction or deletion of a version, or a change of scope, locks its tariff
     * {@code FOR UPDATE} and so waits for a transaction that charges from it, and then sees the charges
     * ({@link TariffVersionUsage}). Ids that do not exist in the cooperative are ignored. Must run inside the caller's
     * transaction.
     */
    void lockShared(Collection<UUID> tariffIds);
}
