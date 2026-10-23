/**
 * The cooperative's meters (electricity in kWh, water in m3), the dated tree of electricity meters, the readings taken on
 * any date and the consumption calculated from them, and the water connections of plots (feature 011). Tables:
 * {@code meter}, {@code meter_parent} and {@code meter_reading} (V12) and {@code plot_water_connection} (V16), all under
 * RLS {@code tenant_isolation}.
 * <p>
 * Public API: {@link app.nadel.meters.MeterResource}, {@link app.nadel.meters.MeterKind}, the quantities
 * {@link app.nadel.meters.MeterValue} (a reading, three decimals, never rounded) and {@link app.nadel.meters.Quantity}
 * (an exact fraction, rounded once for display), the calculation {@link app.nadel.meters.ConsumptionCalculator} with its
 * records, the port {@link app.nadel.meters.ReadingUsage} that the ledger implements so that readings a charge uses
 * are no longer corrected or deleted (ADR 0014), the read API {@link app.nadel.meters.MeterCatalog} (which plots have a
 * meter of a resource; the tariffs module asks it), the read API {@link app.nadel.meters.MeterNetwork} for the ledger
 * (the plot meters not read for a while, the locked points of a meter), the event
 * {@link app.nadel.meters.PlotMeterPointsChanged} (the points of a plot meter changed; the ledger charges the intervals),
 * the read API {@link app.nadel.meters.WaterConnections} (the days plots are connected to water, for the ledger)
 * and the import facade {@link app.nadel.meters.MeterImport} with its
 * records (the Meters sheet of the Excel import). The meter register (entity, commands, queries, the endpoints of tag
 * {@code meters} and the plot guard {@code MeterPlotGuard}, an implementation of {@link app.nadel.plots.PlotChangeGuard}),
 * the readings (entity, rules, commands, the round, the member's submission, approvals and the endpoints of tag
 * {@code readings}), replacement with the tree periods it moves, range consumption over a chain of meters and the water
 * connections (entity, commands, queries and the endpoints of tag {@code water}) are internal. It depends on {@code identity}, {@code audit}, {@code problem} and {@code plots} (the public
 * {@code PlotCatalog} and the import records {@code PlotImportRow}, {@code PlotImportPlan}). {@code tariffs},
 * {@code imports} and {@code ledger} depend on it, never the other way round.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Meters and readings")
package app.nadel.meters;
