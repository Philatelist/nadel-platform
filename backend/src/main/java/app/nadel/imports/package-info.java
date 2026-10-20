/**
 * Excel import of plots, members and ownership: the downloadable template and the upload with a preview of the Plots
 * sheet today; the other sheets, commit and history in later slices. The module has no public API; all endpoints
 * ({@code /imports/...}) are internal. It reads and (in the commit slice) writes other modules only through their
 * public facades such as {@link app.nadel.plots.PlotImport}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Excel import")
package app.nadel.imports;
