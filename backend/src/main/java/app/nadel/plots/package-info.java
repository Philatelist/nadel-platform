/**
 * Plots and common land as cooperative records (number, street, kind, area, cadastral number, building flag, notes,
 * soft archive), their list and card, creation, editing, archiving, restoring and deletion, the owners of plots with
 * their shares and history (feature 007), and the debt status (color) shown on the map. Polygon import and the map
 * view come with the map phase. Member names come from {@link app.nadel.members.MemberDirectory}. Other modules read
 * plot facts (number, kind, archived, area) through {@link app.nadel.plots.PlotCatalog}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Plots and map")
package app.nadel.plots;
