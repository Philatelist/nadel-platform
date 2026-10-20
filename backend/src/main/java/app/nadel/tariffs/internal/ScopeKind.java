package app.nadel.tariffs.internal;

/** Which plots a tariff applies to: every chargeable plot (minus exclusions) or the selected plots. */
enum ScopeKind {
    ALL_PLOTS,
    SELECTED_PLOTS
}
