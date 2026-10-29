package app.nadel.plots;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The current owners of plots for the staff reports of feature 013 (plot balances, debtors), so they never read the
 * ownership table. A current owner is an ownership row without an end date ({@code valid_to} null), as on the plot card
 * and for the plot status. Ids only, no names: a caller that may show names asks {@code members.MemberDirectory}. Both
 * methods run in the caller's transaction ({@code Propagation.MANDATORY}), which the tenant-aware transaction manager
 * has bound to the cooperative; RLS hides the rows of other cooperatives. Staff-only: the caller checks its own
 * permissions, nothing here narrows the answer to a member's own plots.
 */
public interface PlotOwners {

    /**
     * The number of current owners of each plot. Every distinct id is a key, in the order of first appearance; a plot
     * without current owners (or an id unknown in the cooperative) has 0.
     */
    Map<UUID, Integer> ownerCounts(Collection<UUID> plotIds);

    /**
     * The member ids of the current owners of each plot, the earliest owner first (by the start of the ownership, then
     * the member id). Every distinct id is a key, in the order of first appearance; a plot without current owners (or an
     * id unknown in the cooperative) has an empty list.
     */
    Map<UUID, List<UUID>> currentOwnerIds(Collection<UUID> plotIds);

    /**
     * A current owner of a plot with the share of the ownership, as stored ({@code share_numerator} /
     * {@code share_denominator}, never reduced here).
     *
     * @param memberId    the owner
     * @param numerator   the numerator of the share, at least 1
     * @param denominator the denominator of the share, at least the numerator
     */
    record OwnerShare(UUID memberId, int numerator, int denominator) {
    }

    /**
     * The current owners of each plot with their shares (the invoice of feature 016 prints them), in the order of
     * {@link #currentOwnerIds}: the earliest owner first. Every distinct id is a key, in the order of first appearance;
     * a plot without current owners (or an id unknown in the cooperative) has an empty list.
     */
    Map<UUID, List<OwnerShare>> currentOwnerShares(Collection<UUID> plotIds);
}
