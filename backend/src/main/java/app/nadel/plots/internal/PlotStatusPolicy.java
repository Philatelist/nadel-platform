package app.nadel.plots.internal;

import app.nadel.money.Money;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which status (colour) a plot shows. Rules: docs/domain/money-rules.md, section 9. Pure: no Spring, no database, no
 * clock; the caller passes the debt, the thresholds and today.
 */
final class PlotStatusPolicy {

    /**
     * The red threshold of the cooperative (settings {@code redAmount}, {@code redDays}).
     *
     * @param redAmount a debt above this amount is red
     * @param redDays   a debt whose oldest unpaid charge is older than this many days is red
     */
    record Thresholds(Money redAmount, int redDays) {

        Thresholds {
            Objects.requireNonNull(redAmount, "redAmount");
            if (redDays < 0) {
                throw new IllegalArgumentException("redDays is not negative");
            }
        }
    }

    /** What every status read needs besides the plot: the thresholds and today, read once per request. */
    record Basis(Thresholds thresholds, LocalDate today) {

        Basis {
            Objects.requireNonNull(thresholds, "thresholds");
            Objects.requireNonNull(today, "today");
        }
    }

    private PlotStatusPolicy() {
    }

    /**
     * The status of a plot today. GREY for common land and for a plot without a current owner; RED when the debt is
     * above the red amount, or when there is a debt and its oldest unpaid charge is more than the red days old (today
     * minus that date); YELLOW for any other debt; GREEN without a debt (zero or less), also with a prepayment.
     *
     * @param debt           the cached debt of the plot after payments (what is open on its charges)
     * @param oldestDebtDate the day the oldest unpaid charge counts from; null when nothing is unpaid
     */
    static PlotStatusCode statusOf(PlotKind kind, boolean hasOwner, Money debt, @Nullable LocalDate oldestDebtDate,
            Thresholds thresholds, LocalDate today) {
        if (kind == PlotKind.COMMON || !hasOwner) {
            return PlotStatusCode.GREY;
        }
        if (debt.signum() <= 0) {
            return PlotStatusCode.GREEN;
        }
        boolean aboveAmount = debt.minus(thresholds.redAmount()).signum() > 0;
        boolean tooOld = oldestDebtDate != null
                && ChronoUnit.DAYS.between(oldestDebtDate, today) > thresholds.redDays();
        return aboveAmount || tooOld ? PlotStatusCode.RED : PlotStatusCode.YELLOW;
    }

    /** {@link #statusOf(PlotKind, boolean, Money, LocalDate, Thresholds, LocalDate)} with the basis of a request. */
    static PlotStatusCode statusOf(PlotKind kind, boolean hasOwner, Money debt, @Nullable LocalDate oldestDebtDate,
            Basis basis) {
        return statusOf(kind, hasOwner, debt, oldestDebtDate, basis.thresholds(), basis.today());
    }

    /**
     * The stored {@code plot_status.status} after the owners of a plot changed. The column is a snapshot that this
     * release no longer reads (every read derives the status with {@link #statusOf}); it is kept for the previous
     * release during a rolling update. A plot that has current owners and is GREY becomes GREEN; a plot without current
     * owners becomes GREY; any other status of a plot with owners (RED, YELLOW, GREEN) is kept until the next debt
     * refresh rewrites it.
     */
    static PlotStatusCode afterOwnershipChange(boolean hasCurrentOwners, PlotStatusCode cached) {
        if (!hasCurrentOwners) {
            return PlotStatusCode.GREY;
        }
        return cached == PlotStatusCode.GREY ? PlotStatusCode.GREEN : cached;
    }
}
