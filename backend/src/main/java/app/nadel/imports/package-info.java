/**
 * Excel import of plots, members and ownership: the downloadable template today, upload, preview, commit and history
 * in later slices. The module has no public API; all endpoints ({@code /imports/...}) are internal. Writes to other
 * modules will go through their public facades.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Excel import")
package app.nadel.imports;
