/**
 * Excel import of plots, members and ownership: the downloadable template and the upload with a preview of the Plots and
 * Members sheets today; the Ownership sheet, commit and history in later slices. The module has no public API; all endpoints
 * ({@code /imports/...}) are internal. It reads and (in the commit slice) writes other modules only through their
 * public facades ({@link app.nadel.plots.PlotImport}, {@link app.nadel.members.MemberImport}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Excel import")
package app.nadel.imports;
