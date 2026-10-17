package app.nadel.members.internal;

import java.util.UUID;

/** One (role, permission) pair as stored. */
record RolePermissionRow(UUID roleId, String permission) {
}
