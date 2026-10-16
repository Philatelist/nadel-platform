package app.nadel.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The current cooperative. Taken from the {@code tenant_id} JWT claim, or bound explicitly
 * with {@link #runAs(UUID, Supplier)} for background jobs (period closing, notifications).
 * <p>
 * The value is passed to the database at the start of every transaction and limits row visibility through RLS.
 * Never take tenant_id from a client request.
 */
public final class TenantContext {

    public static final String TENANT_CLAIM = "tenant_id";

    private static final ThreadLocal<UUID> OVERRIDE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> currentTenantId() {
        UUID override = OVERRIDE.get();
        if (override != null) {
            return Optional.of(override);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String claim = jwtAuth.getToken().getClaimAsString(TENANT_CLAIM);
            if (claim != null && !claim.isBlank()) {
                return Optional.of(UUID.fromString(claim));
            }
        }
        return Optional.empty();
    }

    /** Runs an action on behalf of a cooperative. For background jobs that have no user token. */
    public static <T> T runAs(UUID tenantId, Supplier<T> action) {
        UUID previous = OVERRIDE.get();
        OVERRIDE.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }
}
