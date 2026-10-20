/**
 * What the cooperative charges for and how much: charge types (the four it starts with and custom ones), later
 * tariffs with dated versions and the plots they apply to. Tables: {@code charge_type}, {@code tariff},
 * {@code tariff_version} and {@code tariff_scope_plot} (V10), all under RLS {@code tenant_isolation}.
 * <p>
 * Public API: {@link app.nadel.tariffs.Basis}, {@link app.nadel.tariffs.Frequency} and
 * {@link app.nadel.tariffs.VersionTerms} (the terms of one tariff version and the one-off rule). The endpoints of tags
 * {@code charge-types} and {@code tariffs} are internal. It depends on {@code identity}, {@code audit},
 * {@code problem}, {@code money} and {@code plots} (the public {@code PlotCatalog}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Charge types and tariffs")
package app.nadel.tariffs;
