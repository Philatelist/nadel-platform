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
 * are no longer corrected or deleted (ADR 0014), the facade {@link app.nadel.meters.ReadingChanges} through which the
 * ledger's recalculation enters, corrects or deletes such a reading together with the recalculation (feature 012), the
 * read API {@link app.nadel.meters.MeterCatalog} (which plots have a
 * meter of a resource; the tariffs module asks it), the read API {@link app.nadel.meters.MeterNetwork} for the ledger
 * (the plot meters not read for a while, the locked points of a meter, the electricity tree with the histories of its
 * meters over a range), the event
 * {@link app.nadel.meters.PlotMeterPointsChanged} (the points of a plot meter changed; the ledger charges the intervals),
 * the read API {@link app.nadel.meters.WaterConnections} (the days plots are connected to water, for the ledger),
 * the port {@link app.nadel.meters.MemberReadings} (a member's meters, submission and the board's decision for the
 * cooperative's Telegram bot, feature 017) with the event {@link app.nadel.meters.ReadingDecided} (the board approved
 * or rejected a member's reading; ids only) and {@link app.nadel.meters.ReadingStatus},
 * the read facade of the reports {@link app.nadel.meters.MeterReports} (the tree of {@code GET /meter-tree} with the
 * children's sum, the losses and the plots fed, as {@link app.nadel.meters.MeterTreeReport} rows, the water use per
 * plot and source as {@link app.nadel.meters.WaterUseRow} and the meters without a reading in a range as
 * {@link app.nadel.meters.MissingReadingRow}; feature 013)
 * and the import facades {@link app.nadel.meters.MeterImport} with its
 * records (the Meters sheet of the Excel import) and {@link app.nadel.meters.WaterConnectionImport} (the water column of
 * the Plots sheet). The meter register (entity, commands, queries, the endpoints of tag
 * {@code meters} and the plot guards {@code MeterPlotGuard} and {@code WaterConnectionPlotGuard}, implementations of
 * {@link app.nadel.plots.PlotChangeGuard}),
 * the readings (entity, rules, commands, the round, the member's submission, approvals and the endpoints of tag
 * {@code readings}), replacement with the tree periods it moves, range consumption over a chain of meters and the water
 * connections (entity, commands, queries and the endpoints of tag {@code water}) are internal. It depends on {@code identity}, {@code audit}, {@code problem} and {@code plots} (the public
 * {@code PlotCatalog} and the import records {@code PlotImportRow}, {@code PlotImportPlan}). {@code tariffs},
 * {@code imports}, {@code ledger} and {@code reports} depend on it, never the other way round.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Meters and readings")
package app.nadel.meters;
