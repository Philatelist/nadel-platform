package app.nadel.settings;

/** Who sees the debt status (colour) of a plot. People allowed to view plots always see every status. */
public enum PlotStatusVisibility {
    /** A member sees the status of the plots they own only. The default for every cooperative. */
    OWN_ONLY,
    /** A member sees the status of every plot, but never amounts, owners or personal data of other plots. */
    ALL_PLOTS
}
