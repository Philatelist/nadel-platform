package app.nadel.tariffs;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-only facts about charge types for other modules (the ledger), so they never touch the {@code charge_type}
 * table. The caller has bound the cooperative (a transaction of the tenant-aware transaction manager): charge types of
 * other cooperatives are invisible.
 */
public interface ChargeTypeCatalog {

    /**
     * What a module may know about a charge type.
     *
     * @param id             the charge type
     * @param code           the code of a seeded type ({@code MEMBERSHIP}, ..., {@code COMMON_CONSUMERS}); null for a
     *                       custom type
     * @param name           the stored name; while {@code nameCustomized} is false it is the English default and
     *                       clients show their own localised default name
     * @param nameCustomized true for custom types and for seeded types that were renamed
     * @param active         whether the type is active
     * @param archived       whether the type is archived
     * @param position       the place in the list; ties are ordered by id
     */
    record ChargeTypeFacts(UUID id, @Nullable String code, String name, boolean nameCustomized, boolean active,
            boolean archived, int position) {

        /**
         * The name as the audit log stores it: {@code base:<CODE>} while a seeded type keeps its default name,
         * otherwise the stored name (user content).
         */
        public String auditName() {
            return code != null && !nameCustomized ? "base:" + code : name;
        }
    }

    /** The charge type, archived or not; empty when it does not exist in the cooperative. */
    Optional<ChargeTypeFacts> find(UUID id);

    /**
     * The charge type, archived or not, with a shared row lock ({@code FOR SHARE}) until the end of the caller's
     * transaction: a write that depends on the type not being archived (an opening balance) runs after a concurrent
     * archive has committed, or the archive waits for it. Must run inside the caller's transaction.
     */
    Optional<ChargeTypeFacts> findAndLockShared(UUID id);

    /** Every charge type of the cooperative, archived ones included, in list order (position, then id). */
    List<ChargeTypeFacts> all();

    /**
     * The charge types each name a person typed stands for (the Excel import): a type has its stored name and, while a
     * seeded type is not renamed, also its default name in English and in Ukrainian. Names are compared ignoring letter
     * case, surrounding spaces and runs of spaces. Archived types are included (the caller decides about them); among
     * the types that are not archived a name normally stands for at most one, since their names are unique, defaults
     * included.
     *
     * @return the matching types of every given name, keyed by the name as given; a name without a match maps to an
     *         empty list
     */
    Map<String, List<ChargeTypeFacts>> findByNames(Collection<String> names);
}
