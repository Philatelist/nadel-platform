package app.nadel.members.internal;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One (role, permission) pair of a member; {@code permission} is null for a role without permissions. */
record RoleGrantRow(UUID roleId, String roleName, @Nullable String baseCode, @Nullable String permission) {
}
