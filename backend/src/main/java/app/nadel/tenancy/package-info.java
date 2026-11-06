/**
 * Cooperatives (tenants) and isolation of their data.
 * <p>
 * Public API: {@link app.nadel.tenancy.TenantContext}, {@link app.nadel.tenancy.TenantDirectory},
 * {@link app.nadel.tenancy.TenantSummary}, {@link app.nadel.tenancy.TenantProvisioning} (creates a cooperative: the
 * first-chairman command of feature 020). Everything in {@code internal} is closed to other modules. See README.md.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Cooperatives")
package app.nadel.tenancy;
