/**
 * Excel import of plots, members, ownership and plot meters: the downloadable template, the upload with a preview of
 * all four sheets, the commit and the history of the uploads. The module has no public API; all endpoints
 * ({@code /imports/...}) are internal. It reads and writes other modules only through their public facades
 * ({@link app.nadel.plots.PlotImport}, {@link app.nadel.members.MemberImport}, {@link app.nadel.meters.MeterImport}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Excel import")
package app.nadel.imports;
