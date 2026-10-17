package app.nadel.members.internal;

import java.util.UUID;

/** The number of active members who hold a role. */
record RoleMemberCount(UUID roleId, long count) {
}
