package app.nadel.tenancy;

import java.util.Optional;

/** Cooperative information for other modules. */
public interface TenantDirectory {

    /** Cooperative of the current user or job. Empty for the platform operator. */
    Optional<TenantSummary> current();
}
