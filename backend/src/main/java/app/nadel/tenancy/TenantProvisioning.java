package app.nadel.tenancy;

import java.util.UUID;

/**
 * Makes a cooperative exist (feature 020: the first-chairman command). There is no operator console yet; this is the
 * only application code that creates a {@code tenant} row.
 */
public interface TenantProvisioning {

    /**
     * Makes sure the cooperative {@code id} exists: when it does not, inserts it with {@code name} and seeds its base
     * roles, charge types and treasury accounts ({@code seed_base_roles}) in the same transaction, so a cooperative
     * never exists without them. An existing cooperative is left exactly as it is (its name included, and its roles:
     * seeding again would give back permissions the board removed from the Treasurer role). Runs in its own
     * transaction with the cooperative bound ({@code TenantContext.runAs}), as RLS requires.
     *
     * @return whether the cooperative was created now
     */
    boolean ensure(UUID id, String name);
}
