package app.nadel.audit;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who made a change. {@code displayName} is personal data: it travels with the event but is never logged.
 *
 * @param memberId    the member behind the token; null when the change was not made by a member
 * @param subject     Keycloak user id
 * @param displayName name of the actor at the time of the change
 */
public record Actor(@Nullable UUID memberId, String subject, String displayName) {
}
