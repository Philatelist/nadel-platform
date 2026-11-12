package app.nadel.members;

import app.nadel.identity.Permission;
import java.util.List;
import java.util.UUID;

/**
 * The members who hold a base role now, for other modules (feature 016: the chairman's and the treasurer's names on an
 * invoice), and the members who hold a permission now (feature 021: who is told that readings wait for approval), so
 * they never read the role tables. Runs in the caller's transaction ({@code Propagation.MANDATORY}),
 * which the tenant-aware transaction manager has bound to the cooperative; RLS hides other cooperatives. The answer
 * carries names (personal data): the caller seals what it stores and never logs it.
 */
public interface RoleHolders {

    /**
     * The active (not archived) members holding the base role with the code ({@code CHAIRMAN}, {@code TREASURER},
     * {@code MEMBER}) through a role of the cooperative, in name order (last, first, middle name, then id). A custom
     * role never counts, even with the same permissions. An unknown code gives an empty list.
     */
    List<MemberRef> activeHolders(String baseRoleCode);

    /**
     * The ids of the active (not archived) members who hold the permission now through any of their roles of the
     * cooperative: a role whose stored permissions include it, base or custom, and always the Chairman base role, which
     * holds every permission whatever rows are stored (ADR 0009). Each member once, in id order. Ids only: no personal
     * data.
     */
    List<UUID> activeWithPermission(Permission permission);
}
