/**
 * Settings of a cooperative that the chairman (or any role with {@code SETTINGS_MANAGE}) can change, kept in one row
 * per cooperative. Today: who sees the debt status of plots ({@link app.nadel.settings.PlotStatusVisibility}).
 * <p>
 * Public API: {@link app.nadel.settings.CooperativeSettings} and {@link app.nadel.settings.PlotStatusVisibility}.
 * {@code GET /settings} and {@code PUT /settings} are internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Cooperative settings")
package app.nadel.settings;
