package app.nadel.plots.internal;

import java.math.BigDecimal;

/** A plot with its cached status (the status may be missing). */
record PlotRow(Plot plot, PlotStatusCode status, BigDecimal debt) {
}
