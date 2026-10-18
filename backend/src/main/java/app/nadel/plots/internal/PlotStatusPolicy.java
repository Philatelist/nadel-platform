package app.nadel.plots.internal;

import org.jspecify.annotations.Nullable;

/** Which status a plot shows. Rules: docs/domain/money-rules.md, section 9. */
final class PlotStatusPolicy {

    private PlotStatusPolicy() {
    }

    /**
     * Common land is always GREY, and so is a plot without a cached status row (a plot without an owner has none
     * until ownership and the ledger exist); every other plot shows its cached status.
     */
    static PlotStatusCode statusOf(PlotKind kind, @Nullable PlotStatusCode cached) {
        if (kind == PlotKind.COMMON || cached == null) {
            return PlotStatusCode.GREY;
        }
        return cached;
    }
}
