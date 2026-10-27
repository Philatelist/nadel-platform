/**
 * Plots and common land as cooperative records (number, street, kind, area, cadastral number, building flag, notes,
 * soft archive), their list and card, creation, editing, archiving, restoring and deletion, the owners of plots with
 * their shares and history (feature 007), and the debt status (color) shown on the map. Polygon import and the map
 * view come with the map phase. Member names come from {@link app.nadel.members.MemberDirectory}. Other modules read
 * plot facts (number, street, kind, archived, area) through {@link app.nadel.plots.PlotCatalog} and may veto deleting,
 * archiving or turning a plot into common land through the SPI {@link app.nadel.plots.PlotChangeGuard} (ADR 0011).
 * The ledger keeps the debt behind the status through {@link app.nadel.plots.PlotDebtCache}; the colour is derived
 * from it, the red threshold of the settings and today whenever it is read. The staff reports read the current owners
 * through {@link app.nadel.plots.PlotOwners} (ids only) and the status through {@link app.nadel.plots.PlotStatuses}
 * ({@link app.nadel.plots.PlotStatus}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Plots and map")
package app.nadel.plots;
