/**
 * What the cooperative charges for and how much: charge types (the four it starts with and custom ones), later
 * tariffs with dated versions and the plots they apply to. Tables: {@code charge_type}, {@code tariff},
 * {@code tariff_version} and {@code tariff_scope_plot} (V10), all under RLS {@code tenant_isolation}.
 * <p>
 * Today the module has no public API of its own: the endpoints of tag {@code tariffs} ({@code /charge-types}) are
 * internal. It depends on {@code identity}, {@code audit} and {@code problem}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Charge types and tariffs")
package app.nadel.tariffs;
