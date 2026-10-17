package app.nadel.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A role a member holds.
 *
 * @param baseCode {@code CHAIRMAN}, {@code TREASURER} or {@code MEMBER} for base roles, null for custom roles
 * @param nameCustomized true for custom roles and for base roles the cooperative renamed; false for a base role with
 *                       its standard name, which clients show from their own catalog
 */
public record RoleRef(UUID id, String name, @Nullable String baseCode, boolean nameCustomized) {
}
