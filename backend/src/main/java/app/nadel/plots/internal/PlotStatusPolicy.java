package app.nadel.plots.internal;

import org.jspecify.annotations.Nullable;

/** Which status a plot shows. Rules: docs/domain/money-rules.md, section 9. */
final class PlotStatusPolicy {

    private PlotStatusPolicy() {
    }

    /**
     * Common land is always GREY, and so is a plot without a cached status row; every other plot shows its cached
     * status (GREEN while it has a current owner and no debt, GREY without an owner, later RED or YELLOW from the
     * ledger).
     */
    static PlotStatusCode statusOf(PlotKind kind, @Nullable PlotStatusCode cached) {
        if (kind == PlotKind.COMMON || cached == null) {
            return PlotStatusCode.GREY;
        }
        return cached;
    }

    /**
     * The cached status after the owners of a plot changed. Until debts exist (the ledger rebuilds the cache later): a
     * plot that has current owners and is GREY becomes GREEN; a plot without current owners becomes GREY; any other
     * status of a plot with owners (RED, YELLOW, GREEN) is kept.
     */
    static PlotStatusCode afterOwnershipChange(boolean hasCurrentOwners, PlotStatusCode cached) {
        if (!hasCurrentOwners) {
            return PlotStatusCode.GREY;
        }
        return cached == PlotStatusCode.GREY ? PlotStatusCode.GREEN : cached;
    }
}
