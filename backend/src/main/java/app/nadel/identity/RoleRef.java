package app.nadel.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A role a member holds.
 *
 * @param baseCode {@code CHAIRMAN}, {@code TREASURER} or {@code MEMBER} for base roles, null for custom roles
 */
public record RoleRef(UUID id, String name, @Nullable String baseCode) {
}
