package app.nadel.settings;

/**
 * Settings of the current cooperative, read by other modules. The cooperative is the one bound to the current
 * transaction or request ({@code TenantContext}); a cooperative that never saved its settings has the defaults.
 */
public interface CooperativeSettings {

    /** Who sees the colour of plots that are not theirs; {@link PlotStatusVisibility#OWN_ONLY} when never saved. */
    PlotStatusVisibility memberPlotStatusVisibility();
}
