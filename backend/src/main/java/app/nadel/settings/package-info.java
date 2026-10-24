/**
 * Settings of a cooperative that the chairman (or any role with {@code SETTINGS_MANAGE}) can change, kept in one row
 * per cooperative: who sees the debt status of plots ({@link app.nadel.settings.PlotStatusVisibility}), the red
 * threshold of the plot status, how losses are charged ({@link app.nadel.settings.LossesMode}) and the default split of
 * common consumers ({@link app.nadel.settings.CommonConsumerSplit}) and the requisites of the cooperative
 * ({@link app.nadel.settings.Requisites}).
 * <p>
 * Public API: {@link app.nadel.settings.CooperativeSettings}, {@link app.nadel.settings.Requisites} and the three
 * enums. {@code GET /settings} and
 * {@code PUT /settings} are internal. Depends on {@code identity}, {@code audit}, {@code problem}, {@code tenancy} and
 * {@code money}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Cooperative settings")
package app.nadel.settings;
