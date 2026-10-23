/**
 * What the cooperative charges for and how much: charge types (the five it starts with and custom ones), tariffs
 * with dated versions and the plots they apply to (all plots minus exclusions, or selected plots). Tables:
 * {@code charge_type}, {@code tariff}, {@code tariff_version} and {@code tariff_scope_plot} (V10), all under RLS
 * {@code tenant_isolation}.
 * <p>
 * Public API: {@link app.nadel.tariffs.Basis}, {@link app.nadel.tariffs.Frequency},
 * {@link app.nadel.tariffs.VersionTerms} (the terms of one tariff version and the one-off rule) and the calculation
 * {@link app.nadel.tariffs.ChargeCalculator} with its result {@link app.nadel.tariffs.ExpectedCharge}, and the port
 * {@link app.nadel.tariffs.TariffVersionUsage} that the ledger implements so that a version a charge uses is no longer
 * corrected or deleted, the read API {@link app.nadel.tariffs.ChargeTypeCatalog} (facts about charge types for the
 * ledger), and for charging (feature 011) the read API {@link app.nadel.tariffs.TariffBook} (the tariffs with scopes
 * and versions, and their shared lock), the pure {@link app.nadel.tariffs.TariffResolver} (which tariff charges a plot,
 * or a conflict) and {@link app.nadel.tariffs.DaySet} (the days a tariff may charge), and for recalculations (feature
 * 012) the facade {@link app.nadel.tariffs.TariffVersionChanges} (a version added, corrected or deleted in the ledger's
 * transaction, without the use check). The endpoints of tags
 * {@code charge-types} and {@code tariffs} are internal. It depends on {@code identity}, {@code audit},
 * {@code problem}, {@code money}, {@code plots} (the public {@code PlotCatalog}) and {@code meters} (the public
 * {@code MeterCatalog}: a tariff charged by meter reaches only the plots with a meter of its resource).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Charge types and tariffs")
package app.nadel.tariffs;
