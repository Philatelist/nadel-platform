/**
 * Excel import of plots (with their water connection), members, ownership, plot meters and opening balances: the
 * downloadable template, the upload with a preview of all five sheets, the commit and the history of the uploads. The
 * module has no public API; all endpoints ({@code /imports/...}) are internal. It reads and writes other modules only
 * through their public facades ({@link app.nadel.plots.PlotImport}, {@link app.nadel.members.MemberImport},
 * {@link app.nadel.meters.MeterImport}, {@link app.nadel.meters.WaterConnectionImport},
 * {@link app.nadel.ledger.OpeningBalanceImport}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Excel import")
package app.nadel.imports;
