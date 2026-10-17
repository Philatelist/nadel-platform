package app.nadel.identity;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * SPI implemented by the module that owns members. {@code identity} calls it on every request to turn a validated
 * token into an {@link AccessProfile}; this keeps the dependency one-way ({@code members} depends on
 * {@code identity}, never the opposite).
 */
public interface AccessProfileSource {

    /**
     * Finds the active member behind a token of the given cooperative.
     * <ol>
     *   <li>the active member already linked to {@code subject};</li>
     *   <li>otherwise, only when {@code emailVerified}, the active member with this e-mail (case-insensitive) that
     *       is not linked to anyone yet; the member is then linked to {@code subject};</li>
     *   <li>a member linked to another subject is never relinked; archived members are never returned.</li>
     * </ol>
     * {@code email} is null when the token has none (then only step 1 applies). The caller has bound the cooperative with {@code TenantContext.runAs}.
     *
     * @return the profile, or empty when the token belongs to no active member (the person is "not linked")
     */
    Optional<AccessProfile> resolve(UUID tenantId, String subject, @Nullable String email,
            boolean emailVerified);
}
