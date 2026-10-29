package app.nadel.members;

import java.util.List;

/**
 * The members who hold a base role now, for other modules (feature 016: the chairman's and the treasurer's names on an
 * invoice), so they never read the role tables. Runs in the caller's transaction ({@code Propagation.MANDATORY}),
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
}
