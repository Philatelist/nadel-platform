package app.nadel.tariffs;

import java.util.UUID;

/**
 * Tells whether a charge was produced from a tariff version. A version can be corrected or deleted only while nothing
 * uses it; after that a correction goes through recalculation (money rules, section 4).
 * <p>
 * This is a port: the tariffs module defines it and never depends on the module that knows the answer. The ledger
 * (feature 011) depends on tariffs, so it provides a Spring bean implementing this interface and the tariffs module
 * finds it by type. Every implementation is asked and the version counts as used when any says so; the bundled default
 * ({@code NoChargesYetUsage}) always answers {@code false} and stays harmless when a real implementation arrives.
 * <p>
 * The method runs inside the caller's tenant-aware transaction, with the tariff row locked {@code FOR UPDATE}, so an
 * implementation may query its own tables (under row level security) without opening a transaction of its own. To
 * make the check race-free, whatever creates charges from a version must take a lock on the tariff row that conflicts
 * with that one, or write its charge in a transaction that the correction's lock waits for.
 */
public interface TariffVersionUsage {

    /**
     * @param tenantId  the cooperative of the version (always the caller's own)
     * @param versionId the tariff version
     * @return {@code true} when at least one charge was produced from the version
     */
    boolean isUsed(UUID tenantId, UUID versionId);
}
