/**
 * Excel import of plots, members and ownership: the downloadable template, the upload with a preview of all three
 * sheets and the commit; the history comes in a later slice. The module has no public API; all endpoints
 * ({@code /imports/...}) are internal. It reads and writes other modules only through their public facades
 * ({@link app.nadel.plots.PlotImport}, {@link app.nadel.members.MemberImport}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Excel import")
package app.nadel.imports;
