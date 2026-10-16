/**
 * Cooperatives (tenants) and isolation of their data.
 * <p>
 * Public API: {@link app.nadel.tenancy.TenantContext}, {@link app.nadel.tenancy.TenantDirectory},
 * {@link app.nadel.tenancy.TenantSummary}. Everything in {@code internal} is closed to other modules.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Cooperatives")
package app.nadel.tenancy;
