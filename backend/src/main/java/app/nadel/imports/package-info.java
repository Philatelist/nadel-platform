/**
 * Excel import of plots, members and ownership: the downloadable template, the upload with a preview of all three
 * sheets, the commit and the history of the uploads. The module has no public API; all endpoints
 * ({@code /imports/...}) are internal. It reads and writes other modules only through their public facades
 * ({@link app.nadel.plots.PlotImport}, {@link app.nadel.members.MemberImport}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Excel import")
package app.nadel.imports;
