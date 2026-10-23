package app.nadel.plots.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * A plot with what its status is derived from: the cached debt and the day its oldest unpaid charge counts from
 * ({@code plot_status}; both absent when the plot has no row), and whether it has a current owner.
 */
record PlotRow(Plot plot, @Nullable BigDecimal debt, @Nullable LocalDate oldestDebtDate, boolean hasOwner) {
}
