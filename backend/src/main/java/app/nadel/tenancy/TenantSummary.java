package app.nadel.tenancy;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A cooperative as other modules see it.
 *
 * @param edrpou the ЄДРПОУ code, set by the platform operator; null when not set
 */
public record TenantSummary(UUID id, String name, @Nullable String edrpou) {
}
